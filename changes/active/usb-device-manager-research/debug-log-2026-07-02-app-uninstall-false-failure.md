# Android 应用卸载成功但提示失败

## 排查步骤

- [x] 复查后端卸载流程：`AppController` → `AndroidDeviceService.uninstallApp`。
- [x] 复查命令结果模型：`CommandResult` 区分 exitCode、stdout、stderr、timeout。
- [x] 定位成功判定：旧逻辑要求 `pm uninstall` 的 stdout 必须包含 `Success`。
- [x] 根据用户反馈确认现象：前端提示失败，但设备侧应用已被卸载。
- [x] 修改卸载成功判定：退出码成功且无明确失败输出时，如果卸载后包列表已查不到该包，则按成功处理。
- [x] 补充单元测试覆盖静默成功和退出码 0 但输出 Failure 两类边界。

## 假设与验证

| 假设 | 验证结果 |
| --- | --- |
| 前端状态更新错误导致提示失败 | 低概率。前端只根据后端 HTTP 状态判断成功/失败。 |
| 后端把实际成功误判为失败 | 成立。旧逻辑只接受 stdout 中的 `Success`，没有考虑输出为空、输出延迟或结果写入 stderr。 |
| 直接放宽为 exitCode=0 即成功 | 不可取。已有设备可能 exitCode=0 但输出 `Failure [...]`。 |
| 用卸载后包是否仍存在兜底确认 | 成立。能避免静默成功误报，也不会把明确 Failure 误判为成功。 |

## 根因链路

1. 用户点击卸载后，设备实际完成卸载。
2. `pm uninstall` 返回 exitCode=0，但输出不稳定，可能没有被当前后端读取到 `Success`。
3. 后端旧逻辑 `!result.successful() || !uninstallSucceeded(result.stdout())` 要求 stdout 必须有 `Success`。
4. stdout 缺少 `Success` 时后端返回 `APP_UNINSTALL_FAILED`。
5. 前端收到失败响应，提示“卸载失败”，但应用已经从设备卸载。

## 修复方案

- 保留明确失败输出优先级：只要 stdout/stderr 中出现 `Failure`、`Error`、`failed`，直接判失败。
- 如果存在明确 `Success`，判成功。
- 如果退出码成功但无明确输出，二次查询包列表；包不存在则判成功，包仍存在则判失败。
- 合并 stdout/stderr 做结果判断，避免设备把结果写到 stderr 时丢失判定依据。

## 验证结果

- `mvn test`：40 个测试全部通过。
- `mvn package -DskipTests`：通过。
- 服务已重启，新 PID：`22921`。
- `http://127.0.0.1:8080/` 返回 `200`。
