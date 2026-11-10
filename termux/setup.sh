#!/data/data/com.termux/files/usr/bin/bash
set -e
pkg update -y
pkg upgrade -y
pkg install -y python ffmpeg coreutils procps
python -m pip install -U "yt-dlp[default]"
termux-setup-storage
printf '\nTermux setup completed. Approve the storage permission if Android asks.\n'
printf 'After storage setup finishes, run:\n\nmkdir -p "$HOME/storage/downloads/Download Studio"\n\n'
printf 'Then select Download/Download Studio in Download Studio settings.\n'
printf 'Also edit ~/.termux/termux.properties and add:\n\nallow-external-apps=true\n\n'
printf 'Force-stop and reopen Termux. Grant Download Studio the "Run commands in Termux" permission in Android Settings.\n'
