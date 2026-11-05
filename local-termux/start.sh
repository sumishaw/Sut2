#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
python "$(dirname "$0")/local_server.py"
