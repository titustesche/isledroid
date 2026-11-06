#!/usr/bin/env bash
# Fine-tunes a German Piper voice on the prepared clips.
#   ./train.sh <dataset dir> [voice name]          e.g.  ./train.sh dataset glados
# Settings via environment: EPOCHS (default 1500), BATCH (default 16), BASE (checkpoint to start from).
# Stop any time with Ctrl+C; running it again continues from the last checkpoint.
set -euo pipefail
cd "$(dirname "$0")"
KIT="$PWD"
WORK="$KIT/work"
DATA="$(realpath "${1:?dataset directory from prepare.py}")"
NAME="${2:-myvoice}"
EPOCHS="${EPOCHS:-1500}"
BATCH="${BATCH:-16}"
RUN="$WORK/runs/$NAME"
mkdir -p "$RUN" "$WORK/base"
# shellcheck disable=SC1091
source "$WORK/.venv/bin/activate"

[ -s "$DATA/metadata.csv" ] || { echo "No $DATA/metadata.csv"; exit 1; }
echo "== $(wc -l < "$DATA/metadata.csv") clips"

# The German "Thorsten" medium voice: its pronunciation of German is what makes 25 minutes enough.
BASE="${BASE:-$WORK/base/thorsten-medium.ckpt}"
if [ ! -s "$BASE" ]; then
  echo "== Downloading the base checkpoint (850 MB, once)"
  curl -L --fail -o "$BASE.part" \
    "https://huggingface.co/datasets/rhasspy/piper-checkpoints/resolve/main/de/de_DE/thorsten/medium/epoch%3D3135-step%3D2702056.ckpt"
  mv "$BASE.part" "$BASE"
fi

# Continue an interrupted run; otherwise start fresh from the base voice's weights.
LAST="$(ls -t "$RUN"/lightning_logs/version_*/checkpoints/*.ckpt 2>/dev/null | head -n1 || true)"
if [ -n "$LAST" ]; then
  echo "== Continuing from $LAST"
  START=(--ckpt_path "$LAST")
else
  START=(--model.warmstart_ckpt "$BASE")
fi

cd "$WORK/piper1-gpl"
python -m piper.train fit \
  --data.voice_name "$NAME" \
  --data.csv_path "$DATA/metadata.csv" \
  --data.audio_dir "$DATA/wavs" \
  --model.sample_rate 22050 \
  --data.espeak_voice "de" \
  --data.cache_dir "$RUN/cache" \
  --data.config_path "$RUN/config.json" \
  --data.batch_size "$BATCH" \
  --data.length_bucketing true \
  --trainer.max_epochs "$EPOCHS" \
  --trainer.default_root_dir "$RUN" \
  "${START[@]}"

echo "== Training finished. Pack it for the phone:  python pack.py $NAME"
