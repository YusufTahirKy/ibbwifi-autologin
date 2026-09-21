# IBB Wi-Fi Captive Portal Auto-Login

Automated login script for the **ibbWiFi** captive portal in Istanbul.

Eliminates the daily friction of manually entering your phone number and password when connecting to IBB Wi-Fi networks (dormitories, transit, public spaces).

---

## Features

- **Multi-Step ASP.NET Handshake:** Seamlessly solves the 3-step CSRF token (`__RequestVerificationToken`) & cookie exchange.
- **Auto Gateway Activation:** Automatically triggers WISPr redirection to finalize internet access.
- **Secure by Design:** Credentials are kept locally in `.env` (which is git-ignored) or passed via arguments.
- **Zero Heavy Dependencies:** Only requires Python 3 and the `requests` library.
- **Smart Connectivity Detection:** Checks if internet is already accessible before issuing unnecessary requests.
- **NetworkManager Integration:** Runs automatically in the background as soon as you connect to `ibbWiFi`.

---

## How It Works

The modern IBB captive portal (`viracaptive.ibbwifi.istanbul`) operates across three verification stages:

```
[Connect to ibbWiFi]
        │
        ▼
[Step 1: GET /] ─────────► Extracts Session Cookies & CSRF Token 1
        │
        ▼
[Step 2: POST /LandingCheck] ──► Submits Phone Number & Token 1
        │                        Follows redirect to / (Login View)
        ▼                        Extracts Fresh CSRF Token 2
[Step 3: POST /Login] ─────────► Submits Password & Token 2
        │                        Receives WISPr Authorization URL
        ▼
[WISPr Activation] ────────────► Requests Gateway URL -> Internet Active!
```

---

## Installation & Setup

### 1. Clone the repository
```bash
git clone git@github.com:YusufTahirKy/ibbwifi-autologin.git
cd ibbwifi-autologin
```

### 2. Install dependencies
```bash
pip install -r requirements.txt
```

### 3. Configure credentials
Copy `.env.example` to `.env`:
```bash
cp .env.example .env
```
Edit `.env` with your phone number and portal password:
```env
IBBWIFI_PHONE=5051234567
IBBWIFI_PASSWORD=your_password
```

---

## Manual Usage

Run the script directly:
```bash
python3 ibbwifi_login.py
```

Or pass credentials via CLI flags:
```bash
python3 ibbwifi_login.py -p 5051234567 -w your_password
```

---

## Automatic Execution (Linux / NetworkManager)

To execute this script automatically whenever your device connects to `ibbWiFi`:

1. Copy the dispatcher hook to NetworkManager:
   ```bash
   sudo cp 99-ibbwifi.sh /etc/NetworkManager/dispatcher.d/99-ibbwifi.sh
   sudo chmod +x /etc/NetworkManager/dispatcher.d/99-ibbwifi.sh
   ```

2. Verify that the path in `/etc/NetworkManager/dispatcher.d/99-ibbwifi.sh` points to your `ibbwifi_login.py`.

3. Ensure `NetworkManager-dispatcher.service` is enabled:
   ```bash
   sudo systemctl enable --now NetworkManager-dispatcher.service
   ```

Logs are written to `/tmp/ibbwifi.log`.

---

## Security

- **Never commit your `.env` file.** Your credentials stay safely on your local machine.
- If you share your computer, restrict permissions on your `.env` file:
  ```bash
  chmod 600 .env
  ```

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
