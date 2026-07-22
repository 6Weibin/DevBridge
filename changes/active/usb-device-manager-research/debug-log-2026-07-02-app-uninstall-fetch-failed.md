# 应用卸载 Failed to fetch

## 排查步骤

- [x] 检查前端卸载请求路径：`DELETE /api/devices/{platform}/{serial}/apps/{packageName}`。
- [x] 检查后端卸载接口和服务：卸载命令为 `adb shell pm uninstall <packageName>`，参数数组执行，无 shell 拼接。
- [x] 查看后端日志：请求中断后 Tomcat 日志链路出现 `NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy`。
- [x] 使用不存在设备序列号验证 DELETE 请求，发现修复前请求会挂住，浏览器侧表现为 `Failed to fetch`。
- [x] 定位通用命令执行器：`CommandRunner` 在超时后 `destroyForcibly()` 再同步读取 stdout/stderr，adb 子进程/流未及时关闭时会阻塞 HTTP 请求。
- [x] 修改 `CommandRunner` 为异步读取 stdout/stderr，超时后快速返回 `CommandResult`。
- [x] 使用不存在包名 `com.devbridge.notinstalled` 在真实设备上验证失败链路，只返回 JSON 错误，不卸载真实应用。

## 假设与验证

| 假设 | 验证结果 |
|------|----------|
| 前端 URL 或 DELETE 方法错误 | 不成立。接口路径匹配，后端有对应 `@DeleteMapping`。 |
| 后端未启动或端口不可用 | 不成立。`http://127.0.0.1:8080/` 返回 200。 |
| 卸载命令返回失败导致前端 `Failed to fetch` | 不完整。命令失败应返回 JSON；实际问题是请求线程卡住/断开。 |
| `CommandRunner` 超时处理会阻塞 | 成立。超时后同步读取未关闭的进程流可能挂住请求。 |

## 根因定位

卸载接口先检查设备连接并执行 adb 命令。当前 `CommandRunner` 在进程超时时会强杀进程，但随后同步读取 stdout/stderr；如果 adb 或其子进程没有及时关闭输出流，请求线程会继续阻塞，前端 fetch 等不到 HTTP 响应，最终显示 `Failed to fetch`。日志里的 logback `NoClassDefFoundError` 是请求异常/断开后记录错误时的二次问题，不是卸载命令失败本身。

## 修复方案

1. `CommandRunner` 启动进程后立即异步读取 stdout/stderr，避免输出管道阻塞进程退出。
2. 命令超时后强杀进程并最多等待 500ms，不再同步阻塞读取输出流。
3. 获取输出时只等待 100ms；未完成则返回空列表，保证 HTTP 请求能按超时边界返回。
4. 新增 `CommandRunnerTest` 覆盖超时命令快速返回。

## 验证结果

- `mvn test`：通过，36 个测试全部成功。
- `mvn package -DskipTests`：通过。
- 服务已重启，`http://127.0.0.1:8080/` 返回 200。
- `DELETE /api/devices/android/NO_SUCH_SERIAL/apps/com.example.notinstalled` 返回 `409 DEVICE_NOT_CONNECTED` JSON。
- `DELETE /api/devices/android/66J5T19411001963/apps/com.devbridge.notinstalled` 返回 `502 APP_UNINSTALL_FAILED` JSON，不再卡住或 `Failed to fetch`。
