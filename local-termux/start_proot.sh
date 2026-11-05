#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
D="$(cd "$(dirname "$0")" && pwd)"
proot-distro login ubuntu --bind "$D":/srv/sv -- /root/venv/bin/python /srv/sv/local_server.py
