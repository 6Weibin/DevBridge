# AI 工具卡片元数据问题排查记录

## 问题现象

- 思考与执行区域中的工具调用卡片标题固定显示为 `ADB MCP`。
- Local Shell MCP 执行本机终端命令时，卡片没有展示最终执行命令。

## 排查步骤

- 搜索前端工具卡片渲染，定位到 `AiToolCallCard` 和 `AiChatPanel` 调用处存在硬编码 `ADB MCP`。
- 检查后端 `AdbMcpToolResult`，确认统一结果中只有状态、输出、消息和风险字段，没有工具族标题和命令摘要。
- 检查 Local Shell 与 ADB 执行器，确认后端能拿到真实 `ProcessBuilder` 命令，但未透传给前端。

## 根因

前端硬编码工具标题只是表层问题。根因是统一 MCP 工具结果模型缺少可展示的工具元数据，导致前端无法区分 ADB MCP 与 Local Shell MCP，也无法展示最终执行命令。

## 修复方案

- 在 `AdbMcpToolResult` 增加 `toolTitle` 和 `commandSummary`，并保留旧构造器兼容既有调用。
- ADB 执行器返回 `ADB MCP` 和实际 adb 命令。
- Local Shell 执行器返回 `Local Shell MCP` 和实际本机命令。
- SSE 压缩结果保留新增字段。
- 前端卡片使用 `toolTitle` 展示标题，并新增“执行命令”区域展示 `commandSummary`。
- 旧历史数据没有新增字段时，前端按确认令牌和错误码做兼容兜底。

## 验证

- `mvn -Dtest=LocalShellCommandExecutorTest,LocalShellToolCatalogTest,LocalShellRiskClassifierTest,LocalShellConfirmationServiceTest,AiConversationServiceTest test`
- `mvn test`
- `npm run build`
