# Training your own Piper voice

Runs on the PC (Fedora, AMD GPU through ROCm). The result goes into the app via
**Settings → Assistant → Voice: Piper → import voice**. Private use only: the model stays on your devices.

## 1. Setup (once)

```sh
./setup.sh
```

Installs the build tools, Piper's training code, Whisper and a PyTorch build for ROCm into `work/`.
It tries the ROCm builds one after another and keeps the first one that actually computes on the card
(`gpu_check.py`). If it adds you to the `render`/`video` groups, log out and in, then run it again.

## 2. Prepare the recording

```sh
source work/.venv/bin/activate
python prepare.py /path/to/lines.mp3 dataset
```

Cuts the file at the pauses, transcribes every clip with Whisper (German, offline), drops what isn't
speech and levels the volume. Then open `dataset/review.html` in a browser:

- clicking into a line plays its clip; fix wrong words, untick clips with music, effects, overlapping
  voices, laughing or singing (wrong text and noisy clips are what makes a voice crackle);
- "only flagged" shows the clips Whisper was unsure about first;
- **Save metadata.csv**, then move the download over `dataset/metadata.csv`.

Knobs if the cutting is off: `--silence-db` (lower = only deeper pauses cut, e.g. -40), `--min-gap`,
`--min`/`--max` clip length, `--model medium` if large-v3 doesn't fit.

## 3. Train

```sh
./train.sh dataset glados
```

Starts from the German "Thorsten" voice and adapts it. `EPOCHS` (default 1500) and `BATCH`
(default 16, fits 16 GB) are environment variables. Ctrl+C stops it; running the same command again
continues from the last checkpoint. Expect several hours.

## 4. Pack for the phone

```sh
python pack.py glados
```

Works mid-training too: exports the newest checkpoint, renders a test sentence
(`work/out/de_DE-glados-custom-test.wav`) and writes `work/out/vits-piper-de_DE-glados-custom.tar.bz2`.
Copy that to the phone and import it. Packing again later and re-importing replaces the voice.
The phone needs one regular Piper voice downloaded already (for the shared pronunciation data).
