# iOS 实时日志点击无反应排查记录

## 排查步骤

- [x] 阅读前端 `startLogStream`，确认原逻辑只允许 Android 进入 SSE，iOS 点击后直接返回。
- [x] 阅读后端 `LogController`，确认实时日志接口通过 `requireAndroid` 拒绝非 Android 平台。
- [x] 直接调用 `idevicesyslog -u {udid}` 验证工具链，确认当前 iOS 真机可以持续输出系统日志。
- [x] 补全后端 iOS SSE：`/api/devices/ios/{udid}/logs/stream` 调用 `idevicesyslog -u {udid}`。
- [x] 补全前端启动逻辑：Android 和 iOS 都允许点击开始采集；iOS 日志导出暂不开放。
- [x] 用 curl 验证 SSE，确认返回 `event:log` 和结构化日志 JSON。

## 假设与验证

| 假设 | 验证结果 |
| --- | --- |
| iOS 设备未连接或未信任电脑 | 不成立；设备详情和 `idevicesyslog` 均可读取 |
| 本机缺少 `idevicesyslog` 工具 | 不成立；工具状态显示 `/opt/homebrew/bin/idevicesyslog` 可用 |
| 前后端没有实现 iOS 日志链路 | 成立；前端只允许 Android，后端也只支持 Android logcat |

## 根因定位链

1. UI 点击“开始采集”后没有日志输出。
2. 前端 `startLogStream` 判断 `sel.platform !== "android"` 时直接返回，所以 iOS 不会创建 EventSource。
3. 即使绕过前端直接请求后端，`LogController` 也会拒绝 iOS 平台。
4. 真实工具 `idevicesyslog` 已验证可用，说明问题是项目实现缺失，不是设备或工具不可行。

## 修复方案

- 后端 `LogController` 按平台分发：Android 走 `streamAndroidLogs`，iOS 走 `streamIosLogs`。
- 后端 `LogStreamService` 新增 iOS 日志会话，复用 `StreamingCommandRunner` 启动 `idevicesyslog -u {udid}`。
- 新增 iOS 日志解析，将 `Debug/Info/Notice/Error/Fault` 映射到前端统一的 `D/I/E/F` 级别。
- 同设备新会话会接管旧会话，避免浏览器断开或 EventSource 重连后残留会话导致再次点击无响应。
- 前端允许 iOS 点击开始采集；日志导出仍保持 Android，避免扩大到未定义的 iOS 导出行为。

## 验证结果

- `idevicesyslog -u 00008120-00182C181EB8C01E`：可持续输出真机日志。
- `mvn test`：通过，13 个测试全部成功。
- `./node_modules/.bin/vite build`：通过。
- `mvn package -DskipTests`：通过。
- 8080 首页已引用新资源 `/assets/index-D8si6S3c.js`。
- `curl -N --max-time 2 '/api/devices/ios/{udid}/logs/stream?level=E'`：已返回多条 `event:log`。
