#!/data/data/com.termux/files/usr/bin/bash
# Most reliable route: a real Ubuntu (glibc) inside Termux, where official PyTorch aarch64 CPU wheels install normally.
# Needs a 64-bit ARM tablet, ~6 GB free storage, 4 GB+ RAM. Run once from Termux (F-Droid build).
set -e
pkg update -y && pkg install -y proot-distro
proot-distro install ubuntu || true
proot-distro login ubuntu -- bash -c '
  set -e
  apt-get update && apt-get install -y python3 python3-venv python3-pip git ffmpeg
  python3 -m venv /root/venv
  /root/venv/bin/pip install --upgrade pip
  /root/venv/bin/pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu
  [ -d /root/kokoclone ] || git clone https://github.com/Ashish-Patnaik/kokoclone.git /root/kokoclone
  /root/venv/bin/pip install -r /root/kokoclone/requirements.txt
  /root/venv/bin/python -c "import torch; print(\"torch OK\", torch.__version__)"
'
echo "Done. Start with: bash $(dirname "$0")/start_proot.sh"
