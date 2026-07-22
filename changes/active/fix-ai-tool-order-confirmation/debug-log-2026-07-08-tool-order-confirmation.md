# AI 工具调用顺序与确认阻塞问题排查记录

## 问题现象

- 思考与执行区域中，后确认执行的工具结果会插入到较早的确认卡片位置，看起来顺序错乱。
- 敏感操作已经进入用户确认阶段后，模型仍可能继续调用后续工具。

## 排查步骤

- 检查前端 `appendProcessTool` 和 `replaceConfirmationResult`，确认确认后的执行结果原先会替换确认卡片原位置。
- 检查后端 `AiConversationService` 工具事件注册逻辑，确认 `CONFIRMATION_REQUIRED` 只是普通工具结果，Provider 流没有被中止。
- 检查 Spring AI ToolCallback 适配器，确认工具返回确认结果后模型仍能继续收到工具 JSON 并继续规划。

## 根因

确认操作是用户决策边界，但后端没有在确认事件发出后停止当前 Provider 流；前端又在用户确认后把执行结果写回确认卡片原位置，造成时间线和真实执行顺序不一致。

## 修复方案

- 后端在工具结果为 `confirmationRequired=true` 时发送确认事件和 `done`，主动取消 Provider 流。
- 前端识别确认事件后不再追加“AI 中断未返回最终回复”的错误消息。
- 思考与执行卡片在待确认时显示“等待用户确认”并保持展开。
- 用户确认后，原确认卡片转为已处理历史步骤，真实执行结果追加到过程末尾，保持时间线顺序。

## 验证

- `mvn -Dtest=AiConversationServiceTest,LocalShellCommandExecutorTest test`
- `mvn test`
- `npm run build`
- `POST /api/ai/mcp/local-shell/tools/call` 高风险命令返回 `CONFIRMATION_REQUIRED`。
