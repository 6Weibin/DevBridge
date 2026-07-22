@echo off
setlocal

REM Ai DevBridge Windows backend startup script. by AI.Coding
REM Keep the working directory at DevBridge-Server so bundled tools resolve from tools\{os-arch}.
cd /d "%~dp0\.."

set "JAR_FILE=target\devbridge-server-0.1.0-SNAPSHOT.jar"
set "RUNTIME_DIR=target\runtime"
set "RUNTIME_JAR=%RUNTIME_DIR%\devbridge-server-runtime.jar"

if not exist "%JAR_FILE%" (
  echo [Ai DevBridge] Backend jar not found: %JAR_FILE%
  echo [Ai DevBridge] Please run: mvn -DskipTests package
  exit /b 1
)

echo [Ai DevBridge] Starting backend on http://127.0.0.1:8080
if not exist "%RUNTIME_DIR%" mkdir "%RUNTIME_DIR%"
copy /Y "%JAR_FILE%" "%RUNTIME_JAR%" >nul
REM Spring Boot fat jar loads nested dependencies lazily; run a copy so later builds do not corrupt this process.
java -jar "%RUNTIME_JAR%"
