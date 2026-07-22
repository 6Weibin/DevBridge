# Ai DevBridge 内置/外部设备工具说明

## Android Platform-Tools

- 状态：已内置
- 官方来源：https://developer.android.com/tools/releases/platform-tools
- macOS 下载地址：https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
- macOS 安装目录：`tools/darwin-arm64/platform-tools`
- Windows 下载地址：https://dl.google.com/android/repository/platform-tools-latest-windows.zip
- Windows 安装目录：`tools/windows-x64/platform-tools`
- Windows 工具版本：`Pkg.Revision=37.0.0`
- Windows 压缩包 SHA-256：`4fe305812db074cea32903a489d061eb4454cbc90a49e8fea677f4b7af764918`
- macOS 已验证命令：
  - `tools/darwin-arm64/platform-tools/adb version`
  - `tools/darwin-arm64/platform-tools/adb devices`
- Windows 已完成集成校验：
  - `tools/windows-x64/platform-tools/adb.exe`
  - `tools/windows-x64/platform-tools/AdbWinApi.dll`
  - `tools/windows-x64/platform-tools/AdbWinUsbApi.dll`
  - 后端会在 Windows x64 环境优先解析 `tools/windows-x64/platform-tools/adb.exe`

## HarmonyOS hdc

- 状态：未内置
- 原因：华为下载中心页面未提供无需登录/无需 SDK 管理器的独立 macOS `hdc` 二进制稳定直链；`hdc` 通常随 DevEco Studio 或 HarmonyOS/OpenHarmony SDK 分发。
- 当前项目处理方式：后端会继续从 `tools/{os-arch}`、显式配置和 PATH 查找 `hdc`/`hdc_std`。拿到官方 SDK 后，把 `hdc` 放入 `tools/darwin-arm64/hdc` 即可被识别。

## iOS libimobiledevice tools

- 状态：未内置，已在当前 macOS 通过 Homebrew 安装用于验证
- 项目来源：https://github.com/libimobiledevice/libimobiledevice
- 安装方式：`brew install libimobiledevice`
- 已验证命令：
  - `idevice_id -h`
  - `idevice_id -l`

说明：`idevice_id`、`idevicesyslog`、`idevicebackup2` 是 libimobiledevice 开源工具，并非 Apple 官方命令。Apple 官方设备工具通常随 Xcode / Command Line Tools 分发，不适合随本项目直接再分发。
