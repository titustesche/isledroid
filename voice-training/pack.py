"""
Packs a trained voice for the phone: exports the newest checkpoint to ONNX, adds what sherpa-onnx
(the app's speech engine) needs, renders a test sentence, and writes

    work/out/vits-piper-de_DE-<name>-custom.tar.bz2      → Settings → Assistant → Piper → import voice

Usage:  python pack.py <name> [--checkpoint path.ckpt] [--text "Satz zum Probehören"]
Works mid-training too (on the latest checkpoint), to hear how far it got.
"""
import argparse
import json
import subprocess
import sys
import tarfile
from pathlib import Path

KIT = Path(__file__).resolve().parent
WORK = KIT / "work"


def newest_checkpoint(run: Path) -> Path:
    found = sorted(run.glob("lightning_logs/version_*/checkpoints/*.ckpt"), key=lambda p: p.stat().st_mtime)
    if not found:
        sys.exit(f"No checkpoint under {run}. Has training saved one yet?")
    return found[-1]


def add_sherpa_metadata(model: Path, config: dict):
    """The keys sherpa-onnx reads from a Piper model (as in its own conversion script)."""
    import onnx

    m = onnx.load(str(model))
    meta = {
        "model_type": "vits",
        "comment": "piper",  # must be "piper" for models from piper
        "language": config.get("language", {}).get("name_english", "German"),
        "voice": config["espeak"]["voice"],
        "has_espeak": 1,
        "n_speakers": config.get("num_speakers", 1),
        "sample_rate": config["audio"]["sample_rate"],
    }
    del m.metadata_props[:]
    for k, v in meta.items():
        p = m.metadata_props.add()
        p.key, p.value = k, str(v)
    onnx.save(m, str(model))


def write_tokens(path: Path, config: dict):
    with path.open("w", encoding="utf-8") as f:
        for symbol, ids in config["phoneme_id_map"].items():
            f.write(f"{symbol} {ids[0]}\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("name")
    ap.add_argument("--checkpoint", type=Path)
    ap.add_argument("--lang", default="de_DE")
    ap.add_argument("--text", default="Hallo. Ich habe deine Ergebnisse ausgewertet, und sie sind genau so, wie ich befürchtet hatte.")
    args = ap.parse_args()

    run = WORK / "runs" / args.name
    ckpt = args.checkpoint or newest_checkpoint(run)
    config = json.loads((run / "config.json").read_text(encoding="utf-8"))
    vid = f"{args.lang}-{args.name.lower().replace('-', '_')}-custom"
    folder = WORK / "out" / f"vits-piper-{vid}"
    folder.mkdir(parents=True, exist_ok=True)
    model = folder / f"{vid}.onnx"

    print(f"Exporting {ckpt.name} …")
    subprocess.run([sys.executable, "-m", "piper.train.export_onnx", "--checkpoint", str(ckpt), "--output-file", str(model)],
                   check=True, cwd=WORK / "piper1-gpl")
    (folder / f"{vid}.onnx.json").write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")

    # Piper's own player on the PC, before the metadata goes in: what the phone will sound like.
    sample = WORK / "out" / f"{vid}-test.wav"
    try:
        # Piper finds <model>.onnx.json next to the model on its own.
        subprocess.run([sys.executable, "-m", "piper", "-m", str(model), "-f", str(sample), "--", args.text],
                       check=True, cwd=WORK / "piper1-gpl")
        print(f"Test sentence: {sample}")
    except subprocess.CalledProcessError:
        print("(Couldn't render the test sentence; the archive is still fine.)")

    add_sherpa_metadata(model, config)
    write_tokens(folder / "tokens.txt", config)

    archive = WORK / "out" / f"vits-piper-{vid}.tar.bz2"
    with tarfile.open(archive, "w:bz2") as tar:
        tar.add(folder, arcname=folder.name)
    print(f"\nFor the phone: {archive} ({archive.stat().st_size / 1e6:.0f} MB)")


if __name__ == "__main__":
    sys.exit(main())
