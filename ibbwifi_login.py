#!/usr/bin/env python3
"""
IBB Wi-Fi Captive Portal Auto-Login Script
Handles the multi-step ASP.NET Core anti-forgery verification handshake.
"""

import os
import re
import sys
import time
import argparse
import subprocess
from pathlib import Path
import requests

PORTAL_BASE = "https://viracaptive.ibbwifi.istanbul"
CONNECTIVITY_CHECK_URL = "http://connectivitycheck.gstatic.com/generate_204"

def load_env(env_path: Path):
    """Simple parser for .env files without external dependencies."""
    if not env_path.exists():
        return
    with open(env_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, val = line.split("=", 1)
            key = key.strip()
            val = val.strip().strip("'\"")
            if key not in os.environ:
                os.environ[key] = val

def is_already_connected() -> bool:
    """Check if internet access is already available."""
    try:
        res = requests.get(CONNECTIVITY_CHECK_URL, timeout=3, allow_redirects=False)
        return res.status_code == 204
    except Exception:
        return False

def get_wifi_mac() -> str | None:
    """Attempt to get active Wi-Fi MAC address."""
    try:
        out = subprocess.check_output(
            "WIFI_DEV=$(nmcli -t -f DEVICE,TYPE device 2>/dev/null | awk -F: '$2==\"wifi\"{print $1; exit}'); cat /sys/class/net/$WIFI_DEV/address 2>/dev/null",
            shell=True, text=True
        ).strip()
        if out and len(out.split(":")) == 6:
            return out.lower()
    except Exception:
        pass
    return None

def detect_portal_url(session: requests.Session) -> str:
    """
    Detect the full captive portal URL with session tokens, location, and MAC
    by capturing the network gateway redirect.
    """
    detect_urls = [
        "http://connectivitycheck.gstatic.com/generate_204",
        "http://detectportal.firefox.com/canonical.html",
        "http://clients3.google.com/generate_204"
    ]
    for url in detect_urls:
        try:
            res = session.get(url, timeout=5, allow_redirects=True)
            if "ibbwifi" in res.url or "viracaptive" in res.url:
                return res.url
        except Exception:
            continue

    # Fallback to appending current Wi-Fi MAC
    mac = get_wifi_mac()
    if mac:
        return f"{PORTAL_BASE}/?mac={mac}"
    return f"{PORTAL_BASE}/"

def extract_token(html_text: str) -> str | None:
    """Extract __RequestVerificationToken from ASP.NET HTML."""
    match = re.search(r'name="__RequestVerificationToken"\s+type="hidden"\s+value="([^"]+)"', html_text)
    return match.group(1) if match else None

def login(phone: str, password: str, country_code: str = "90", flag_code: str = "tr") -> bool:
    if is_already_connected():
        print("[+] Already connected to the internet. No login required.")
        return True

    session = requests.Session()
    session.headers.update({
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:156.0) Gecko/20100101 Firefox/156.0",
        "Origin": PORTAL_BASE,
        "Accept": "*/*"
    })

    print("[*] Detecting portal session URL...")
    portal_landing_url = detect_portal_url(session)
    print(f"[*] Target portal URL: {portal_landing_url}")

    print("[*] [Step 1/3] Fetching portal session cookies and CSRF token...")
    try:
        landing_res = session.get(portal_landing_url, timeout=10)
    except requests.RequestException as e:
        print(f"[-] Failed to reach portal: {e}")
        return False

    token1 = extract_token(landing_res.text)
    if not token1:
        print("[-] Could not extract initial CSRF token.")
        if is_already_connected():
            print("[+] Connection verified. Already online!")
            return True
        return False

    print("[*] [Step 2/3] Submitting phone number verification...")
    landing_payload = {
        "PhoneNumber": phone,
        "CountryCode": country_code,
        "FlagCode": flag_code
    }

    try:
        check_res = session.post(
            f"{PORTAL_BASE}/LandingCheck",
            json=landing_payload,
            headers={
                "Content-Type": "application/json",
                "X-CSRF-TOKEN": token1,
                "Referer": portal_landing_url
            },
            timeout=10
        )
    except requests.RequestException as e:
        print(f"[-] Phone verification request failed: {e}")
        return False

    if check_res.status_code == 429:
        print("[-] Rate limit reached (HTTP 429). Please wait a few minutes before retrying.")
        return False

    token2 = extract_token(check_res.text)
    if not token2:
        print("[-] Failed to retrieve secondary verification token.")
        return False

    time.sleep(1)

    print("[*] [Step 3/3] Authenticating password...")
    login_payload = {"Password": password}
    try:
        login_res = session.post(
            f"{PORTAL_BASE}/Login",
            json=login_payload,
            headers={
                "Content-Type": "application/json",
                "X-CSRF-TOKEN": token2,
                "Referer": portal_landing_url
            },
            timeout=10
        )
    except requests.RequestException as e:
        print(f"[-] Login request failed: {e}")
        return False

    try:
        data = login_res.json()
    except Exception:
        data = {}

    if login_res.ok and data.get("url"):
        wispr_url = data["url"]
        print(f"[*] Finalizing network authorization via gateway...")
        session.get(wispr_url, timeout=10)
        print("[+] Successfully logged in to IBB Wi-Fi! Internet active.")
        return True
    else:
        err_msg = data.get("message") or login_res.text
        print(f"[-] Login failed: {err_msg}")
        return False

def main():
    parser = argparse.ArgumentParser(description="Auto-login to IBB Wi-Fi Captive Portal")
    parser.add_argument("-p", "--phone", help="Phone number (10 digits without leading 0, e.g. 5051234567)")
    parser.add_argument("-w", "--password", help="IBB Wi-Fi portal password")
    parser.add_argument("--env-file", help="Path to custom .env file", default=None)
    args = parser.parse_args()

    search_paths = [
        Path(args.env_file) if args.env_file else None,
        Path.cwd() / ".env",
        Path(__file__).resolve().parent / ".env",
        Path.home() / ".config" / "ibbwifi" / ".env"
    ]

    for p in search_paths:
        if p and p.exists():
            load_env(p)
            break

    phone = args.phone or os.getenv("IBBWIFI_PHONE")
    password = args.password or os.getenv("IBBWIFI_PASSWORD")

    if not phone or not password:
        print("[-] Error: Missing phone number or password.")
        print("    Provide via arguments (-p / -w) or set in .env (IBBWIFI_PHONE, IBBWIFI_PASSWORD)")
        sys.exit(1)

    success = login(phone=phone, password=password)
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
