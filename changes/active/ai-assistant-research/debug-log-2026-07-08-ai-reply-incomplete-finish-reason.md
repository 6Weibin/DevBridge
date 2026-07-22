# AI 回复输出不完整排查记录

## 排查步骤

- [x] 复查前端 SSE 读取逻辑：`DevBridge-Front/src/app/ai/aiApi.ts`
- [x] 复查 AI 面板流式消息落盘逻辑：`DevBridge-Front/src/app/ai/AiChatPanel.tsx`
- [x] 复查后端普通对话流式服务：`DevBridge-Server/src/main/java/com/devbridge/server/ai/conversation/AiConversationService.java`
- [x] 复查 Spring AI Provider Gateway：`DevBridge-Server/src/main/java/com/devbridge/server/ai/provider/SpringAiProviderGateway.java`
- [x] 验证 Spring AI 1.0.9 流式 API 是否能暴露 `finishReason`
- [x] 执行后端全量单元测试

## 假设与验证

| 假设 | 结论 | 验证结果 |
| --- | --- | --- |
| 前端 SSE 尾部 `done` 漏消费导致误判中断 | 本轮证据不足 | 现有代码已经 flush `TextDecoder` 并兼容 CRLF，连接关闭未收到 `done` 会显示明确错误。 |
| Electron OOM 防护导致回复被 120k 字符上限截断 | 可能但不是唯一根因 | 前后端都有明确截断提示，如果出现该提示属于受控截断。 |
| 模型达到 `max_tokens` 后协议正常完成，但业务内容不完整 | 高置信 | 后端使用 `.stream().content()` 只拿文本，不读取 Spring AI `ChatResponse` metadata，无法识别 `LENGTH`。 |
| 工具调用后模型未总结导致没有最终回复 | 已有兜底 | 后端会在工具后无内容时追加工具原始输出兜底，不是本轮主要问题。 |

## 根因定位链

用户看到“AI 回复输出没完成” → 前端可能已经收到 `done`，因此不会进入错误分支 → 后端 Provider 流式调用使用 Spring AI `.stream().content()` → 该 API 丢弃 `GenerationMetadata.finishReason` → 模型因 `max_tokens` 返回 `LENGTH` 时，后端仍按正常完成处理 → 前端没有任何“达到模型输出上限”的提示，表现为回复突然结束。

## 修复方案

- 将 Spring AI 流式读取从 `.content()` 改为 `.chatResponse()`，保留文本输出的同时读取 `finishReason`。
- 扩展 `AiProviderStreamListener.onComplete(String finishReason)`，使用默认方法保持旧实现兼容。
- 在 `AiConversationService` 中识别 `LENGTH/MAX_TOKENS/CONTENT_FILTER`，在回复末尾追加明确诊断提示。
- 将默认输出 token 从 3000 提升到 6000，降低复杂表格和长分析被过早截断的概率。
- 保留前后端 120k 字符保护，避免为追求长回复再次引入 Electron OOM。

## 验证

- `mvn test -Dtest=AiConversationServiceTest,SpringAiProviderGatewayTest`：通过。
- `mvn test`：通过，98 个测试全部通过。
