@echo off
REM Windows one-click runner for IBB Wi-Fi Auto-Login
cd /d "%~dp0"

echo [*] Starting IBB Wi-Fi Auto-Login...
python ibbwifi_login.py

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [-] Login encountered an issue. Check your .env file or network.
    pause
) else (
    echo.
    echo [+] Success! Closing in 3 seconds...
    timeout /t 3 >nul
)
