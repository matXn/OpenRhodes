@echo off
cd /d "%~dp0.."
where cl >nul 2>nul
if errorlevel 1 (
  echo Run this script from an x64 Native Tools Command Prompt for Visual Studio.
  exit /b 1
)
if not exist test\http_preview mkdir test\http_preview
cl /nologo /EHsc /std:c++17 /utf-8 /Fe:test\http_preview\radio-priority-test.exe /Fo:test\http_preview\radio-priority-test.obj test\wifi-setup.test.cpp /link /MANIFEST:EMBED "/MANIFESTUAC:level='asInvoker' uiAccess='false'"
if errorlevel 1 exit /b 1
test\http_preview\radio-priority-test.exe
exit /b %errorlevel%
