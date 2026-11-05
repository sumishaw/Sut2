#!/data/data/com.termux/files/usr/bin/bash
# Run once inside Termux (install Termux from F-Droid, NOT the Play Store).
set -e
termux-setup-storage || true
pkg update -y && pkg install -y git ffmpeg python tur-repo
pkg install -y python-torch || echo "!! python-torch not available: KokoClone cannot run here, see README"
cd ~ && [ -d kokoclone ] || git clone https://github.com/Ashish-Patnaik/kokoclone.git
cd kokoclone && pip install -r requirements.txt
echo "Setup done. Start the server with:  bash ~/SubVoice/local-termux/start.sh"
