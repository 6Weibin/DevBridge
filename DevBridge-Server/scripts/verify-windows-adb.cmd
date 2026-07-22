@echo off
setlocal

REM Ai DevBridge Windows ADB verification script. by AI.Coding
REM Use the bundled adb.exe directly to avoid an old adb from PATH taking precedence.
cd /d "%~dp0\.."

set "ADB_EXE=tools\windows-x64\platform-tools\adb.exe"

if not exist "%ADB_EXE%" (
  echo [Ai DevBridge] Bundled Windows adb.exe not found: %ADB_EXE%
  exit /b 1
)

echo [Ai DevBridge] adb version
"%ADB_EXE%" version
if errorlevel 1 exit /b 1

echo.
echo [Ai DevBridge] restart adb server
"%ADB_EXE%" kill-server
"%ADB_EXE%" start-server
if errorlevel 1 exit /b 1

echo.
echo [Ai DevBridge] connected Android devices
"%ADB_EXE%" devices -l
