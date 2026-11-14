@echo off
setlocal
cd /d "%~dp0"
set "FG_SINGLE_FAMILY=1"
if not defined FG_SETUP_TOKEN set "FG_SETUP_TOKEN=LOCAL-DEBUG-SETUP-KEY-CHANGE-ME-12345678"
echo First-family local setup key: %FG_SETUP_TOKEN%
if exist "%~dp0local-alert-settings.cmd" call "%~dp0local-alert-settings.cmd"
echo FamilyGuard local testing (HTTP, private Wi-Fi only)
if not defined FG_ALERT_URL echo Warning: external emergency push not configured; parent must open app to see SOS/fence alerts.
echo Do not expose port 8088 to the Internet.
if not exist "%~dp0familyguard-server.exe" (
  echo Missing Windows server binary.
  exit /b 1
)
"%~dp0familyguard-server.exe" -listen 0.0.0.0:8088 -data "%~dp0local-test-data"
if errorlevel 1 (
  echo Server failed to start. Port 8088 may already be in use.
  exit /b 1
)
