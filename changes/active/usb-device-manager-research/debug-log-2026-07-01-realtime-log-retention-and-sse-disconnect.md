# 实时日志保留与 SSE 断连排查记录

## 排查步骤

- [x] 复核前端实时日志状态管理：检查 `App.tsx` 中日志级别切换、缓存裁剪、EventSource 生命周期。
- [x] 复核后端日志流：检查 `LogStreamService` 的 logcat/idevicesyslog 启动命令、SSE 发送、断连清理。
- [x] 用 `curl --max-time` 模拟 SSE 客户端主动断开，观察后端日志。
- [x] 补充单测覆盖 Android logcat 初始回放上限和客户端断连异常处理。
- [x] 执行前端构建、后端完整测试、后端打包、服务重启和接口验证。

## 假设与验证

| 假设 | 证据 | 结论 |
| --- | --- | --- |
| 切换日志级别后达不到 1000 行，是因为所有级别共用一个 1000 行缓存，低频级别被高频级别挤掉。 | 前端原逻辑只保留总日志数组；切到 E/W/D 等级别后只能从总数组中过滤。 | 成立。改为 ALL/V/D/I/W/E/F 独立缓存桶，每个级别最多保留 1000 行。 |
| iOS/高频日志几行后像清屏，是每行触发一次 React 重绘造成 UI 抖动。 | iOS 系统日志吞吐高，逐条 `setLogs` 会频繁重绘整表。 | 成立。改为前端批量 flush，减少重绘压力。 |
| SSE 断开后 Spring 仍尝试写 JSON 错误，导致 EventSource 错误链更明显。 | 断连测试曾出现 `Broken pipe`、`HttpMessageNotWritableException` 和 `text/event-stream` 下写 JSON 的异常。 | 成立。增加流式响应断连的 IO 异常处理，不再写 JSON 错误体。 |
| Android 初始历史日志倒灌过多，放大前端和 SSE 压力。 | `curl` 4 秒曾收到 6000+ 事件。 | 成立。Android logcat 改为 `-T 1000`，只回放最近 1000 行后接实时增量。 |

## 根因链

1. 前端用一个总日志数组承载所有级别，数组达到 1000 后按总量裁剪。
2. 用户切换日志类型时，只是在已裁剪总数组上过滤，低频类型天然不足 1000 行。
3. iOS/Android 高频日志会快速触发大量 SSE 消息，逐条重绘会让页面看起来像清空刷新。
4. Android logcat 默认会输出较多历史日志，进一步放大启动瞬间的消息洪峰。
5. 客户端断开 SSE 时，Spring 异步写失败会进入全局 JSON 异常处理链，产生 `text/event-stream` 响应中写 JSON 的异常。

## 修复方案

- 前端为每个日志级别建立独立缓存桶，级别切换读取对应缓存桶，保证每个级别最多可显示 1000 行。
- 前端日志接收改为批量刷新，并阻止重复点击开始采集创建多个 EventSource。
- 后端 Android 实时日志命令增加 `-T 1000`，控制初始历史回放规模。
- 后端全局异常处理器识别 `Broken pipe`、`Connection reset` 等客户端断连 IO 异常，不再尝试写 JSON 错误响应。

## 验证结果

- `npx -y yarn@1.22.22 build` 通过。
- `mvn test` 通过：21 个测试全部通过。
- `mvn package -DskipTests` 通过。
- 重启后 `http://127.0.0.1:8080/` 返回 200，引用新前端资源 `index-C9XkmyvB.js`。
- `http://127.0.0.1:8080/api/runtime/environment` 返回 200。
- SSE 短连接验证返回 `text/event-stream`，2 秒内收到 1100+ 条事件。
- 断开后服务日志未再出现 `Broken pipe`、`HttpMessageNotWritableException`、`text/event-stream` 写 JSON、`AsyncRequestNotUsableException` 或 `NoClassDefFoundError`。
