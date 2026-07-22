#!/usr/bin/env bash
#
# Ai DevBridge macOS/Linux backend startup script.
# by AI.Coding

set -euo pipefail

cd "$(dirname "$0")/.."

JAR_FILE="target/devbridge-server-0.1.0-SNAPSHOT.jar"
RUNTIME_DIR="target/runtime"
RUNTIME_JAR="${RUNTIME_DIR}/devbridge-server-runtime.jar"

if [[ ! -f "${JAR_FILE}" ]]; then
  echo "[Ai DevBridge] Backend jar not found: ${JAR_FILE}"
  echo "[Ai DevBridge] Please run: mvn -DskipTests package"
  exit 1
fi

mkdir -p "${RUNTIME_DIR}"
# Spring Boot fat jar loads nested dependencies lazily; run a copy so later builds do not corrupt this process.
cp "${JAR_FILE}" "${RUNTIME_JAR}"

echo "[Ai DevBridge] Starting backend on http://127.0.0.1:8080"
exec java -jar "${RUNTIME_JAR}"
