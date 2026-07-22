@echo off
rem DevBridge Windows 初始化构建脚本。
rem 负责补齐编译工具、安装依赖并依次构建全部子工程。
rem by AI.Coding
setlocal EnableExtensions EnableDelayedExpansion

set "ROOT_DIR=%~dp0"
set "FRONT_DIR=%ROOT_DIR%DevBridge-Front"
set "SERVER_DIR=%ROOT_DIR%DevBridge-Server"
set "ELECTRON_DIR=%ROOT_DIR%DevBridge-Electron"
set "CHECK_ONLY=0"

rem 解析参数，仅支持无副作用的环境检查模式。
if /I "%~1"=="--check-only" set "CHECK_ONLY=1"
if not "%~1"=="" if /I not "%~1"=="--check-only" (
  echo [DevBridge Init] 不支持的参数: %~1。可用参数: --check-only
  exit /b 2
)

call :validate_projects || exit /b 1
call :ensure_tools || exit /b 1
call :print_versions || exit /b 1
if "%CHECK_ONLY%"=="1" (
  echo [DevBridge Init] 环境与工程结构检查通过。
  exit /b 0
)

call :build_frontend || exit /b 1
call :build_server || exit /b 1
call :build_electron || exit /b 1
echo [DevBridge Init] 全部工程初始化构建完成。
exit /b 0

rem 校验三个工程的构建描述文件，避免在不完整目录执行安装。
:validate_projects
if not exist "%FRONT_DIR%\package.json" (
  echo [DevBridge Init] 缺少前端工程。
  exit /b 1
)
if not exist "%SERVER_DIR%\pom.xml" (
  echo [DevBridge Init] 缺少后端工程。
  exit /b 1
)
if not exist "%ELECTRON_DIR%\package.json" (
  echo [DevBridge Init] 缺少 Electron 工程。
  exit /b 1
)
exit /b 0

rem 检查工具版本和可执行文件，缺失时调用系统包管理器安装。
:ensure_tools
call :ensure_node || exit /b 1
where pnpm >nul 2>nul || call :install_package pnpm.pnpm pnpm
if errorlevel 1 exit /b 1
call :ensure_java || exit /b 1
where mvn >nul 2>nul || call :install_package Apache.Maven maven maven
if errorlevel 1 exit /b 1
call :refresh_path
where node >nul 2>nul || (echo [DevBridge Init] Node.js 安装后仍不可用，请重启终端。& exit /b 1)
where pnpm >nul 2>nul || (echo [DevBridge Init] pnpm 安装后仍不可用，请重启终端。& exit /b 1)
where javac >nul 2>nul || (echo [DevBridge Init] JDK 安装后仍不可用，请重启终端。& exit /b 1)
where mvn >nul 2>nul || (echo [DevBridge Init] Maven 安装后仍不可用，请重启终端。& exit /b 1)
exit /b 0

rem Node.js 低于 20 时安装当前 LTS，满足 Vite 6 和 Electron 工具链要求。
:ensure_node
set "NODE_MAJOR=0"
for /f "tokens=1 delims=." %%V in ('node -p "process.versions.node" 2^>nul') do set "NODE_MAJOR=%%V"
if !NODE_MAJOR! GEQ 20 exit /b 0
call :install_package OpenJS.NodeJS.LTS nodejs-lts nodejs-lts
exit /b %errorlevel%

rem JDK 低于 17 时安装 Temurin 17，匹配 Spring Boot 工程基线。
:ensure_java
set "JAVA_MAJOR=0"
for /f %%V in ('powershell -NoProfile -Command "$v = (& javac -version 2^>^&1); if ($v -match '([0-9]+)') { $Matches[1] } else { 0 }" 2^>nul') do set "JAVA_MAJOR=%%V"
if !JAVA_MAJOR! GEQ 17 exit /b 0
call :install_package EclipseAdoptium.Temurin.17.JDK temurin17 temurin17
exit /b %errorlevel%

rem 优先使用 Windows 内置 winget，旧环境兼容已安装的 Chocolatey。
:install_package
echo [DevBridge Init] 正在安装 %~1。
where winget >nul 2>nul && winget install --id %~1 --exact --accept-package-agreements --accept-source-agreements
if not errorlevel 1 exit /b 0
where choco >nul 2>nul && choco install %~2 -y
if not errorlevel 1 exit /b 0
echo [DevBridge Init] 无法自动安装 %~1，请先安装 winget 或 Chocolatey。
exit /b 1

rem 合并注册表中的系统与用户 PATH，使本次会话立即识别新安装工具。
:refresh_path
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')"`) do set "PATH=%%P"
exit /b 0

rem 输出最终工具版本，便于定位不同开发机上的环境差异。
:print_versions
for /f "delims=" %%V in ('node --version') do echo [DevBridge Init] Node.js %%V
for /f "delims=" %%V in ('npm --version') do echo [DevBridge Init] npm %%V
for /f "delims=" %%V in ('pnpm --version') do echo [DevBridge Init] pnpm %%V
javac -version
for /f "delims=" %%V in ('mvn --version ^| findstr /B "Apache Maven"') do echo [DevBridge Init] %%V
exit /b 0

rem 按 pnpm 锁文件安装依赖并构建 Vite 前端。
:build_frontend
echo [DevBridge Init] 安装并构建 DevBridge-Front。
call pnpm --dir "%FRONT_DIR%" install --frozen-lockfile || exit /b 1
call pnpm --dir "%FRONT_DIR%" run build || exit /b 1
exit /b 0

rem Maven package 默认执行测试，失败时阻止后续 Electron 打包。
:build_server
echo [DevBridge Init] 构建并测试 DevBridge-Server。
call mvn -f "%SERVER_DIR%\pom.xml" package || exit /b 1
exit /b 0

rem 安装 Electron 锁定依赖，准备资源后生成当前 Windows 的目录包。
:build_electron
echo [DevBridge Init] 安装并构建 DevBridge-Electron。
call npm --prefix "%ELECTRON_DIR%" ci || exit /b 1
call npm --prefix "%ELECTRON_DIR%" run prepare:resources || exit /b 1
rem electron-builder 以当前工作目录查找 package.json，因此显式切换目录。
pushd "%ELECTRON_DIR%" || exit /b 1
call npm exec -- electron-builder --dir
set "BUILD_EXIT_CODE=!errorlevel!"
popd
exit /b !BUILD_EXIT_CODE!
