#!/usr/bin/env python3
"""
IBB Wi-Fi Captive Portal Auto-Login Script
Handles the multi-step ASP.NET Core anti-forgery verification handshake.
"""

import os
import re
import sys
import argparse
from pathlib import Path
import requests

# Base URLs
PORTAL_URL = "https://viracaptive.ibbwifi.istanbul"
CONNECTIVITY_CHECK_URL = "http://connectivitycheck.gstatic.com/generate_204"

def load_env(env_path: Path):
    """Simple parser for .env files without requiring third-party libraries."""
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
        res = requests.get(CONNECTIVITY_CHECK_URL, timeout=3)
        return res.status_code == 204
    except Exception:
        return False

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
        "Origin": PORTAL_URL,
        "Accept": "*/*"
    })

    print("[*] [Step 1/3] Accessing portal landing page...")
    try:
        landing_res = session.get(PORTAL_URL, timeout=10)
    except requests.RequestException as e:
        print(f"[-] Failed to reach portal: {e}")
        return False

    token1 = extract_token(landing_res.text)
    if not token1:
        print("[-] Could not extract initial CSRF token. Checking connection...")
        if is_already_connected():
            print("[+] Internet is reachable!")
            return True
        print("[-] Portal page did not contain verification token.")
        return False

    print("[*] [Step 2/3] Submitting phone number verification...")
    landing_payload = {
        "PhoneNumber": phone,
        "CountryCode": country_code,
        "FlagCode": flag_code
    }

    try:
        check_res = session.post(
            f"{PORTAL_URL}/LandingCheck",
            json=landing_payload,
            headers={
                "Content-Type": "application/json",
                "X-CSRF-TOKEN": token1,
                "Referer": landing_res.url
            },
            timeout=10
        )
    except requests.RequestException as e:
        print(f"[-] Phone verification request failed: {e}")
        return False

    if check_res.status_code == 429:
        print("[-] Rate limit exceeded (HTTP 429). Please wait 1-5 minutes.")
        return False

    token2 = extract_token(check_res.text)
    if not token2:
        print("[-] Failed to retrieve secondary verification token.")
        return False

    print("[*] [Step 3/3] Authenticating password...")
    login_payload = {"Password": password}
    try:
        login_res = session.post(
            f"{PORTAL_URL}/Login",
            json=login_payload,
            headers={
                "Content-Type": "application/json",
                "X-CSRF-TOKEN": token2,
                "Referer": PORTAL_URL
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

    # Look for .env in current directory, script directory, or ~/.config/ibbwifi/.env
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
