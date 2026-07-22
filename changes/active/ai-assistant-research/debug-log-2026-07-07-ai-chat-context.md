# AI 对话上下文连续性排查记录

## 问题现象

- 用户在 AI 助手中完成一轮问答后，继续基于上一轮内容追问，AI 无法理解“上个对话”的指代。
- 现象表现为 AI 像单轮问答一样处理当前输入，不知道上一轮已经查询或分析过什么。

## 排查步骤

- [x] 检查前端 `AiChatPanel.tsx` 发送对话请求时携带的字段。
- [x] 检查前端 `AiChatRequest` 类型定义。
- [x] 检查后端 `AiChatRequest` record。
- [x] 检查 `AiConversationService.userPrompt` 是否拼接历史消息。
- [x] 检查 `SpringAiProviderGateway` 是否使用 Spring AI 会话记忆或只发送单次 prompt。
- [x] 增加后端单测，约束最近历史必须进入 prompt。

## 假设与验证

| 假设 | 验证结果 |
| --- | --- |
| 模型本身不支持多轮对话 | 不成立。OpenAI-compatible 模型支持多轮，但调用方必须传入历史消息或会话记忆。 |
| `conversationId` 已经让后端关联上下文 | 不成立。当前 `conversationId` 只用于 ADB MCP 工具确认令牌和工具事件绑定。 |
| 前端发送了历史但后端没使用 | 不成立。前端原始请求类型里只有 `message`、`deviceContext`、`conversationId`。 |
| 后端每次只构造当前用户问题 | 成立。`userPrompt(request)` 只包含设备摘要和当前问题。 |

## 根因链

前端对话窗口保存了完整消息列表 → 发送请求时只取当前输入文本 → 后端 `AiChatRequest` 没有历史字段 → `conversationId` 仅绑定工具确认和事件，不承载语义记忆 → `SpringAiProviderGateway` 每次创建一次性 `ChatClient.prompt()` 并只发送当前 user prompt → 模型无法看到上一轮问答 → 连续追问失效。

## 修复方案

- 前端 `AiChatRequest` 增加 `history` 字段。
- 发送普通流式对话前，从当前窗口消息列表中提取最近 12 条普通用户/AI文本消息。
- 过程卡片、工具卡片、错误消息、系统欢迎语不进入历史，避免噪声和敏感执行细节反复放大。
- 前端限制单条历史内容长度为 1200 字符。
- 后端 `AiChatRequest` 增加历史字段，并兼容旧请求为空历史。
- 后端在 `userPrompt` 中加入最近对话上下文，并限制总历史长度 8000 字符。
- 后端只接受 `user` 和 `assistant` 两类历史角色，忽略异常角色。

## 验证结果

- `npx -y yarn@1.22.22 build`：通过，生成 `DevBridge V2026.7.0060`。
- `mvn -Dtest=AiConversationServiceTest test`：通过，4 个测试。
- `mvn -DskipTests resources:resources`：通过。
- 重启后 `http://127.0.0.1:8080/` 返回 `200`。
- 页面加载新资源：`index-cuMeROoz.js`、`index-S1XeYDXJ.css`。

