# Local Shell MCP 实施任务

> 来源：`changes/active/local-shell-mcp/design.md`
> 状态：实现与验收中

## 任务清单

- [x] 后端模型与配置：新增 `ai-mcp-local-shell` 配置、请求/计划/确认/审计模型。
- [x] 工具目录：实现 `LocalShellToolCatalog`，注册 P0 工具和 JSON Schema。
- [x] 策略与风险识别：实现工作目录、环境变量、风险命令和拒绝规则。
- [x] 确认机制：实现一次性确认令牌、绑定校验、确认和取消接口。
- [x] 命令执行：实现 `ARGV` / `SHELL` 执行、超时、取消、流式输出和输出脱敏。
- [x] Spring AI 适配：新增 Local Shell ToolCallback 并接入 `AiToolRegistry`。
- [x] REST/SSE 接口：提供工具目录、调用、流式调用、确认、取消运行接口。
- [x] 前端交互：泛化确认卡片，并按 `local-` 令牌路由到 Local Shell 确认接口。
- [x] AI 授权配置：在 AI 配置中新增 Local Shell MCP 命令授权规则，支持低/中/高安全等级控制直接执行、确认和阻断。
- [x] 测试与验收：覆盖风险识别、确认令牌、工具目录和构建验证。

## 当前说明

- Local Shell MCP 与 ADB MCP 使用独立后端包，前端只复用工具过程与确认交互。
- 高风险命令返回确认令牌，令牌绑定对话、命令、工作目录和风险级别。
- 用户授权规则只影响 Local Shell MCP；内置极高危命令仍优先阻断，不能通过低风险授权降级。
- 控制类工具 `local_shell_process_status`、`local_shell_cancel` 只操作本应用启动的进程，不扫描或控制系统全部进程。
