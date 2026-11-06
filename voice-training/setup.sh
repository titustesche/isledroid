#!/usr/bin/env bash
# One-time setup on the training PC (Fedora, AMD GPU via ROCm).
# Creates ./work/.venv with PyTorch (ROCm), Whisper and Piper's training code.
set -euo pipefail
cd "$(dirname "$0")"
KIT="$PWD"
WORK="$KIT/work"
mkdir -p "$WORK"

PY="${PYTHON:-python3.12}"
command -v "$PY" >/dev/null || PY=python3

echo "== System packages (asks for your password)"
sudo dnf install -y gcc gcc-c++ cmake ninja-build git espeak-ng ffmpeg-free python3-devel "${PY}" "${PY}-devel" || \
  sudo dnf install -y gcc gcc-c++ cmake ninja-build git espeak-ng ffmpeg-free python3-devel

# The GPU device nodes (/dev/kfd, /dev/dri) belong to these groups.
if ! id -nG "$USER" | grep -qw render; then
  sudo usermod -aG render,video "$USER"
  echo "!! Added you to the render and video groups. Log out and back in (or reboot), then run this script again."
  exit 1
fi

echo "== Python environment"
[ -d "$WORK/.venv" ] || "$PY" -m venv "$WORK/.venv"
# shellcheck disable=SC1091
source "$WORK/.venv/bin/activate"
python -m pip install --upgrade pip wheel setuptools cython

echo "== Piper training code"
if [ ! -d "$WORK/piper1-gpl" ]; then
  git clone https://github.com/OHF-voice/piper1-gpl.git "$WORK/piper1-gpl"
fi
(
  cd "$WORK/piper1-gpl"
  python -m pip install -e '.[train]'
  ./build_monotonic_align.sh
  python setup.py build_ext --inplace
)
python -m pip install openai-whisper onnx numpy

echo "== PyTorch for ROCm"
# Piper's install may have pulled the CUDA build; replace it with a ROCm one that runs on this card.
# RDNA4 (RX 9070 XT, gfx1201) needs ROCm 6.4.1 or newer. Override with TORCH_INDEX=<url> if needed.
CANDIDATES=("${TORCH_INDEX:-}" "https://download.pytorch.org/whl/rocm6.4" "https://download.pytorch.org/whl/rocm7.0")
ok=""
for idx in "${CANDIDATES[@]}"; do
  [ -n "$idx" ] || continue
  echo "-- trying $idx"
  if python -m pip install --force-reinstall --no-deps torch torchaudio --index-url "$idx" && python "$KIT/gpu_check.py"; then
    ok="$idx"
    break
  fi
done
if [ -z "$ok" ]; then
  echo "!! None of the PyTorch ROCm builds ran on this GPU."
  echo "   Look up the current ROCm PyTorch wheels for gfx1201 (AMD's docs: 'Install PyTorch on ROCm')"
  echo "   and rerun with: TORCH_INDEX=<that index url> ./setup.sh"
  exit 1
fi
echo "$ok" > "$WORK/torch-index.txt"
echo "== Done. PyTorch from $ok runs on your GPU."
