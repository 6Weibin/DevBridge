# 工具集成验证记录

## 1. 结论

- adb：已从 Google 官方 Android SDK Platform-Tools 下载并内置到项目；macOS 真实命令验证通过，Windows 工具包已完成下载、版本和路径解析校验。
- hdc：未完成内置。当前未找到无需登录/无需 SDK 管理器的官方 macOS 独立二进制稳定直链；本机也未安装 `hdc`/`hdc_std`。
- idevice 工具：已通过 Homebrew 安装 `libimobiledevice`，真实命令验证通过；它是 libimobiledevice 开源工具，不是 Apple 官方工具，也未内置到项目目录。

## 2. 工具来源

| 工具 | 来源 | 项目内置 | 说明 |
|------|------|----------|------|
| adb | Google Android SDK Platform-Tools：`https://developer.android.com/tools/releases/platform-tools` | 是 | macOS 下载包：`https://dl.google.com/android/repository/platform-tools-latest-darwin.zip`；Windows 下载包：`https://dl.google.com/android/repository/platform-tools-latest-windows.zip` |
| hdc / hdc_std | 华为 DevEco Studio / HarmonyOS SDK / OpenHarmony SDK | 否 | 未找到可自动下载的官方 macOS 独立二进制直链 |
| idevice_id / idevicesyslog / idevicebackup2 | libimobiledevice：`https://github.com/libimobiledevice/libimobiledevice` | 否 | 当前通过 `brew install libimobiledevice` 安装到 `/opt/homebrew/bin` |

## 3. 文件位置

| 文件/目录 | 说明 |
|-----------|------|
| `DevBridge-Server/tools/downloads/platform-tools-latest-darwin.zip` | Google 官方 platform-tools 下载包 |
| `DevBridge-Server/tools/downloads/platform-tools-latest-windows.zip` | Google 官方 Windows platform-tools 下载包，SHA-256：`4fe305812db074cea32903a489d061eb4454cbc90a49e8fea677f4b7af764918` |
| `DevBridge-Server/tools/darwin-arm64/platform-tools/adb` | 项目内置 adb |
| `DevBridge-Server/tools/windows-x64/platform-tools/adb.exe` | 项目内置 Windows adb |
| `DevBridge-Server/tools/windows-x64/platform-tools/AdbWinApi.dll` | Windows adb 运行依赖 |
| `DevBridge-Server/tools/windows-x64/platform-tools/AdbWinUsbApi.dll` | Windows adb USB 访问依赖 |
| `DevBridge-Server/tools/darwin-arm64/platform-tools/fastboot` | platform-tools 随包工具 |
| `DevBridge-Server/tools/TOOLS.md` | 工具来源与限制说明 |

## 4. 命令验证

| 命令 | 结果 | 关键输出 |
|------|------|----------|
| `DevBridge-Server/tools/darwin-arm64/platform-tools/adb version` | 通过 | `Android Debug Bridge version 1.0.41`, `Version 37.0.0-14910828` |
| `DevBridge-Server/tools/darwin-arm64/platform-tools/adb devices` | 通过 | `List of devices attached`，当前无 Android 真机连接 |
| `cat DevBridge-Server/tools/windows-x64/platform-tools/source.properties` | 通过 | `Pkg.Revision=37.0.0` |
| `shasum -a 256 DevBridge-Server/tools/downloads/platform-tools-latest-windows.zip` | 通过 | `4fe305812db074cea32903a489d061eb4454cbc90a49e8fea677f4b7af764918` |
| `idevice_id -h` | 通过 | 显示 `Usage: idevice_id [OPTIONS] [UDID]` |
| `idevice_id -l` | 部分通过 | 工具可执行，但返回 `ERROR: Unable to retrieve device list!`，当前未验证到 iOS 设备 |
| `idevicebackup2 --help` | 通过 | 显示 backup/restore/list 等命令帮助 |
| `command -v hdc` / `command -v hdc_std` | 不通过 | 本机未安装 |

## 5. 后端 API 验证

启动后端后请求 `GET /api/tools/status`，后端真实识别到以下工具：

| 工具 | available | path | version 摘要 |
|------|-----------|------|--------------|
| adb | true | `/Users/puweibin/Documents/MyDocuments/Projects/DevBridge/DevBridge-Server/tools/darwin-arm64/platform-tools/adb` | `Android Debug Bridge version 1.0.41` |
| hdc | false | 空 | `tool not found` |
| idevice_id | true | `/opt/homebrew/bin/idevice_id` | `Usage: idevice_id [OPTIONS] [UDID]` |
| idevicesyslog | true | `/opt/homebrew/bin/idevicesyslog` | `Usage: idevicesyslog [OPTIONS]` |
| idevicebackup2 | true | `/opt/homebrew/bin/idevicebackup2` | `Usage: idevicebackup2 [OPTIONS] CMD [CMDOPTIONS] DIRECTORY` |

请求 `GET /api/devices` 返回 `[]`。这说明真实工具链已接入，但当前没有可枚举的 Android/iOS/HarmonyOS 真机。

## 5.1 Windows ADB 集成校验

- 已解压官方 Windows platform-tools 到 `DevBridge-Server/tools/windows-x64/platform-tools`。
- 已保留官方压缩包到 `DevBridge-Server/tools/downloads/platform-tools-latest-windows.zip`。
- 已新增 `ExecutableLocatorTest`，模拟 `os.name=Windows 11`、`os.arch=amd64`，验证后端优先解析 `tools/windows-x64/platform-tools/adb.exe`。
- 已新增 `scripts/verify-windows-adb.cmd`，用于在 Windows 机器直接执行内置 `adb.exe version`、`adb.exe devices -l`。
- 已新增 `scripts/start-server.cmd`，用于在 Windows 机器从已打包 jar 启动本机后端。
- 当前开发机是 macOS，无法直接执行 Windows `.exe`；需要在 Windows 机器上补充 `adb.exe version` 和 `adb.exe devices` 真机验证。

## 6. 服务清理

- 后端 Spring Boot 服务已停止。
- 前端 Vite 服务未运行。
- adb daemon 已执行 `adb kill-server` 停止。

## 7. 后续建议

1. 连接一台已开启 USB 调试的 Android 设备，再次验证 `GET /api/devices`。
2. 安装 DevEco Studio 或官方 HarmonyOS/OpenHarmony SDK 后，将 `hdc` 放入 `DevBridge-Server/tools/darwin-arm64/hdc`，再验证 hdc。
3. 连接并信任一台 iPhone，确认 macOS 可通过 libimobiledevice 获取设备列表。
4. 在 Windows x64 机器启动后端，访问 `GET /api/tools/status`，确认 adb 路径显示为 `tools/windows-x64/platform-tools/adb.exe`。

补充：已检查华为下载中心页面和其前端公开脚本，页面通过下载中心接口动态获取工具列表；直接探测相关 code server 接口未获得可用公开下载数据。因此本轮没有把非官方来源的 hdc 二进制放入项目。
