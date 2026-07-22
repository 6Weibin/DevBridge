# ADB Server 权限导致 Android 设备列表为空排查记录

## 问题现象

- 用户通过 USB 连接 Android 设备后，访问 `/api/devices` 无法读取到设备。
- 后端服务正常运行，前端页面可访问，因此优先排查 ADB 调用链路。

## 排查步骤

- 直接执行工程内置 ADB：
  - 命令：`DevBridge-Server/tools/darwin-arm64/platform-tools/adb devices -l`
  - 结果：ADB server 启动失败，提示 `could not install *smartsocket* listener: Operation not permitted`
- 查看 ADB 日志：
  - 文件：`/var/folders/2b/xnrd3wzd1fj17hlsnj9r9tww0000gn/T/adb.501.log`
  - 结果：多次出现 `Operation not permitted`、`Address already in use`、`Broken pipe`
- 使用非沙箱权限执行工程内置 ADB：
  - 结果：ADB server 成功启动，并识别到设备 `EMH0223511000196`
- 复查后端诊断接口：
  - `/api/diagnostics/adb-devices` 返回设备原始列表
  - `/api/devices` 返回 `android:EMH0223511000196`

## 假设与验证

| 假设 | 验证结果 | 结论 |
| --- | --- | --- |
| 手机没有开启 USB 调试或未授权 | 非沙箱 ADB 能返回 `device` 状态 | 排除 |
| 工程内置 ADB 路径错误 | ADB 日志显示已执行工程内置路径 | 排除 |
| 后端解析逻辑错误 | ADB server 启动后 `/api/devices` 能返回设备 | 排除 |
| ADB daemon 无法在当前运行权限下启动 | 沙箱内启动失败，非沙箱启动成功 | 确认 |

## 根因链路

1. `/api/devices` 调用后端 `AndroidDeviceService`。
2. 后端通过工程内置 `adb devices -l` 获取设备列表。
3. 当本机 ADB server 尚未运行时，ADB 会尝试监听 `127.0.0.1:5037`。
4. 当前服务由 Codex 沙箱环境启动，子进程启动 ADB server 监听端口被系统拒绝。
5. ADB daemon 启动失败，后端拿不到设备列表，因此 `/api/devices` 返回空数组。
6. 在非沙箱权限下先启动 ADB server 后，后端可复用该 daemon，接口恢复正常。

## 当前结论

本次问题不是 USB 线、手机授权、前端页面或后端解析逻辑导致，而是 ADB server 首次启动权限问题。当前已通过非沙箱权限启动工程内置 ADB server，后端接口已能读取到 Android 设备。

## 后续建议

- 本地日常测试时，优先从普通终端启动后端服务，避免由受限沙箱环境启动。
- 如果再次出现空列表，先执行工程内置 `adb devices -l`，确认 ADB server 是否能正常启动。
- 可在后端诊断接口中增强错误提示，将 `Operation not permitted` 显示为明确的 “ADB server 启动权限不足”。
