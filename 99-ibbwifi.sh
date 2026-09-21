#!/usr/bin/env bash
# NetworkManager Dispatcher script for IBB Wi-Fi Auto-Login
# Place this script in /etc/NetworkManager/dispatcher.d/99-ibbwifi.sh
# Make sure it is executable: chmod +x /etc/NetworkManager/dispatcher.d/99-ibbwifi.sh

INTERFACE="$1"
ACTION="$2"

# Change this path to where your script is installed
SCRIPT_PATH="/home/ytk/.gemini/antigravity/scratch/ibbwifi-autologin/ibbwifi_login.py"

if [ "$CONNECTION_ID" = "ibbWiFi" ] && [ "$ACTION" = "up" ]; then
    # Wait briefly for DHCP routing and DNS propagation
    sleep 3
    /usr/bin/python3 "$SCRIPT_PATH" >> /tmp/ibbwifi.log 2>&1
fi
