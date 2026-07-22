# ADB 设备枚举一直等待连接排查记录

## 排查步骤

- [x] 调用 `GET /api/devices` 复现，确认前端拿到空设备列表。
- [x] 调用 `GET /api/tools/status`，确认后端可定位内置 ADB。
- [x] 调用 `GET /api/diagnostics/adb-devices`，确认 ADB 返回 `protocol fault` 和 `Connection reset by peer`。
- [x] 查看 ADB 日志，发现 daemon 握手阶段存在 `Broken pipe`。
- [x] 阅读 `DeviceService` 和 `CommandRunner`，确认设备枚举复用了全局 3 秒命令超时。
- [x] 修复 ADB 枚举超时和 daemon 异常恢复逻辑。
- [x] 重新启动后端并验证 `GET /api/diagnostics/adb-devices`、`GET /api/devices`。
- [x] 刷新内置浏览器页面，确认页面不再显示等待设备连接。

## 假设与验证

| 假设 | 验证结果 |
| --- | --- |
| 前端轮询或状态渲染错误导致设备不显示 | 否。`/api/devices` 返回空数组，问题在后端设备枚举链路。 |
| ADB 工具缺失或路径错误 | 否。`/api/tools/status` 返回内置 ADB 可用。 |
| 手机未接入或未授权 | 否。修复后 ADB 输出 `66J5T19411001963 device`，设备状态正常。 |
| ADB daemon 握手被过短超时打断 | 是。诊断接口出现协议错误，延长 ADB 专用超时并恢复 daemon 后问题消失。 |

## 根因定位链

用户现象是页面停留在“等待设备连接”。前端依赖 `/api/devices`，该接口返回空数组。进一步调用 ADB 诊断接口，后端执行内置 ADB 时返回 `adb: failed to check server version: protocol fault ... Connection reset by peer`。结合 ADB 日志中的 `Broken pipe`，可以判断 ADB client 与 daemon 的启动/握手被中断。

代码层面，`DeviceService` 设备枚举调用 `CommandRunner.run(command)`，该路径使用全局 `devbridge.command-timeout: 3s`。macOS 下首次启动 ADB daemon 或 USB 握手可能超过 3 秒，后端提前终止 ADB client 后，会留下短时间不可用的 daemon 协议状态。前端持续轮询设备列表会放大这个问题，导致设备一直不能稳定枚举。

## 修复方案

在 `DeviceService` 中对 ADB 枚举使用专用超时：

- `adb devices` 使用 12 秒超时，覆盖 daemon 启动和 USB 握手时间。
- `adb kill-server` / `adb start-server` 使用 15 秒超时。
- 仅在超时、`protocol fault`、`connection reset`、`cannot connect to daemon`、`failed to check server version` 等 daemon 异常时重置 ADB server。
- 对普通 `unauthorized`、`offline` 等设备状态不重置 daemon，避免误伤用户正在处理的授权状态。
- 添加 `DeviceServiceTest` 覆盖 ADB 协议错误后的 `devices -> kill-server -> start-server -> devices` 恢复路径。

## 验证结果

- `mvn -Dtest=DeviceServiceTest test` 通过，1 个测试，0 失败。
- `GET /api/runtime/environment` 返回 `appName=Ai DevBridge`、`appVersion=V2026.7.0081`。
- `GET /api/diagnostics/adb-devices` 返回 exitCode `0`，stdout 包含 `66J5T19411001963 device`。
- `GET /api/devices` 返回 `android:66J5T19411001963`，状态 `connected`。
- 内置浏览器刷新后页面不再显示“等待设备连接”，设备列表和设备信息区域已显示该 Android 设备。
