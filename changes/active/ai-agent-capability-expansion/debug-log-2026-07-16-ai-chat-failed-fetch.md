# AI 对话 Failed to fetch 排查记录

## 排查步骤

- [x] 验证 `8080` 进程、配置接口和首页可访问性。
- [x] 直接调用 `/api/ai/chat/stream` 复现无响应。
- [x] 检查运行进程启动时间、JAR 更新时间和线程状态。
- [x] 重启当前 JAR，并验证控制面认证、Cookie 引导和真实 GLM 流式对话。
- [x] 修复后端重启后旧页面 Cookie 失效的自动恢复缺口。

## 假设与结果

| 假设 | 验证结果 |
|------|----------|
| Provider、API URL 或 API Key 错误 | 否。配置状态正常，携带有效 Cookie 后 GLM 返回 `OK` 和 `done`。 |
| 最近长回复分段改动破坏请求 JSON | 否。请求在进入 Provider 前即失败，当前构建的流式请求可正常完成。 |
| 页面与后端版本不一致 | 是。`8080` 仍运行三天前启动的旧 JVM，磁盘 JAR 已在当天重新打包。 |
| 控制面令牌在后端重启后失效 | 是。新进程使用新内存令牌，未刷新的同源页面继续携带旧 Cookie，API 返回 `CONTROL_PLANE_UNAUTHORIZED`。 |

## 根因定位链

`Failed to fetch` -> 配置 GET 正常但旧 SSE 无响应 -> 发现旧 JVM 未加载当天 JAR且输出连接异常 -> 重启当前 JAR -> 无 Cookie 请求返回 401 -> 首页引导后真实 GLM 对话成功 -> 确认“旧服务未重启 + 页面 Cookie 无自动恢复”共同造成故障。

## 修复方案

保留本地控制面认证，并采用两层同源恢复：

1. 后端仅对明确携带同源 `Origin` 的浏览器请求重新签发当前进程 Cookie，并让原请求继续；无 Origin 的本地进程和跨域网页仍返回 401。
2. 前端统一请求在确认响应为 `401 CONTROL_PLANE_UNAUTHORIZED` 且页面与后端同源时，重新访问首页获取当前进程 HttpOnly Cookie，并安全重试原请求一次，用于覆盖不携带 Origin 的同源 GET。

401 由认证过滤器在 Controller 之前返回，因此前端重试不会重复业务副作用；Electron 和跨域页面不进入该恢复路径。

## Electron 专项复查

普通浏览器恢复后，Electron 仍出现 `Failed to fetch`。再次按 Electron 的 `15173 -> 18180` 跨域链路检查，确认聊天请求携带 `Idempotency-Key`，统一 Agent 确认还携带 `X-Agent-Conversation-Id` 和 `X-Agent-Confirmation-Token`，事件恢复可能携带 `Last-Event-ID`。Electron 主进程原先只返回：

```text
Access-Control-Allow-Headers: Content-Type,Accept,X-Ai-DevBridge-Token
```

因此 Chromium 在请求到达后端前就拒绝预检，前端只能得到网络层 `Failed to fetch`；普通浏览器由于同源不会触发该问题。

修复后 Electron CORS 白名单与当前协议头保持一致，包含 `Idempotency-Key`、两个 Agent 确认头和 `Last-Event-ID`。控制面令牌仍由 Electron 主进程注入，未放宽后端认证。

## 停止任务专项修复

停止按钮原先先等待后端取消成功再中断 SSE，导致清理延迟或取消竞态直接表现为按钮失败。与此同时，取消 Provider 会触发其异常回调，异常回调可能把任务先写成 `FAILED`，随后取消流程再写 `CANCELED` 时产生非法终态转换。

修复后前端立即停止当前 SSE，再独立请求后端清理；后端在取消作用域生效后忽略迟到的 Provider 完成和失败回调，并在提交取消终态前重新读取任务状态。暂停和继续入口从前端移除，后端兼容 API 暂时保留，不影响历史任务恢复数据。
