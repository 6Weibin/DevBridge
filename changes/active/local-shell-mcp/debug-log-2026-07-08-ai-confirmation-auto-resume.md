# AI 工具确认后不自动续跑排查记录

## 排查步骤

- [x] 复查前端确认交互：`DevBridge-Front/src/app/ai/AiChatPanel.tsx`
- [x] 复查后端流式对话确认中断逻辑：`DevBridge-Server/src/main/java/com/devbridge/server/ai/conversation/AiConversationService.java`
- [x] 复查 ADB / Local Shell 确认接口：`AiMcpController`、`LocalShellMcpController`
- [x] 修改前端确认后的续跑状态机
- [x] 执行前端构建、后端测试、后端打包和 8080 重启验证

## 假设与验证

1. 假设：后端确认接口执行命令失败，导致无法继续。
   - 验证结果：不成立。确认接口只负责执行令牌绑定命令并返回工具结果，接口本身没有续跑 AI 的职责。
2. 假设：前端点击确认后没有触发后续 AI 请求。
   - 验证结果：成立。`decideConfirmation` 只更新确认卡片和工具结果，没有再次调用 `chatStream`。
3. 假设：后端 SSE 在确认时已经彻底结束，无法原连接继续。
   - 验证结果：成立。`completeForConfirmation` 会发送 `done`、完成 emitter 并取消 Provider 流，这是为了防止确认前模型继续执行后续工具。

## 根因定位链

用户问题是“确认后 AI 停住，必须再输入继续”。根因不是工具执行失败，而是前一次修复把确认点实现成了“结束当前 SSE 对话”，只解决了确认前阻塞问题，没有设计确认后的续跑状态机。用户点击确认后，前端只把真实工具执行结果追加到过程卡片，并未自动把该结果作为上下文交回模型继续推理，所以 AI 必须依赖用户下一条“继续”消息才能重新启动。

## 修复方案

- 抽取 `streamAssistantTurn`，让普通发送和确认后续跑共用同一套流式响应、工具过程卡片、确认卡片处理逻辑。
- 在 `decideConfirmation` 确认成功后自动调用 `continueAfterApprovedTool`。
- 构造内部续跑提示 `confirmationContinuationPrompt`，包含原始任务、工具类型、最终命令、状态、退出码、stdout 和 stderr，并明确要求模型不要等待用户再输入“继续”，也不要重复执行已完成命令。
- 续跑请求不新增用户气泡，避免 UI 上出现一条用户没有手动输入的消息。

## 验证

- `npm run build` 通过，生成 `Ai DevBridge V2026.7.0098`。
- `mvn test` 通过，97 个测试全部通过。
- `mvn package -DskipTests` 通过。
- 8080 已重启，当前进程 PID 为 `20026`。
- `GET /api/ai/config/status` 返回已配置 GLM：`glm / glm-5.2`。
