# Bridge Copilot 命名、确认续跑循环与工具卡片展示排查记录

## 排查步骤

- [x] 检查 AI 面板标题、欢迎语、浮动入口和配置页文案。
- [x] 检查“正在思考”加载态渲染位置和样式。
- [x] 检查工具卡片标题来源：`toolTitle`、`toolDisplayName`、`AiToolCallCard`。
- [x] 检查敏感操作确认后的续跑链路：`decideConfirmation`、`continueAfterApprovedTool`、`confirmationContinuationPrompt`。
- [x] 检查后端工具范围选择：`AiConversationService.resolveToolScope`、`localShellIntent`。
- [x] 执行前端构建、后端专项测试、后端全量测试和打包。

## 假设与验证

| 假设 | 结论 | 验证结果 |
| --- | --- | --- |
| 确认后重复请求确认是因为确认结果没有进入续跑上下文 | 成立 | `continueAfterApprovedTool(messages, result)` 使用 React 闭包中的旧 `messages`，可能不包含刚替换的确认结果。 |
| ADB 确认后误切成本机终端工具范围 | 成立 | 续跑提示中包含“执行命令/工具命令”和工具输出，后端 `localShellIntent` 会被 `java/进程/端口/执行命令` 等词误触发。 |
| 工具卡片标题出现 MCP 是后端工具元数据直接展示 | 成立 | 前端 `result.toolTitle || toolName` 直接展示 `ADB MCP`、`Local Shell MCP`。 |
| “正在思考”气泡来自 loading 状态样式 | 成立 | loading 区域使用了 border、bg-card、rounded、shadow。 |

## 根因定位链

用户确认敏感操作 → 前端调用确认接口得到真实结果 → 前端用旧 `messages` 构造续跑历史 → 后端根据续跑提示做工具范围判断 → 提示和工具输出中包含本机命令关键词 → ADB 场景可能误切换到本机终端工具范围 → 模型缺少完整工具时间线并拿到错误工具范围 → 可能重复调用已完成或无需重复的敏感工具，反复进入确认。

## 修复方案

- 将显示名从“AI 助手”改为 `Bridge Copilot`，覆盖标题、入口 tooltip、欢迎语、配置页默认提示词。
- 增加 `messagesRef`，确认后先生成包含确认结果的 `updatedMessages`，再用于自动续跑。
- 续跑提示新增最近工具历史摘要，明确哪些工具已经完成，避免模型重复操作。
- 续跑提示把“执行命令”改为“操作摘要”，减少本机工具意图误触发。
- 后端新增确认续跑工具类型识别：声明 `ADB 设备管理工具` 时强制使用 ADB 工具范围，即使输出里包含 `java/进程/端口` 等词。
- 工具展示名统一去掉 `MCP`：`ADB MCP` 显示为 `ADB 工具`，`Local Shell MCP` 显示为 `本机终端`。
- “正在思考”去掉气泡外框，改为轻量文本行和带动画的 Sparkles 图标。

## 验证

- `npm run build`：通过，生成 `/assets/index-DgLn-C-g.js`。
- `mvn test -Dtest=AiConversationServiceTest`：通过，8 个测试。
- `mvn test`：通过，99 个测试。
- `mvn package -DskipTests`：通过。
- 8080 已重启，新进程 PID `45641`，`/api/ai/config/status` 探活正常。
