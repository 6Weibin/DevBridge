# AI 回复正文不应被前端截断排查记录

## 排查步骤

- [x] 检查前端 SSE 读取逻辑：`DevBridge-Front/src/app/ai/aiApi.ts`
- [x] 检查 AI 面板消息状态清洗和流式追加逻辑：`DevBridge-Front/src/app/ai/AiChatPanel.tsx`
- [x] 检查 Markdown 渲染轻量模式：`DevBridge-Front/src/app/ai/MarkdownContent.tsx`
- [x] 检查后端 SSE 下发逻辑：`DevBridge-Server/src/main/java/com/devbridge/server/ai/conversation/AiConversationService.java`
- [x] 执行前端构建、后端测试和后端打包
- [x] 重启 8080 后端并探活

## 假设与验证

| 假设 | 结论 | 验证结果 |
| --- | --- | --- |
| “AI 回复过长”提示来自 Provider 或模型 | 不成立 | 提示由前端 `aiApi.ts` 主动追加，后端也存在对应总字符截断。 |
| 当前策略会改变业务结果完整性 | 成立 | 前端 SSE、前端消息状态、Markdown 显示、后端 SSE 都存在 120k 限制或省略逻辑。 |
| OOM 应通过渲染和存储降级解决，而不是截断 AI 正文 | 成立 | 当前已有流式低频刷新、历史持久化压缩、工具输出压缩；应保留这些技术边界，移除最终回复正文截断。 |

## 根因定位链

长回复到达前端 → `aiApi.ts` 统计累计字符数超过 120k → 追加“前端已停止继续接收”提示并丢弃后续 chunk → 同时后端 `AiConversationService` 也可能超过 120k 后停止下发 → 即使数据到达消息层，`AiChatPanel` 和 `MarkdownContent` 仍会再次截断展示 → 用户看到的回复必然不完整。

## 修复方案

- 前端 SSE 不再按总字符数停止接收 AI 正文，只保留单个未完成 buffer 和单个 SSE 事件的协议异常保护。
- 后端 SSE 不再按总字符数停止下发 AI 正文，只保留 8000 字符分片，避免单个 SSE 事件过大。
- 当前会话的 AI 消息正文不再经过 `sanitizeMessageForState` 截断。
- Markdown 超过安全解析上限时进入轻量模式，但轻量模式按 6000 字符分块完整显示，不再截断显示。
- 历史持久化副本、工具输出、过程详情仍压缩，避免 localStorage 和工具输出长期占用内存。

## 验证

- `npm run build`：通过，生成 `/assets/index-CV2v9Nnr.js`。
- `mvn test -Dtest=AiConversationServiceTest,SpringAiProviderGatewayTest`：通过。
- `mvn test`：通过，98 个测试全部通过。
- `mvn package -DskipTests`：通过。
- 8080 已重启，新进程 PID `33191`，`/api/ai/config/status` 探活正常。
