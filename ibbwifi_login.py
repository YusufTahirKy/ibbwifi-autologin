#!/usr/bin/env python3
import os
import re
import sys
import time
from pathlib import Path
import requests

PORTAL_URL = "https://viracaptive.ibbwifi.istanbul"
CHECK_URL = "http://connectivitycheck.gstatic.com/generate_204"

def is_connected():
    try:
        r = requests.get(CHECK_URL, timeout=3, allow_redirects=False)
        return r.status_code == 204
    except requests.RequestException:
        return False

def get_csrf_token(html):
    match = re.search(r'name="__RequestVerificationToken"[^>]*value="([^"]+)"', html)
    return match.group(1) if match else None

def load_credentials():
    phone = os.getenv("IBBWIFI_PHONE")
    password = os.getenv("IBBWIFI_PASSWORD")

    env_file = Path(__file__).resolve().parent / ".env"
    if (not phone or not password) and env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, val = line.split("=", 1)
                key, val = key.strip(), val.strip().strip("'\"")
                if key == "IBBWIFI_PHONE" and not phone:
                    phone = val
                elif key == "IBBWIFI_PASSWORD" and not password:
                    password = val

    return phone, password

def get_portal_url(session):
    test_urls = [
        CHECK_URL,
        "http://detectportal.firefox.com/canonical.html",
        "http://clients3.google.com/generate_204"
    ]
    for url in test_urls:
        try:
            res = session.get(url, timeout=5, allow_redirects=True)
            if "ibbwifi" in res.url or "viracaptive" in res.url:
                return res.url
        except requests.RequestException:
            pass
    return f"{PORTAL_URL}/"

def login():
    if is_connected():
        print("Already connected to the internet.")
        return True

    phone, password = load_credentials()
    if not phone or not password:
        print("Error: Phone number or password is missing in .env file.")
        return False

    session = requests.Session()
    session.headers.update({
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:156.0) Gecko/20100101 Firefox/156.0",
        "Origin": PORTAL_URL,
        "Accept": "*/*"
    })

    print("Connecting to portal...")
    portal_url = get_portal_url(session)

    try:
        res = session.get(portal_url, timeout=10)
    except requests.exceptions.ConnectionError as e:
        err_str = str(e)
        if "NameResolutionError" in err_str or "Failed to resolve" in err_str:
            print("Failed to resolve portal address.")
            print("Tip: If you are on a phone with Mobile Data active, turn off Mobile Data")
            print("or disable 'Switch to mobile data automatically' in your Wi-Fi settings.")
        else:
            print(f"Connection error: {e}")
        return False
    except requests.RequestException as e:
        print(f"Failed to connect to portal: {e}")
        return False

    token1 = get_csrf_token(res.text)
    if not token1:
        if is_connected():
            print("Already connected to the internet.")
            return True
        print("Could not retrieve verification token from page.")
        return False

    print("Submitting phone number...")
    payload_phone = {
        "PhoneNumber": phone,
        "CountryCode": "90",
        "FlagCode": "tr"
    }

    try:
        res = session.post(
            f"{PORTAL_URL}/LandingCheck",
            json=payload_phone,
            headers={
                "Content-Type": "application/json",
                "X-CSRF-TOKEN": token1,
                "Referer": portal_url
            },
            timeout=10
        )
    except requests.RequestException as e:
        print(f"Phone verification error: {e}")
        return False

    if res.status_code == 429:
        print("Rate limit reached. Please wait a few minutes before trying again.")
        return False

    token2 = get_csrf_token(res.text)
    if not token2:
        print("Failed to get session login token.")
        return False

    time.sleep(1)

    print("Authenticating password...")
    try:
        res = session.post(
            f"{PORTAL_URL}/Login",
            json={"Password": password},
            headers={
                "Content-Type": "application/json",
                "X-CSRF-TOKEN": token2,
                "Referer": portal_url
            },
            timeout=10
        )
    except requests.RequestException as e:
        print(f"Login error: {e}")
        return False

    try:
        data = login_res = res.json()
    except Exception:
        data = {}

    if res.ok and data.get("url"):
        session.get(data["url"], timeout=10)
        print("Login successful! Connected to the internet.")
        return True

    error_message = data.get("message") or res.text
    print(f"Login failed: {error_message}")
    return False

if __name__ == "__main__":
    success = login()
    sys.exit(0 if success else 1)
