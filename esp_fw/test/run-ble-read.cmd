@echo off
cd /d "%~dp0.."
where cl >nul 2>nul
if errorlevel 1 (
  echo Run this script from an x64 Native Tools Command Prompt for Visual Studio.
  exit /b 1
)
if not exist test\http_preview mkdir test\http_preview
python test\ble-read.test.py %*
exit /b %errorlevel%
