# Android 应用卸载失败继续排查

## 排查步骤

- [x] 复查前端卸载调用，确认请求为 `DELETE /api/devices/{platform}/{serial}/apps/{packageName}`。
- [x] 复查后端 `AppController`、`AndroidDeviceService` 和统一异常处理。
- [x] 使用 `curl` 直接调用当前设备卸载接口，确认后端能返回 JSON，不是接口路由缺失。
- [x] 对不存在包名执行安全测试，定位设备侧原始返回为 `Failure [DELETE_FAILED_INTERNAL_ERROR]`。
- [x] 验证系统包卸载边界，确认普通 adb 权限不应允许直接卸载系统应用。
- [x] 增加卸载前包存在性校验、系统包保护、前端错误 detail 展示和系统应用卸载入口置灰。
- [x] 执行 `npm run build`、`mvn test`、`mvn package -DskipTests` 并重启服务。

## 假设与验证

| 假设 | 验证结果 |
| --- | --- |
| 后端接口不存在或 CORS 导致 `Failed to fetch` | `curl` 和 OPTIONS 预检均正常，接口存在且能返回 JSON。 |
| `pm uninstall` 命令拼接错误 | 后端使用参数数组，不存在 shell 拼接问题。 |
| 设备侧返回被前端隐藏 | 成立。前端只展示 `message`，没有展示 `detail`，用户无法看到 `DELETE_FAILED_INTERNAL_ERROR`。 |
| 不存在包会被设备返回内部错误 | 成立。当前设备对不存在包返回 `Failure [DELETE_FAILED_INTERNAL_ERROR]`，需要后端提前转换为 `APP_NOT_FOUND`。 |
| 系统应用被允许进入卸载流程 | 成立。前端右键菜单未区分系统应用，后端也未做系统包保护。 |

## 根因链路

1. 应用列表包含系统应用和用户应用。
2. 前端对所有应用都展示“卸载应用”，未阻止系统应用。
3. 后端收到卸载请求后直接执行 `adb shell pm uninstall {packageName}`。
4. 对不可卸载、未安装或系统保护包，设备可能返回 `Failure [DELETE_FAILED_INTERNAL_ERROR]` 这类低可读错误。
5. 前端错误解析只展示统一 `message`，没有展示设备侧 `detail`；如果浏览器侧出现网络异常，也没有给出本机服务连接提示。

## 修复方案

- 后端卸载前先用 `cmd package list packages {packageName}` 精确确认包存在。
- 后端再用 `cmd package list packages -s {packageName}` 判断系统包，系统包返回 `APP_UNINSTALL_FORBIDDEN`，不执行卸载命令。
- 前端 `apiError` 将后端 `detail` 拼接到错误摘要中，后续真实用户应用失败时可以直接看到设备侧原因。
- 前端对系统应用右键菜单中的“卸载应用”置灰，并在二次确认入口兜底阻止。

## 验证结果

- `npm run build`：通过。
- `mvn test`：38 个测试全部通过。
- `mvn package -DskipTests`：通过。
- 服务已重启，新 PID 为 `68833`。
- `DELETE /apps/com.devbridge.nonexistent.probe` 返回 `APP_NOT_FOUND`。
- `DELETE /apps/com.android.settings` 返回 `APP_UNINSTALL_FORBIDDEN`。
