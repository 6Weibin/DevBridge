# Ai DevBridge Server

本工程是 Ai DevBridge 本机 USB 设备管理后端，默认只监听 `127.0.0.1:8080`。打包后会同时提供 H5 页面和 REST/SSE API。

## Windows 快速验证

1. 确认已安装 JDK 17 或更高版本，并且 `java -version` 可用。
2. 在 `DevBridge-Server` 目录构建后端：

```bat
mvn -DskipTests package
```

3. 验证项目内置 Windows ADB：

```bat
scripts\verify-windows-adb.cmd
```

4. 启动后端：

```bat
scripts\start-server.cmd
```

5. 访问接口：

```text
http://127.0.0.1:8080/
http://127.0.0.1:8080/api/runtime/environment
http://127.0.0.1:8080/api/tools/status
http://127.0.0.1:8080/api/devices
```

## Android MVP 接口

以下接口仅支持已授权、已连接的 Android 设备：

```text
GET  /api/devices/{platform}/{serial}/detail
GET  /api/devices/{platform}/{serial}/files?path=/sdcard
GET  /api/devices/{platform}/{serial}/files/download?path=/sdcard/file.txt
GET  /api/devices/{platform}/{serial}/logs/stream
POST /api/logs/sessions/{sessionId}/stop
GET  /api/devices/{platform}/{serial}/logs/export
```

文件浏览和下载仅允许访问：

```text
/sdcard
/storage/emulated/0
```

越界路径会返回 `REMOTE_PATH_FORBIDDEN`，并且不会执行设备命令。

## Windows Android 设备要求

- 手机开启开发者选项和 USB 调试。
- 手机连接电脑后，在手机端确认 RSA 授权。
- Windows 能正确识别 USB 设备；部分品牌手机需要安装厂商 USB 驱动。
- `scripts\verify-windows-adb.cmd` 能看到设备序列号后，后端 `/api/devices` 才能枚举到 Android 设备。

## 内置工具目录

后端会按当前系统自动优先查找项目内置工具：

```text
tools\windows-x64\platform-tools\adb.exe
tools/darwin-arm64/platform-tools/adb
```

当前运行平台可通过 `/api/runtime/environment` 查看。Windows x64 机器应返回：

```json
{
  "toolDirectoryName": "windows-x64"
}
```

## 说明

`tools/windows-x64/platform-tools` 来自 Google 官方 Android SDK Platform-Tools。ADB 在 Windows 上依赖同目录下的 `AdbWinApi.dll` 和 `AdbWinUsbApi.dll`，不要只复制 `adb.exe`。

`hdc` 和 `idevice` 工具未内置，后端会继续从显式配置和 PATH 查找。工具缺失时，相关功能会在前端降级显示。
