"""
Turns one long recording of voice lines into a Piper training set.

  1. Cuts it at the pauses between lines (long stretches are split again at Whisper's word gaps).
  2. Transcribes every clip with Whisper, offline.
  3. Throws out what can't be speech, flags what looks doubtful, levels the volume, and writes
       <out>/wavs/clip_0001.wav ...   22.05 kHz mono, 16 bit
       <out>/metadata.csv             clip_0001.wav|Text   (what training reads)
       <out>/review.html              listen, fix the text, untick bad clips, save a new metadata.csv

Usage:  python prepare.py lines.mp3 dataset/ [--model large-v3] [--language de]
"""
import argparse
import html
import json
import re
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

SR = 22050          # Piper "medium" voices
WHISPER_SR = 16000


def load(path: Path, rate: int) -> np.ndarray:
    """Any format ffmpeg reads → mono float32 at [rate]."""
    cmd = ["ffmpeg", "-nostdin", "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(rate), "-f", "f32le", "-"]
    raw = subprocess.run(cmd, check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def speech_regions(audio: np.ndarray, threshold_db: float, min_gap: float, pad: float) -> list[tuple[float, float]]:
    """Stretches louder than [threshold_db] below the recording's loud parts, joined across gaps shorter than [min_gap]."""
    hop = int(SR * 0.02)
    frames = len(audio) // hop
    rms = np.sqrt(np.mean(audio[: frames * hop].reshape(frames, hop) ** 2, axis=1) + 1e-12)
    db = 20 * np.log10(rms)
    loud = np.percentile(db, 95)
    voiced = db > loud + threshold_db
    regions, start = [], None
    for i, v in enumerate(voiced):
        t = i * hop / SR
        if v and start is None:
            start = t
        elif not v and start is not None:
            regions.append([start, t])
            start = None
    if start is not None:
        regions.append([start, frames * hop / SR])
    merged = []
    for r in regions:
        if merged and r[0] - merged[-1][1] < min_gap:
            merged[-1][1] = r[1]
        else:
            merged.append(r)
    total = len(audio) / SR
    return [(max(0.0, a - pad), min(total, b + pad)) for a, b in merged if b - a > 0.15]


def split_long(model, audio16: np.ndarray, start: float, end: float, max_len: float, language: str) -> list[tuple[float, float]]:
    """Cuts an over-long stretch at the biggest word gaps, preferring sentence ends, so every piece fits [max_len]."""
    piece = audio16[int(start * WHISPER_SR): int(end * WHISPER_SR)]
    res = model.transcribe(piece, language=language, word_timestamps=True, condition_on_previous_text=False, temperature=0.0)
    words = [w for s in res["segments"] for w in s.get("words", [])]
    if len(words) < 2:
        return [(start, end)]
    cuts = [start]
    seg_start = 0.0
    for a, b in zip(words, words[1:]):
        gap = b["start"] - a["end"]
        sentence_end = a["word"].strip()[-1:] in ".!?"
        long_enough = a["end"] - seg_start > 1.5
        too_long_soon = b["end"] - seg_start > max_len
        if long_enough and ((sentence_end and gap > 0.12) or too_long_soon):
            cut = (a["end"] + b["start"]) / 2
            cuts.append(start + cut)
            seg_start = cut
    cuts.append(end)
    return list(zip(cuts, cuts[1:]))


# Things Whisper writes when there's no speech, learned from subtitle data.
HALLUCINATIONS = re.compile(r"untertitel|zdf|copyright|vielen dank (fürs|für's) zuschauen|amara\.org|swr ", re.I)


def clean_text(t: str) -> str:
    t = t.replace("„", "\"").replace("“", "\"").replace("”", "\"").replace("‚", "'").replace("‘", "'").replace("’", "'")
    t = re.sub(r"[\[\(<].*?[\]\)>]", "", t)   # [Musik], (lacht), <unk>
    t = re.sub(r"\s+", " ", t).strip()
    return t


def write_wav(path: Path, audio: np.ndarray):
    pcm = (np.clip(audio, -1, 1) * 32767).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def level(clip: np.ndarray, target_db: float = -20.0, peak: float = 0.89) -> np.ndarray:
    """Same loudness for every clip (training dislikes jumps), never clipping."""
    rms = np.sqrt(np.mean(clip ** 2) + 1e-12)
    gain = 10 ** (target_db / 20) / rms
    gain = min(gain, peak / (np.max(np.abs(clip)) + 1e-9))
    return clip * gain


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--model", default="large-v3", help="Whisper model (large-v3 needs ~10 GB VRAM; 'medium' is lighter)")
    ap.add_argument("--language", default="de")
    ap.add_argument("--min", type=float, default=1.0, help="shortest clip in seconds")
    ap.add_argument("--max", type=float, default=12.0, help="longest clip in seconds")
    ap.add_argument("--silence-db", type=float, default=-35.0, help="how far below the loud parts counts as a pause")
    ap.add_argument("--min-gap", type=float, default=0.35, help="pauses shorter than this don't cut")
    args = ap.parse_args()

    import torch
    import whisper

    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"Loading Whisper {args.model} on {device} …")
    model = whisper.load_model(args.model, device=device)

    print("Reading audio …")
    audio = load(args.input, SR)
    audio16 = load(args.input, WHISPER_SR)
    print(f"  {len(audio) / SR / 60:.1f} minutes")

    regions = speech_regions(audio, args.silence_db, args.min_gap, pad=0.12)
    print(f"  {len(regions)} stretches between pauses")

    # Very short bits are joined to a close neighbour; very long ones split at word gaps.
    joined = []
    for r in regions:
        if joined and (r[1] - r[0] < args.min or joined[-1][1] - joined[-1][0] < args.min) \
                and r[0] - joined[-1][1] < 0.6 and r[1] - joined[-1][0] <= args.max:
            joined[-1] = (joined[-1][0], r[1])
        else:
            joined.append(r)
    pieces = []
    for a, b in joined:
        pieces.extend(split_long(model, audio16, a, b, args.max, args.language) if b - a > args.max else [(a, b)])

    out = args.out
    (out / "wavs").mkdir(parents=True, exist_ok=True)
    clips = []
    fp16 = device == "cuda"
    for n, (a, b) in enumerate(pieces, 1):
        dur = b - a
        name = f"clip_{n:04d}.wav"
        seg16 = audio16[int(a * WHISPER_SR): int(b * WHISPER_SR)]
        res = model.transcribe(seg16, language=args.language, condition_on_previous_text=False, temperature=0.0, fp16=fp16)
        segs = res["segments"]
        text = clean_text(res["text"])
        logprob = float(np.mean([s["avg_logprob"] for s in segs])) if segs else -9.0
        no_speech = float(np.max([s["no_speech_prob"] for s in segs])) if segs else 1.0
        ratio = float(np.max([s["compression_ratio"] for s in segs])) if segs else 0.0
        words = len(text.split())

        flags = []
        if dur < args.min:
            flags.append("short")
        if dur > args.max + 0.5:
            flags.append("long")
        if logprob < -0.8:
            flags.append("unsure")
        if ratio > 2.4:
            flags.append("repeats")
        if words and not (0.8 <= words / dur <= 5.0):
            flags.append("odd pace")
        drop = not text or (no_speech > 0.6 and logprob < -1.0) or bool(HALLUCINATIONS.search(text))
        if drop:
            flags.append("no speech?")

        write_wav(out / "wavs" / name, level(audio[int(a * SR): int(b * SR)]))
        clips.append({"file": name, "text": text, "start": round(a, 2), "dur": round(dur, 2), "flags": flags,
                      "use": not drop and "short" not in flags and "long" not in flags})
        mark = "x" if clips[-1]["use"] else " "
        print(f"  [{mark}] {name} {dur:5.1f}s {' '.join(flags):<20} {text[:70]}")

    used = [c for c in clips if c["use"]]
    (out / "metadata.csv").write_text("".join(f"{c['file']}|{c['text']}\n" for c in used), encoding="utf-8")
    (out / "clips.json").write_text(json.dumps(clips, ensure_ascii=False, indent=1), encoding="utf-8")
    (out / "review.html").write_text(review_page(clips), encoding="utf-8")
    minutes = sum(c["dur"] for c in used) / 60
    print(f"\n{len(used)} of {len(clips)} clips kept, {minutes:.1f} minutes. "
          f"Flagged: {sum(1 for c in clips if c['flags'])}.\nNext: open {out / 'review.html'} in a browser.")


def review_page(clips: list[dict]) -> str:
    data = json.dumps(clips, ensure_ascii=False).replace("</", "<\\/")
    return """<!doctype html><html lang="de"><meta charset="utf-8"><title>Review clips</title>
<style>
body{font:15px system-ui,sans-serif;margin:0;background:#111;color:#eee}
header{position:sticky;top:0;background:#000;padding:12px 16px;display:flex;gap:12px;align-items:center;flex-wrap:wrap;border-bottom:1px solid #333;z-index:1}
button{font:inherit;padding:8px 14px;border-radius:8px;border:1px solid #555;background:#222;color:#eee;cursor:pointer}
button.main{background:#34c759;color:#000;border-color:#34c759;font-weight:600}
.row{display:grid;grid-template-columns:28px 110px 260px 1fr;gap:12px;align-items:center;padding:8px 16px;border-bottom:1px solid #222}
.row.off{opacity:.4}.row.flagged{background:#1d1a10}
.flags{color:#ff9f0a;font-size:12px}.name{font:12px monospace;color:#999}
textarea{width:100%;box-sizing:border-box;font:15px system-ui;background:#1b1b1b;color:#eee;border:1px solid #333;border-radius:6px;padding:6px;resize:vertical;min-height:36px}
audio{width:100%}label{display:flex;gap:6px;align-items:center}
</style>
<header><button class="main" id="save">Save metadata.csv</button>
<label><input type="checkbox" id="onlyFlagged"> only flagged</label><span id="stats"></span>
<span style="color:#999">Clicking into a text plays its clip, Ctrl+Space replays it. Changes are kept in this browser until you save.</span></header>
<main id="list"></main>
<script>
const clips = """ + data + """;
const KEY = "piper-review:" + clips.length + ":" + (clips[0]||{}).file;
try { const s = JSON.parse(localStorage.getItem(KEY) || "null"); if (s && s.length === clips.length) s.forEach((c,i)=>Object.assign(clips[i], c)); } catch(e) {}
const list = document.getElementById("list");
function persist(){ try { localStorage.setItem(KEY, JSON.stringify(clips.map(c=>({text:c.text,use:c.use})))); } catch(e) {} stats(); }
function stats(){ const u = clips.filter(c=>c.use); document.getElementById("stats").textContent =
  u.length + " of " + clips.length + " clips, " + (u.reduce((a,c)=>a+c.dur,0)/60).toFixed(1) + " min"; }
function render(){
  list.innerHTML = "";
  const only = document.getElementById("onlyFlagged").checked;
  clips.forEach((c,i)=>{
    if (only && !c.flags.length) return;
    const row = document.createElement("div");
    row.className = "row" + (c.use ? "" : " off") + (c.flags.length ? " flagged" : "");
    row.innerHTML = '<input type="checkbox"' + (c.use?' checked':'') + ' aria-label="use this clip">' +
      '<div><div class="name">' + c.file + ' · ' + c.dur + 's</div><div class="flags">' + c.flags.join(", ") + '</div></div>' +
      '<audio controls preload="none" src="wavs/' + c.file + '"></audio><textarea rows="1"></textarea>';
    const [box,,audio,ta] = row.children;
    ta.value = c.text;
    box.onchange = ()=>{ c.use = box.checked; row.classList.toggle("off", !c.use); persist(); };
    ta.oninput = ()=>{ c.text = ta.value.replace(/\\s+/g," ").trim(); persist(); };
    ta.onkeydown = e=>{ if (e.key===" " && e.ctrlKey) { e.preventDefault(); audio.paused ? audio.play() : audio.pause(); } };
    ta.onfocus = ()=>{ audio.currentTime = 0; audio.play(); };
    list.appendChild(row);
  });
  stats();
}
document.getElementById("onlyFlagged").onchange = render;
document.getElementById("save").onclick = ()=>{
  const csv = clips.filter(c=>c.use && c.text).map(c=>c.file + "|" + c.text.replace(/\\|/g," ")).join("\\n") + "\\n";
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([csv], {type:"text/csv"}));
  a.download = "metadata.csv"; a.click();
};
render();
</script></html>"""


if __name__ == "__main__":
    sys.exit(main())
