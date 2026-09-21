#!/usr/bin/env bash
# Enable automatic login on Linux (NetworkManager)
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="/etc/NetworkManager/dispatcher.d/99-ibbwifi.sh"

echo "[*] Creating NetworkManager Dispatcher hook at $TARGET..."

sudo bash -c "cat > '$TARGET'" <<EOF
#!/usr/bin/env bash
INTERFACE="\$1"
ACTION="\$2"

if [ "\$CONNECTION_ID" = "ibbWiFi" ] && [ "\$ACTION" = "up" ]; then
    sleep 3
    /usr/bin/python3 "$SCRIPT_DIR/ibbwifi_login.py" >> /tmp/ibbwifi.log 2>&1
fi
EOF

sudo chmod +x "$TARGET"
sudo systemctl enable --now NetworkManager-dispatcher.service 2>/dev/null || true

echo "[+] Successfully enabled! IBB Wi-Fi will now auto-login when connected."
