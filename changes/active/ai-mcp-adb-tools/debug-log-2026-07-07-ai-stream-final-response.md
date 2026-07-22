# AI 工具调用后最终回复缺失排查记录

## 排查步骤

- 搜索前端 `AI 未返回最终回复。` 兜底文案，定位到 `AiChatPanel.sendMessage` 的工具调用后完成判断。
- 阅读前端 `chatStream`、`readEventStream`、`handleSseBlock`，确认原实现只解析 chunk/error，不校验流是否收到 done。
- 阅读后端 `AiConversationService.streamChat`，确认工具调用结果会通过 SSE 透传，且 onComplete 有工具结果兜底回复。
- 检查后端 timeout/error 分支，发现 timeout 直接 `complete()`，前端会把静默关闭误判为正常结束。

## 假设与验证

- 假设 1：模型工具调用后没有继续输出，后端兜底未到达前端。验证：前端只有在 `assistantId === null` 时显示“AI 未返回最终回复”，说明最终 chunk 没进入前端消息。
- 假设 2：后端超时或 Provider 断流没有发送错误事件。验证：`emitter.onTimeout` 只取消 handle 并 complete，没有发送 error。
- 假设 3：前端异常信息不可见。验证：`runRequest` 只写入 hint，聊天内容区没有错误消息。

## 根因定位

当前流式协议缺少前端终止态校验：连接关闭但没有收到 `done` 时，前端仍认为请求正常完成。再叠加后端 timeout 分支没有发送错误事件，用户最终只能看到“AI 未返回最终回复。”，无法判断是 Provider 超时、工具调用后未续写，还是 SSE 中断。

## 修复方案

- 后端 `AiChatStreamEvent` 增加兼容式 `detail` 字段，用于错误诊断详情。
- 后端 `AiConversationService` 在流式 timeout 时发送 `AI_STREAM_TIMEOUT` 错误事件，再关闭 SSE。
- 前端 `readEventStream` 记录是否收到 `done`，连接关闭但没有 `done` 时抛出“AI 流式响应中断”错误。
- 前端 `AiChatPanel` 增加红色错误消息，异常和静默中断都写入聊天内容区。

## 验证结果

- `npx -y yarn@1.22.22 build` 通过。
- `mvn -Dtest=AiConversationServiceTest test` 通过。
