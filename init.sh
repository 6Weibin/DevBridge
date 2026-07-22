#!/usr/bin/env bash
# DevBridge macOS 初始化构建脚本。
# 负责补齐编译工具、安装依赖并依次构建全部子工程。
#
# by AI.Coding

set -Eeuo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly FRONT_DIR="${ROOT_DIR}/DevBridge-Front"
readonly SERVER_DIR="${ROOT_DIR}/DevBridge-Server"
readonly ELECTRON_DIR="${ROOT_DIR}/DevBridge-Electron"
CHECK_ONLY=false

# 输出带统一前缀的执行信息，便于定位初始化阶段。
log() {
  printf '[DevBridge Init] %s\n' "$*"
}

# 在命令失败时输出失败行号，避免长构建日志掩盖根因。
on_error() {
  local exit_code=$?
  log "执行失败（行号: $1，退出码: ${exit_code}）。"
  exit "${exit_code}"
}
trap 'on_error ${LINENO}' ERR

# 解析脚本参数；当前只开放无副作用的环境检查模式。
parse_args() {
  if [[ ${1:-} == "--check-only" ]]; then
    CHECK_ONLY=true
  elif [[ $# -gt 0 ]]; then
    log "不支持的参数: $1。可用参数: --check-only"
    exit 2
  fi
}

# 校验工程目录，防止脚本在文件不完整的位置继续安装或构建。
validate_projects() {
  [[ -f "${FRONT_DIR}/package.json" ]] || { log "缺少前端工程。"; exit 1; }
  [[ -f "${SERVER_DIR}/pom.xml" ]] || { log "缺少后端工程。"; exit 1; }
  [[ -f "${ELECTRON_DIR}/package.json" ]] || { log "缺少 Electron 工程。"; exit 1; }
}

# 安装 Homebrew；macOS 使用统一包管理器可避免散落的手工 PATH 配置。
ensure_homebrew() {
  if command -v brew >/dev/null 2>&1; then
    return
  fi
  log "未检测到 Homebrew，正在安装。"
  NONINTERACTIVE=1 /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  if [[ -x /opt/homebrew/bin/brew ]]; then
    eval "$(/opt/homebrew/bin/brew shellenv)"
  elif [[ -x /usr/local/bin/brew ]]; then
    eval "$(/usr/local/bin/brew shellenv)"
  fi
}

# 比较主版本号，低于工程基线时由 Homebrew 安装受支持版本。
version_at_least() {
  local actual=$1
  local required=$2
  [[ ${actual} =~ ^[0-9]+$ ]] && (( actual >= required ))
}

# 确保 Node.js、pnpm、JDK 和 Maven 满足项目构建要求。
ensure_tools() {
  ensure_homebrew
  local node_major=""
  local java_major=""
  node_major="$(node --version 2>/dev/null | sed -E 's/^v([0-9]+).*/\1/' || true)"
  java_major="$(javac -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/' || true)"
  if ! version_at_least "${node_major}" 20; then
    log "正在安装 Node.js 20。"
    brew install node@20
    export PATH="$(brew --prefix node@20)/bin:${PATH}"
  fi
  if ! command -v pnpm >/dev/null 2>&1; then
    log "正在安装 pnpm。"
    brew install pnpm
  fi
  if ! version_at_least "${java_major}" 17; then
    log "正在安装 OpenJDK 17。"
    brew install openjdk@17
    export JAVA_HOME="$(brew --prefix openjdk@17)"
    export PATH="${JAVA_HOME}/bin:${PATH}"
  fi
  if ! command -v mvn >/dev/null 2>&1; then
    log "正在安装 Maven。"
    brew install maven
  fi
}

# 输出实际使用的工具版本，便于复现构建环境。
print_versions() {
  log "Node.js $(node --version), npm $(npm --version), pnpm $(pnpm --version)"
  log "$(javac -version 2>&1), $(mvn --version | sed -n '1p')"
}

# 恢复锁定依赖并构建 Vite 前端。
build_frontend() {
  log "安装并构建 DevBridge-Front。"
  pnpm --dir "${FRONT_DIR}" install --frozen-lockfile
  pnpm --dir "${FRONT_DIR}" run build
}

# Maven package 默认执行测试，确保初始化产物具备基本质量保证。
build_server() {
  log "构建并测试 DevBridge-Server。"
  mvn -f "${SERVER_DIR}/pom.xml" package
}

# 使用 npm 锁文件恢复 Electron 依赖，并基于已构建的上游产物生成目录包。
build_electron() {
  log "安装并构建 DevBridge-Electron。"
  npm --prefix "${ELECTRON_DIR}" ci
  npm --prefix "${ELECTRON_DIR}" run prepare:resources
  # electron-builder 以当前工作目录查找 package.json，不能只依赖 npm --prefix。
  (cd "${ELECTRON_DIR}" && npm exec -- electron-builder --dir)
}

# 初始化入口按依赖顺序执行，检查模式不会生成构建产物。
main() {
  parse_args "$@"
  [[ $(uname -s) == "Darwin" ]] || { log "init.sh 当前仅支持 macOS，请在 Windows 使用 init.cmd。"; exit 1; }
  validate_projects
  ensure_tools
  print_versions
  if [[ ${CHECK_ONLY} == true ]]; then
    log "环境与工程结构检查通过。"
    return
  fi
  build_frontend
  build_server
  build_electron
  log "全部工程初始化构建完成。"
}

main "$@"
