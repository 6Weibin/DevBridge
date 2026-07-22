# AI ADB MCP 工具调用无有效回复排查记录

## 排查步骤

- [x] 复核用户症状：询问“查询当前链接设备安装的所有应用，并分析都是什么应用。整理成表格给我。”后，前端出现 ADB MCP 调用，但没有正确 AI 回复。
- [x] 检查前端 `AiChatPanel` 渲染顺序，确认工具结果使用独立 `toolResults` 列表，并在 `messages` 前渲染。
- [x] 检查后端 `AiConversationService` 流式链路，确认工具结果只透传为 SSE 工具事件，模型无文本输出时前端只能显示“AI 未返回内容”。
- [x] 检查 `AdbToolCatalog`，确认 `adb_shell` schema 继承了通用 `action` 必填字段，但执行器实际使用 `command/commandArgs`。
- [x] 通过服务日志验证 ADB 实际执行过 `adb shell pm list packages -f`，排除 ADB 命令未执行。
- [x] 补充后端测试和前端构建验证。

## 假设与验证结果

| 假设 | 置信度 | 验证结果 |
| --- | --- | --- |
| ADB MCP 没有执行命令 | 低 | 后端日志显示 `adb_shell` 已成功执行 `pm list packages -f`，该假设排除。 |
| 前端工具卡片出现在用户消息前是排序逻辑错误 | 高 | `toolResults` 独立渲染在 `messages.map` 前，必然导致工具信息位于对话最前。 |
| 工具调用后模型没有继续输出导致用户看不到答案 | 高 | 流式完成逻辑只发送 `done`，没有根据已返回工具结果生成兜底回复。 |
| `adb_shell` schema 会干扰模型入参 | 中高 | schema 要求 `action`，但 planner 的 shell 分支不消费 `action`，与真实契约不一致。 |

## 根因定位链

1. 用户问题触发 Spring AI Tool Calling，ADB MCP 成功执行 `adb_shell`。
2. 工具结果通过 `AiMcpToolEventPublisher` 发给前端工具卡片，但流式模型如果没有继续输出自然语言，后端没有任何兜底内容。
3. 前端在发送消息后立即创建空 assistant 占位，并把工具结果放在独立列表顶部渲染，导致用户看到的顺序不是“我的问题 -> 工具调用 -> AI 回复”。
4. `adb_shell` 工具 schema 与 planner 实际契约不一致，增加了模型生成错误入参的概率。

## 修复方案

- `AiConversationService`：
  - 流式请求期间收集 ADB MCP 工具结果。
  - 如果流式完成时没有任何模型文本但已有工具输出，基于脱敏后的工具结果生成 Markdown 兜底回复。
  - 对 `pm list packages` 输出生成 Markdown 表格，按包名前缀做保守分类，避免编造具体应用名称。
- `AdbToolCatalog`：
  - `adb_shell` schema 改为 `command/commandArgs/args/globalOptions/environment`，不再继承无效的 `action required`。
  - 工具描述补充查询应用列表的推荐入参示例。
- `AiChatPanel`：
  - 工具调用结果并入 `messages` 时间线，不再单独在消息列表前渲染。
  - assistant 消息延迟到首次 chunk 时创建，保证工具调用发生在用户消息之后、最终回复按真实事件顺序出现。

## 验证

- `mvn test`：通过，62 个测试全部成功。
- `npm run build`：通过，构建版本 `V2026.7.0024`。
- 服务重启后健康检查：
  - `http://127.0.0.1:8080/` 返回 200。
  - `http://127.0.0.1:5173/` 返回 200。
  - `http://127.0.0.1:8080/api/ai/mcp/adb/tools` 返回 200。

## 二次复查记录

用户复测后仍反馈“ADB MCP 命令执行完成后，没有其他回复”。重新用 `curl -N /api/ai/chat/stream` 复现后确认：

- Provider 会先输出“好的，我先查询设备上安装的所有应用包名。”等工具调用前文本。
- 随后 ADB MCP 返回 `tool-result`。
- 原逻辑只要检测到任意文本 `contentSent=true`，就不会触发兜底回复，因此工具后的最终总结仍为空。

修复调整：

- 后端新增 `contentAfterTool` 判断，工具事件到达后重置为 `false`，只有工具事件之后继续收到模型文本才置为 `true`。
- `onComplete` 改为按“工具调用后是否有内容”判断是否发送兜底 Markdown，而不是按整次请求是否有任意文本判断。
- 前端在工具事件进入时间线后重置当前 assistant 消息 ID，使工具后的兜底/总结文本作为新消息显示在工具卡片之后。

二次验证：

- `mvn test`：通过，62 个测试全部成功。
- `npm run build`：通过，构建版本 `V2026.7.0025`。
- 直接复测同一 SSE 请求，确认 `tool-result` 后出现 `event:chunk`，内容为“已通过 ADB 查询到 302 个应用包...”的 Markdown 表格，然后才发送 `event:done`。
