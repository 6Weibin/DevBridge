# 排查记录：Android 手机已连接但 `/api/devices` 返回空数组

## 1. 排查步骤

- [x] 验证真实 adb 输出：执行项目内置 `adb devices -l`
- [x] 对比后端接口：请求 `/api/devices` 和 `/api/tools/status`
- [x] 阅读后端设备枚举链路：`DeviceService`、`CommandRunner`、`DeviceOutputParser`
- [x] 增加受控诊断接口：`/api/diagnostics/adb-devices`
- [x] 增加 adb 枚举失败后的 `start-server` 重试
- [x] 重新构建并验证 `/api/devices`

## 2. 假设与验证结果

| 假设 | 验证方式 | 结果 |
|------|----------|------|
| 手机未被 adb 识别 | `adb devices -l` | 不成立，adb 返回 `EMH0223511000196 device` |
| 后端未识别内置 adb | `/api/tools/status` | 不成立，后端识别到项目内置 adb |
| adb 输出解析失败 | 对比 `adb devices` 输出和 `DeviceOutputParser` | 不成立，解析格式匹配 |
| 后端枚举时 adb daemon 初始化存在时序/权限问题 | 增加诊断接口并重启验证 | 成立。显式诊断和重试后 `/api/devices` 可返回设备 |

## 3. 根因定位链

用户通过 USB 连接 Android 手机后，浏览器访问 `/api/devices` 得到 `[]`。直接执行项目内置 adb 可以看到：

```text
EMH0223511000196       device usb:0-1 product:NAM-AL00 model:NAM_AL00 device:HWNAM transport_id:1
```

这说明设备授权和 adb 本身可用。后端当时对设备枚举命令失败只返回空列表，没有暴露 stderr/exitCode，导致 adb daemon 初始化失败、权限问题或时序问题都被表现成“无设备”。因此需要补充诊断能力，并在 adb 首次枚举失败时显式 `adb start-server` 后重试一次。

## 4. 修复方案

- 新增 `CommandDiagnostic` 和 `/api/diagnostics/adb-devices`，用于返回后端进程真实执行的 adb 命令、退出码、stdout、stderr 和超时状态。
- 修改 `DeviceService`：当 adb 枚举失败时，先执行 `adb start-server`，再重试一次 `adb devices`。
- 保留失败日志：后续 hdc/idevice 或 adb 真失败时，后端日志会记录 tool、exitCode、timedOut、stderr。

## 5. 验证结果

`GET /api/diagnostics/adb-devices` 返回：

```json
{
  "exitCode": 0,
  "stdout": [
    "List of devices attached",
    "EMH0223511000196       device usb:0-1 product:NAM-AL00 model:NAM_AL00 device:HWNAM transport_id:1",
    ""
  ],
  "stderr": [],
  "timedOut": false
}
```

`GET /api/devices` 返回：

```json
[
  {
    "id": "android:EMH0223511000196",
    "serial": "EMH0223511000196",
    "model": "Android Device",
    "osVersion": "",
    "platform": "android",
    "status": "connected"
  }
]
```

## 6. 后续建议

1. 下一步补充 Android 设备详情接口，通过 `adb shell getprop` 获取品牌、型号和系统版本。
2. `/api/devices` 当前只返回最小设备信息，前端设备详情里的硬件信息仍会使用默认/占位字段。
3. hdc 仍未安装，HarmonyOS 设备枚举需要拿到官方 hdc 后继续验证。
