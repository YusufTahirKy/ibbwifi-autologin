#!/usr/bin/env bash
# Disable automatic login on Linux (NetworkManager)

TARGET="/etc/NetworkManager/dispatcher.d/99-ibbwifi.sh"

if [ -f "$TARGET" ]; then
    echo "[*] Removing $TARGET..."
    sudo rm -f "$TARGET"
    echo "[+] Auto-login disabled successfully."
else
    echo "[!] Dispatcher script was not installed or already removed."
fi
