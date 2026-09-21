# IBB Wi-Fi Captive Portal Auto-Login

A fast, lightweight, cross-platform automated login script for the **ibbWiFi** captive portal in Istanbul.

Runs seamlessly on **Linux**, **Windows**, and **Android (Termux)**.

---

## Supported Platforms

| Platform | Manual Run | Automatic on Connect | How to Disable |
|---|---|---|---|
| **Android (.APK)** | Tap "Şimdi Giriş Yap" | Background Auto-Login | Switch to OFF |
| **Linux** | `python3 ibbwifi_login.py` | NetworkManager Dispatcher | `./uninstall_linux.sh` |
| **Windows** | Double-click `run_windows.bat` | Task Scheduler on Wi-Fi connect | Disable Task in Task Scheduler |
| **Android (Termux)**| Tap Widget / Termux | Termux:Tasker / MacroDroid | Turn off MacroDroid / Tasker rule |

> 📱 **Android Kullanıcıları İçin:** Python kurmak istemiyorsanız, doğrudan GitHub [Releases](https://github.com/YusufTahirKy/ibbwifi-autologin/releases) sayfasından **`app-debug.apk`** dosyasını indirip telefonunuza yükleyebilirsiniz!

---

## Quick Start (All Platforms)

### 1. Install Dependencies
Make sure Python 3 is installed, then run:
```bash
pip install requests
```

### 2. Configure Credentials
Copy `.env.example` to `.env`:
```bash
cp .env.example .env
```
*(On Windows: rename or copy `.env.example` to `.env` using Notepad).*

Fill in your credentials:
```env
IBBWIFI_PHONE=5051234567
IBBWIFI_PASSWORD=your_password
```

---

## Platform Guides

### 🐧 Linux (Arch, CachyOS, Ubuntu, Fedora)

#### Automatic Setup:
Run the setup script inside the project folder:
```bash
chmod +x setup_linux.sh uninstall_linux.sh
./setup_linux.sh
```
This registers a NetworkManager dispatcher hook (`/etc/NetworkManager/dispatcher.d/99-ibbwifi.sh`) that triggers whenever you connect to `ibbWiFi`.

#### How to Disable / Turn Off (Linux):
To remove the automatic background trigger, simply run:
```bash
./uninstall_linux.sh
```
Or manually:
```bash
sudo rm -f /etc/NetworkManager/dispatcher.d/99-ibbwifi.sh
```

---

### 🪟 Windows (10 / 11)

#### 1. Manual One-Click Run:
Simply double-click **`run_windows.bat`** whenever you connect to `ibbWiFi`.

#### 2. Automatic on Wi-Fi Connect (Task Scheduler):
1. Press `Win + R`, type `taskschd.msc` and hit Enter to open **Task Scheduler**.
2. Click **Create Task** on the right panel:
   - **General:** Name it `IBB Wi-Fi Auto Login`. Check *"Run with highest privileges"*.
   - **Triggers:** New -> Begin the task: *"On an event"*.
     - Log: `Microsoft-Windows-WLAN-AutoConfig/Operational`
     - Source: `WLAN-AutoConfig`
     - Event ID: `8001` *(Triggered when Wi-Fi successfully connects)*.
   - **Actions:** New -> Action: *"Start a program"*.
     - Program/script: `python.exe` (or `pythonw.exe` for silent/hidden window).
     - Add arguments: `ibbwifi_login.py`
     - Start in: Full path to your folder (e.g., `C:\Users\username\Desktop\ibbwifi-autologin`).
   - **Conditions:** Uncheck *"Start the task only if the computer is on AC power"*.
3. Click **OK**.

#### How to Disable / Turn Off (Windows):
1. Open **Task Scheduler** (`taskschd.msc`).
2. Find `IBB Wi-Fi Auto Login` in the list.
3. Right-click it and select **Disable** or **Delete**.

---

### 🤖 Android (Termux)

> **Important Mobile Tip:** If your phone fails to reach the login portal while Mobile Data (4G/5G) is on, open **Settings > Wi-Fi > Advanced** and turn off **"Switch to mobile data automatically"** (or "Smart Network Switch" / "Wi-Fi Assistant"). This forces Android to keep traffic on Wi-Fi instead of bypassing the captive portal.

#### 1. Setup in Termux:
Install Termux from [F-Droid](https://f-droid.org/packages/com.termux/). Open Termux and run:
```bash
pkg update -y && pkg install python git -y
git clone https://github.com/YusufTahirKy/ibbwifi-autologin.git
cd ibbwifi-autologin
pip install requests
cp .env.example .env
nano .env  # Enter your phone number and password
```

#### 2. One-Tap Login (Termux:Widget):
Install **Termux:Widget** from F-Droid:
```bash
mkdir -p ~/.shortcuts
echo "python ~/ibbwifi-autologin/ibbwifi_login.py" > ~/.shortcuts/ibb_login.sh
chmod +x ~/.shortcuts/ibb_login.sh
```
Add the Termux Widget to your Android Home Screen. Whenever you connect to `ibbWiFi`, simply tap the shortcut!

#### 3. Automatic on Connect (MacroDroid or Tasker):
1. Install **MacroDroid** (Free on Google Play).
2. Create a new Macro:
   - **Trigger:** *Connectivity > Wi-Fi State Change > Connected to Network* -> Select `ibbWiFi`.
   - **Action:** *Applications > Open App* -> Choose **Termux** (or run Termux task via Termux:Tasker plugin).
3. Save the Macro.

#### How to Disable / Turn Off (Android):
- **MacroDroid / Tasker:** Open the app and toggle the `ibbWiFi` macro/profile to **Off**.
- **Termux:** Simply remove the widget or delete the shortcut file: `rm -f ~/.shortcuts/ibb_login.sh`.

---

## Security

- **Never commit `.env` to GitHub.** Your credentials are saved locally on your device only.
- The repository includes a `.gitignore` specifically ignoring `.env` and `.env.*`.

---

## License

MIT License - feel free to modify and share!
