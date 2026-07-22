> 来源: `changes/active/ai-mcp-adb-tools/spec.md`
> 生成时间: 2026-07-06
> 阶段: design

### Why

**背景与现状**

DevBridge 当前已经完成 AI 浮动入口、AI 配置、Spring AI Provider Gateway、流式对话、Markdown 渲染和日志分析能力。前端 AI 代码集中在 `DevBridge-Front/src/app/ai/`，`App.tsx` 只负责挂载 AI Shell 并提供当前设备和日志快照；后端 AI 代码集中在 `DevBridge-Server/src/main/java/com/devbridge/server/ai/`，已有 `config`、`provider`、`conversation`、`analysis`、`security`、`tool`、`rag`、`observation` 等包边界。

后端设备能力已具备可复用基础：`ExecutableLocator` 可定位项目内置 ADB，`CommandRunner` 以参数数组执行短命令并处理超时，`StreamingCommandRunner` 可启动并停止长进程，`AndroidDeviceService` 已有设备详情、应用、文件、截图、日志导出等语义化能力。现有 AI 日志分析仍是单业务接口形态，若继续为安装、卸载、端口转发、bugreport、任意 shell 等场景逐个增加 AI 专用接口，会导致 AI 编排与设备业务强耦合。

**设计目标 / 非目标**

| 类型 | 说明 |
|------|------|
| ✅ 目标 | 新增覆盖项目内置 ADB `1.0.41 / 37.0.0-14910828` 顶层能力的 MCP 工具契约 |
| ✅ 目标 | 支持任意 `adb shell COMMAND...`，不做产品语义白名单裁剪 |
| ✅ 目标 | 对删除、卸载、清数据、安装写入、重启、root、remount、verity、server 影响等敏感操作做对话确认 |
| ✅ 目标 | MCP 工具核心与 Spring AI 解耦，Spring AI Tool Calling 只作为第一阶段调用适配 |
| ✅ 目标 | 统一工具结果、错误码、输出限制、脱敏、审计和取消模型 |
| ✅ 目标 | 日志分析改为可通过 MCP 工具获取 logcat 或当前日志上下文，旧接口只保留兼容入口 |
| ✅ 目标 | 前端 AI 侧边栏展示工具调用状态、确认卡片和执行结果摘要，不影响主界面状态 |
| ❌ 非目标 | 不实现 iOS/HarmonyOS 全量 MCP 工具集 |
| ❌ 非目标 | 不让前端直接执行 adb 或拼接宿主机命令 |
| ❌ 非目标 | 不把 MCP 执行逻辑写入 `App.tsx`、文件页、应用页、日志页主体逻辑 |
| ❌ 非目标 | 不取消现有设备、文件、应用、日志 REST 接口 |
| ❌ 非目标 | 第一阶段不强制暴露独立网络 MCP Server 进程；但工具契约必须能被标准 MCP 适配层复用 |

### What

#### 技术方案

**架构决策**

| 模块 | 职责 | 依赖 |
|------|------|------|
| `ai.mcp.catalog` | 定义 ADB MCP 工具目录、工具参数 Schema、输出 Schema、ADB version 绑定和风险元数据 | 无业务页面依赖 |
| `ai.mcp.model` | 定义工具请求、工具结果、错误码、风险级别、确认状态、运行上下文 | Jackson |
| `ai.mcp.execution` | 组装 ADB 参数数组、校验设备状态、执行短命令和长命令、管理取消句柄 | `ExecutableLocator`、`CommandRunner`、`StreamingCommandRunner`、`DeviceService` |
| `ai.mcp.risk` | 识别顶层 ADB 命令和 shell 命令中的敏感操作，生成风险说明和影响范围 | `ai.mcp.catalog` |
| `ai.mcp.confirmation` | 生成、校验、消费和取消一次性确认令牌 | `ai.mcp.model`、JDK crypto/time |
| `ai.mcp.security` | 对 stdout/stderr、错误摘要、设备序列号、token、账号字段做截断和脱敏 | 现有 `SensitiveDataMasker` |
| `ai.mcp.audit` | 记录工具名、设备摘要、参数摘要、风险、确认状态、耗时、exitCode、错误摘要 | SLF4J、`AiObservationRecorder` |
| `ai.mcp.adapter.spring` | 将内部 MCP 工具契约适配为 Spring AI `ToolCallback` | Spring AI `ToolCallback` |
| `ai.mcp.adapter.rest` | 提供前端和后续调试使用的工具目录、工具调用、确认、取消 REST/SSE 入口 | 独立 `AiMcpController` |
| `ai.tool` | 继续作为 AI 工具注册边界，根据 `AiToolScope` 返回 Spring AI 工具回调 | `ai.mcp.adapter.spring` |
| `ai.conversation` | 在普通对话流中启用 ADB 工具调用，并把工具事件转为 SSE 事件 | `AiToolRegistry`、`AiMcpToolEventPublisher` |
| `ai.analysis` | 日志分析改为通过 MCP 工具 Facade 读取日志上下文，旧日志分析接口保留兼容 | `AdbMcpToolService` 内部 Facade |
| `DevBridge-Front/src/app/ai` | 展示工具调用状态、确认卡片、结果摘要和取消按钮 | 现有 AI API 封装 |

模块关系：

```text
AI Chat / Log Analysis
  └─ AiToolRegistry
      └─ SpringAiAdbToolCallbackAdapter
          └─ AdbMcpToolService
              ├─ AdbToolCatalog
              ├─ AdbRiskClassifier
              ├─ AdbConfirmationService
              ├─ AdbCommandExecutor
              ├─ AdbOutputSanitizer
              └─ AdbToolAuditRecorder

Front AI Panel
  └─ aiApi
      └─ AiMcpController
          └─ AdbMcpToolService

Future Standard MCP Server
  └─ StandardMcpServerAdapter
      └─ AdbMcpToolService
```

设计原则：

- 内部 MCP 工具核心只处理 `toolName + JSON arguments + context -> result`，不依赖 Spring AI、React 或页面状态。
- Spring AI `ToolCallback` 只负责把模型工具调用入参转为内部请求，把内部结果序列化回模型。
- REST 入口只负责前端确认、调试和后续标准 MCP 兼容验证，不承载 ADB 业务逻辑。
- 所有 ADB 执行必须使用参数数组。即使用户输入任意 shell 字符串，也只能作为 `adb shell` 后续参数传入，不经过宿主机 shell。

**ADB 工具目录设计**

工具目录按 ADB 顶层命令域拆分为可读工具，避免单个万能工具让模型难以选择；同时保留 `adb_raw` 覆盖无法精确建模的官方顶层参数组合。`adb_shell` 支持任意 shell command，是 P0 工具。

| 工具名 | 覆盖能力 | 默认风险 | 是否需要设备 |
|--------|----------|----------|--------------|
| `adb_devices` | `devices [-l]` | `LOW` | 否 |
| `adb_help` | `help`、指定命令帮助 | `LOW` | 否 |
| `adb_version` | `version` | `LOW` | 否 |
| `adb_network` | `connect`、`disconnect`、`pair`、`mdns check/services` | 动态 | 否 |
| `adb_forward` | `forward --list`、`forward`、`forward --remove`、`forward --remove-all` | 动态 | 部分需要 |
| `adb_reverse` | `reverse --list`、`reverse`、`reverse --remove`、`reverse --remove-all` | 动态 | 是 |
| `adb_file_transfer` | `push`、`pull`、`sync` 及压缩、保留时间戳等参数 | 动态 | 是 |
| `adb_shell` | `shell [-e ESCAPE] [-n] [-Tt] [-x] [COMMAND...]` | 动态 | 是 |
| `adb_emu` | `emu COMMAND` | `MEDIUM` | 是 |
| `adb_app_install` | `install`、`install-multiple`、`install-multi-package` | `HIGH` | 是 |
| `adb_app_uninstall` | `uninstall` | `HIGH` | 是 |
| `adb_debugging` | `bugreport`、`jdwp`、`logcat` | 动态 | 部分需要 |
| `adb_security` | `disable-verity`、`enable-verity`、`keygen` | 动态 | 部分需要 |
| `adb_scripting` | `wait-for-*`、`get-state`、`get-serialno`、`get-devpath` | `LOW` | 部分需要 |
| `adb_device_control` | `remount`、`reboot`、`sideload`、`root`、`unroot`、`usb`、`tcpip` | `HIGH` | 是 |
| `adb_server` | `start-server`、`kill-server`、`reconnect`、`reconnect device/offline` | 动态 | 否 |
| `adb_usb` | `attach`、`detach` | `MEDIUM` | 否 |
| `adb_raw` | 受控顶层 ADB 参数数组，覆盖 global options 和少见组合 | 动态 | 动态 |

`adb_raw` 的边界：

- 只允许 ADB help 顶层命令和 global options 组合。
- 不允许传入 adb 可执行文件路径。
- 不允许传入宿主机 shell 元字符作为宿主命令执行依据。
- 仍执行敏感操作识别、设备校验、确认、脱敏、审计和超时。

**数据模型变更**

不新增数据库表。第一阶段确认令牌和运行中工具调用使用内存存储，符合本地桌面工具单进程形态；应用重启后令牌和运行中任务全部失效。审计第一阶段写入应用日志，后续观测看板需要时再扩展为本地文件或 SQLite。

| 操作 | 表/实体 | 字段 | 类型 | 约束 | 说明 |
|------|---------|------|------|------|------|
| 新增 | `AdbMcpToolDefinition` | `name` | `String` | NOT BLANK，唯一 | MCP 工具名 |
| 新增 | `AdbMcpToolDefinition` | `description` | `String` | NOT BLANK | 给模型和前端展示的工具说明 |
| 新增 | `AdbMcpToolDefinition` | `inputSchema` | `JsonNode` | NOT NULL | 与 MCP Tool inputSchema 对齐 |
| 新增 | `AdbMcpToolDefinition` | `outputSchema` | `JsonNode` | NOT NULL | 与统一结果结构对齐 |
| 新增 | `AdbMcpToolDefinition` | `defaultRiskLevel` | `AdbRiskLevel` | NOT NULL | 工具默认风险 |
| 新增 | `AdbMcpToolDefinition` | `timeout` | `Duration` | NOT NULL | 工具超时上限 |
| 新增 | `AdbMcpToolDefinition` | `outputLimit` | `AdbOutputLimit` | NOT NULL | stdout/stderr 行数和字符数限制 |
| 新增 | `AdbMcpToolRequest` | `toolName` | `String` | NOT BLANK | 被调用工具 |
| 新增 | `AdbMcpToolRequest` | `conversationId` | `String` | NOT BLANK | 绑定对话和确认令牌 |
| 新增 | `AdbMcpToolRequest` | `deviceSerial` | `String` | 可空 | 目标设备序列号 |
| 新增 | `AdbMcpToolRequest` | `arguments` | `Map<String, Object>` | NOT NULL | 工具入参 |
| 新增 | `AdbMcpToolRequest` | `confirmationToken` | `String` | 可空 | 敏感操作二次调用时携带 |
| 新增 | `AdbMcpToolRequest` | `requestId` | `String` | NOT BLANK | 取消和审计关联 ID |
| 新增 | `AdbMcpToolResult` | `status` | `AdbToolStatus` | NOT NULL | `SUCCESS/FAILED/CONFIRMATION_REQUIRED/CANCELED` |
| 新增 | `AdbMcpToolResult` | `stdout`、`stderr` | `String` | 脱敏后 | 输出文本 |
| 新增 | `AdbMcpToolResult` | `exitCode` | `Integer` | 可空 | 未执行时为空 |
| 新增 | `AdbMcpToolResult` | `timedOut` | `boolean` | NOT NULL | 是否超时 |
| 新增 | `AdbMcpToolResult` | `durationMillis` | `long` | NOT NULL | 执行耗时 |
| 新增 | `AdbMcpToolResult` | `truncated` | `boolean` | NOT NULL | 输出是否截断 |
| 新增 | `AdbMcpToolResult` | `riskLevel` | `AdbRiskLevel` | NOT NULL | 实际风险 |
| 新增 | `AdbMcpToolResult` | `confirmationRequired` | `boolean` | NOT NULL | 是否需要确认 |
| 新增 | `AdbMcpToolResult` | `confirmationToken` | `String` | 可空 | 确认令牌 |
| 新增 | `AdbMcpToolResult` | `message` | `String` | NOT NULL | 用户可读摘要 |
| 新增 | `AdbMcpToolResult` | `errorCode` | `String` | 可空 | 稳定错误码 |
| 新增 | `AdbConfirmationEntry` | `tokenHash` | `String` | NOT BLANK，唯一 | 不保存令牌明文 |
| 新增 | `AdbConfirmationEntry` | `conversationId` | `String` | NOT BLANK | 对话绑定 |
| 新增 | `AdbConfirmationEntry` | `deviceSerialHash` | `String` | NOT BLANK | 设备绑定，审计不存完整序列号 |
| 新增 | `AdbConfirmationEntry` | `adbArgsHash` | `String` | NOT BLANK | 完整 ADB 参数数组绑定 |
| 新增 | `AdbConfirmationEntry` | `riskLevel` | `AdbRiskLevel` | NOT NULL | 风险绑定 |
| 新增 | `AdbConfirmationEntry` | `expiresAt` | `Instant` | NOT NULL | 过期时间 |
| 新增 | `AdbConfirmationEntry` | `used` | `boolean` | NOT NULL | 一次性消费 |
| 新增 | `AdbRunningToolCall` | `requestId` | `String` | NOT BLANK，唯一 | 运行中工具调用 |
| 新增 | `AdbRunningToolCall` | `processId` | `String` | 可空 | 长进程句柄 ID |
| 新增 | `AdbRunningToolCall` | `cancelHandle` | `Runnable` | NOT NULL | 取消回调 |

枚举：

| 枚举 | 值 |
|------|----|
| `AdbRiskLevel` | `LOW`、`MEDIUM`、`HIGH`、`CRITICAL` |
| `AdbToolStatus` | `SUCCESS`、`FAILED`、`CONFIRMATION_REQUIRED`、`CANCELED` |
| `AdbConfirmationStatus` | `PENDING`、`APPROVED`、`CANCELED`、`EXPIRED`、`USED`、`MISMATCH` |

**接口定义**

外部 REST/SSE 接口：

| 接口 | 方法 | 路径/签名 | 入参 | 出参 | 说明 |
|------|------|----------|------|------|------|
| ADB MCP 工具目录 | GET | `/api/ai/mcp/adb/tools` | 无 | `List<AdbMcpToolDefinition>` | 前端展示和调试使用；与 ADB version 绑定 |
| ADB MCP 工具调用 | POST | `/api/ai/mcp/adb/tools/call` | `AdbMcpToolRequest` | `AdbMcpToolResult` | 非流式工具调用，适合短命令和确认后执行 |
| ADB MCP 工具流式调用 | POST | `/api/ai/mcp/adb/tools/call/stream` | `AdbMcpToolRequest` | `SseEmitter` | 长命令输出工具事件，事件名见下表 |
| 确认敏感操作 | POST | `/api/ai/mcp/adb/confirmations/{token}/approve` | `AdbConfirmationDecisionRequest` | `AdbMcpToolResult` | 用户确认后执行令牌绑定命令 |
| 取消敏感操作 | POST | `/api/ai/mcp/adb/confirmations/{token}/cancel` | `AdbConfirmationDecisionRequest` | `AdbMcpToolResult` | 令牌失效，不执行命令 |
| 取消运行中工具 | POST | `/api/ai/mcp/adb/tools/running/{requestId}/cancel` | 无 | `AdbMcpToolResult` | 用户取消 AI 回复或关闭侧边栏时调用 |

工具 SSE 事件：

| 事件名 | 数据类型 | 说明 |
|--------|----------|------|
| `tool-start` | `AdbToolProgressEvent` | 工具开始、工具名、风险级别 |
| `tool-output` | `AdbToolOutputEvent` | 脱敏后的增量 stdout/stderr |
| `tool-confirmation` | `AdbMcpToolResult` | 需要用户确认 |
| `tool-result` | `AdbMcpToolResult` | 工具完成 |
| `tool-error` | `AdbMcpToolResult` | 稳定错误 |

内部关键 public 方法签名：

| 类 | 方法签名 | 说明 |
|----|----------|------|
| `AdbToolCatalog` | `List<AdbMcpToolDefinition> listTools()` | 返回完整工具目录 |
| `AdbToolCatalog` | `AdbMcpToolDefinition requireTool(String toolName)` | 获取工具定义，不存在抛稳定错误 |
| `AdbMcpToolService` | `AdbMcpToolResult call(AdbMcpToolRequest request)` | 内部统一工具调用入口 |
| `AdbMcpToolService` | `SseEmitter streamCall(AdbMcpToolRequest request)` | 内部长命令流式调用入口 |
| `AdbMcpToolService` | `AdbMcpToolResult cancel(String requestId)` | 取消运行中工具 |
| `AdbCommandPlanner` | `AdbCommandPlan plan(AdbMcpToolRequest request, AdbMcpToolDefinition definition)` | 将工具入参转换为 ADB 参数数组 |
| `AdbCommandExecutor` | `AdbMcpToolResult execute(AdbCommandPlan plan, AdbMcpExecutionContext context)` | 执行短命令 |
| `AdbCommandExecutor` | `SseEmitter executeStreaming(AdbCommandPlan plan, AdbMcpExecutionContext context)` | 执行长命令 |
| `AdbDeviceValidator` | `void validate(AdbCommandPlan plan)` | 校验目标设备存在且 connected |
| `AdbRiskClassifier` | `AdbRiskAssessment assess(AdbCommandPlan plan)` | 识别敏感操作和风险 |
| `AdbConfirmationService` | `AdbConfirmationChallenge create(AdbConfirmationRequest request)` | 创建确认挑战 |
| `AdbConfirmationService` | `void verifyAndConsume(String token, AdbConfirmationCheck check)` | 校验并消费令牌 |
| `AdbConfirmationService` | `void cancel(String token, String conversationId)` | 取消令牌 |
| `AdbOutputSanitizer` | `AdbSanitizedOutput sanitize(CommandResult result, AdbOutputLimit limit)` | 脱敏并截断命令输出 |
| `AdbToolAuditRecorder` | `void record(AdbToolAuditEvent event)` | 记录审计摘要 |
| `SpringAiAdbToolCallbackAdapter` | `List<ToolCallback> callbacks(AiToolScope scope)` | 生成 Spring AI 工具回调 |
| `AiToolRegistry` | `List<ToolCallback> toolCallbacks(AiToolScope scope)` | 注入 ADB MCP 工具到 Spring AI |
| `AiConversationService` | `SseEmitter streamChat(AiChatRequest request)` | 增加工具事件透传，不改变原有文本 chunk 事件 |
| `AiLogAnalysisService` | `AiLogAnalysisResponse analyze(AiLogAnalysisRequest request)` | 保留兼容入口，内部通过 MCP 工具取数 |

Spring AI 适配约定：

- `ToolCallback.getToolDefinition()` 的 name、description、inputSchema 来自 `AdbToolCatalog`。
- `ToolCallback.call(String toolInput, ToolContext toolContext)` 解析 JSON 后调用 `AdbMcpToolService.call`。
- `ToolContext` 必须携带 `conversationId`、`requestId`、当前设备上下文和取消上下文。
- 工具返回 JSON 必须是 `AdbMcpToolResult`，不得返回未脱敏原始输出。

标准 MCP 兼容约定：

- `AdbMcpToolDefinition.inputSchema` 与 MCP Tool `inputSchema` 对齐。
- `AdbMcpToolResult` 可映射为 MCP `structuredContent`。
- 未来增加 `tools/list` 和 `tools/call` 协议适配时，仅新增 `StandardMcpServerAdapter`，复用 `AdbMcpToolService`。

**错误处理策略**

| 错误类型 | 处理方式 | HTTP状态码/异常类 |
|---------|---------|----------------|
| ADB 工具不存在 | 不执行命令，返回 `ADB_TOOL_NOT_FOUND` | 409 / `BusinessException` |
| 设备不存在 | 返回当前可用设备摘要，返回 `ADB_DEVICE_NOT_FOUND` | 404 / `BusinessException` |
| 设备 unauthorized | 返回授权提示，返回 `ADB_DEVICE_UNAUTHORIZED` | 409 / `BusinessException` |
| 设备 offline | 返回重插或 reconnect 提示，返回 `ADB_DEVICE_OFFLINE` | 409 / `BusinessException` |
| 命令参数为空 | 返回 `ADB_COMMAND_EMPTY` | 400 / `BusinessException` |
| 顶层命令不支持 | 返回 `ADB_COMMAND_UNSUPPORTED` 和支持域摘要 | 400 / `BusinessException` |
| shell 命令为空 | 返回 `ADB_SHELL_COMMAND_EMPTY` | 400 / `BusinessException` |
| 敏感命令未确认 | 不执行真实命令，返回 `confirmationRequired=true` | 200 / `AdbMcpToolResult` |
| 确认令牌过期 | 拒绝执行，返回 `ADB_CONFIRMATION_EXPIRED` | 409 / `BusinessException` |
| 确认令牌不匹配 | 拒绝执行，返回 `ADB_CONFIRMATION_MISMATCH` | 409 / `BusinessException` |
| 确认令牌重复使用 | 拒绝执行，返回 `ADB_CONFIRMATION_USED` | 409 / `BusinessException` |
| 用户取消确认 | 令牌失效，返回 `CANCELED` | 200 / `AdbMcpToolResult` |
| 命令超时 | 终止进程，返回 `timedOut=true`、`exitCode=124` | 200 / `AdbMcpToolResult` |
| 运行中工具取消 | 停止进程或标记取消，返回 `CANCELED` | 200 / `AdbMcpToolResult` |
| 输出超限 | 截断并脱敏，返回 `truncated=true` | 200 / `AdbMcpToolResult` |
| ADB 非零退出码 | 返回 `ADB_COMMAND_FAILED`、exitCode、stderr 摘要 | 200 / `AdbMcpToolResult` |
| 工具内部异常 | 脱敏 detail 后返回稳定错误 | 500 / `BusinessException` |

#### 关键决策与理由

| 决策 | 可选方案 | 选择 | 理由 |
|------|---------|------|------|
| MCP 核心位置 | A: 写在 Spring AI ToolCallback / B: 写在现有设备 Service / C: 新增独立 `ai.mcp` 核心 | C | 用户要求解耦和最小侵入；独立核心可同时服务 Spring AI、REST 调试和未来标准 MCP Server |
| Spring AI 使用方式 | A: 仅手写 REST 调模型 / B: Spring AI Tool Calling 适配内部 MCP / C: 直接依赖标准 MCP Server | B | 项目已引入 Spring AI 1.0.9，后续需要工具调用、RAG、Agent 和观测；先用 ToolCallback 落地，同时不让业务绑定 Spring AI API |
| ADB 覆盖策略 | A: 每个 adb 子命令一个工具 / B: 少量大工具按命令域覆盖 + `adb_raw` 兜底 / C: 只有一个万能工具 | B | 覆盖完整 ADB 顶层能力，同时避免工具数量过多；模型选择更稳定，仍满足全能力覆盖 |
| 任意 shell | A: 禁止危险 shell / B: 放任 shell 但敏感确认 / C: 只允许白名单命令 | B | 用户明确要求 `adb shell` 能直接使用；通过风险识别、确认令牌、审计和脱敏控制安全边界 |
| 命令执行 | A: 拼接 shell 字符串 / B: 参数数组执行 / C: 前端执行 adb | B | 现有 `CommandRunner` 已按参数数组防宿主机命令注入；前端执行违反 spec |
| 确认令牌存储 | A: 内存一次性令牌 / B: 数据库持久化 / C: 前端保存令牌明文状态 | A | 本地桌面单进程第一阶段足够；应用重启令牌失效更安全；无需引入数据库 |
| 审计存储 | A: 应用日志摘要 / B: 新建数据库表 / C: 记录完整命令输出 | A | 满足 P0 审计摘要；不记录完整日志和敏感数据；观测看板后续再扩展持久化 |
| 日志分析迁移 | A: 删除旧接口 / B: 保留旧接口但内部走 MCP / C: 维持单接口实现 | B | 保持前端兼容和低风险迁移，同时满足“不作为唯一实现路径” |
| 前端确认 | A: 浏览器 confirm / B: AI 侧边栏确认卡片 / C: 主界面弹窗 | B | 确认是 AI 对话上下文的一部分；不会干扰设备页、文件页和日志页状态 |
| 长任务取消 | A: 只前端 abort / B: 后端运行表 + StreamingProcess stop / C: 不支持取消 | B | spec 要求关闭侧边栏或取消回复时终止工具调用；仅 abort 不能保证 adb 进程退出 |

#### 风险与权衡

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| ADB help 后续版本新增命令导致目录不完整 | 中 | 中 | 工具目录绑定 ADB version；新增 `adb_raw` 兜底；单测校验当前内置 help 顶层命令覆盖 |
| shell 敏感识别无法覆盖所有危险组合 | 中 | 高 | 覆盖 spec 列出的 18 类；对写敏感路径、`su`、`mount`、`setenforce`、`dd of=` 等按高风险处理；审计所有 shell 调用 |
| 模型重复或错误使用确认令牌 | 中 | 高 | 令牌绑定会话、设备、完整参数 hash、风险、过期时间，一次性消费 |
| 长命令输出过大拖垮内存 | 中 | 中 | 输出按行数和字符数限制；流式命令只保留摘要；进入 Prompt 前必须截断和脱敏 |
| ADB server 操作影响现有页面设备状态 | 中 | 中 | `kill-server`、`tcpip`、`usb`、全量 `disconnect` 需要确认；结果只通过 AI 面板展示，不主动重置页面状态 |
| Tool Calling 与流式文本事件混合导致前端状态复杂 | 中 | 中 | 后端定义固定 SSE 事件类型；前端消息和工具事件分离渲染 |
| 第一阶段无标准 MCP Server 进程 | 低 | 中 | 内部契约按 MCP Tool schema 设计；后续只新增协议适配层，不改执行核心 |
| Windows/macOS ADB 行为差异 | 中 | 中 | 复用 `ExecutableLocator` 定位内置工具；命令组装全用参数数组；测试覆盖 darwin/windows 路径拼装 |

**发布策略**：

- **发布方式**：分阶段合入。先后端目录、执行、风险、确认和测试，再接入 Spring AI，最后接前端确认交互。
- **灰度开关**：新增配置 `devbridge.ai.mcp-adb.enabled`，默认开发态开启；若关闭，AI 工具注册表不返回 ADB 工具，REST 工具调用返回 `AI_MCP_ADB_DISABLED`。
- **回滚条件**：现有设备列表、文件、应用、日志接口出现回归；AI 普通对话不可用；ADB 敏感操作绕过确认。
- **数据迁移**：无数据库迁移。内存令牌和运行中工具调用在应用重启后自动失效。

#### 变更文件清单

| 文件路径 | 操作 | 变更说明 |
|---------|------|---------|
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/model/*` | 新增 | ADB MCP 请求、结果、工具定义、风险、确认、审计模型 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/catalog/*` | 新增 | ADB 工具目录、Schema、ADB version/help 覆盖校验 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/execution/*` | 新增 | ADB 参数规划、设备校验、短命令/长命令执行、取消管理 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/risk/*` | 新增 | 敏感操作识别和风险说明 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/confirmation/*` | 新增 | 一次性确认令牌生成、校验、消费和取消 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/security/*` | 新增 | ADB 工具输出限制和脱敏 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/audit/*` | 新增 | ADB MCP 工具调用审计摘要 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/adapter/spring/*` | 新增 | Spring AI ToolCallback 适配 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/adapter/rest/*` | 新增 | 工具目录、调用、确认、取消 REST/SSE 接口 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/tool/AiToolRegistry.java` | 修改 | 根据 `AiToolScope` 注册 ADB MCP Spring AI 工具 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/tool/AiToolScope.java` | 修改 | 新增 `ADB_DEVICE_MANAGEMENT` 工具范围 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/conversation/*` | 修改 | 流式对话启用工具调用并透传工具事件 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/analysis/*` | 修改 | 日志分析兼容入口内部改为 MCP 工具取数 |
| `DevBridge-Server/src/main/java/com/devbridge/server/config/DevBridgeProperties.java` | 修改 | 新增 MCP ADB 开关、默认超时和输出限制配置 |
| `DevBridge-Server/src/main/resources/application.yml` | 修改 | 新增 `devbridge.ai.mcp-adb` 配置默认值 |
| `DevBridge-Server/src/test/java/com/devbridge/server/ai/mcp/**` | 新增 | 工具目录、命令规划、风险识别、确认、脱敏、取消测试 |
| `DevBridge-Front/src/app/ai/aiTypes.ts` | 修改 | 新增工具事件、工具结果、确认卡片类型 |
| `DevBridge-Front/src/app/ai/aiApi.ts` | 修改 | 新增 MCP 工具目录、确认、取消和工具事件流封装 |
| `DevBridge-Front/src/app/ai/AiChatPanel.tsx` | 修改 | 展示工具调用状态、确认卡片、取消运行中工具 |
| `DevBridge-Front/src/app/ai/AiToolCallCard.tsx` | 新增 | 展示工具调用状态、参数摘要和结果摘要，不修改主业务页面 |
| `DevBridge-Front/src/app/ai/AiConfirmationCard.tsx` | 新增 | 展示敏感操作确认、取消和有效期，不修改主业务页面 |

### How

#### 任务拆分

| 任务名称 | 详细描述 | 关联设计章节 | 计划工作量(人天) |
|----------|---------|------------|--------------|
| 【工具模型】(后端) 定义 ADB MCP 统一模型 | 1. 定义工具定义、请求、结果、风险、错误码、输出限制模型<br>2. 定义确认令牌和运行中调用模型<br>3. 保证结果字段包含 spec 要求的统一结构<br>4. 覆盖序列化单测 | 数据模型变更 / 接口定义 | 1 |
| 【工具目录】(后端) 实现 ADB MCP 工具目录 | 1. 建立内置 ADB version/help 基准<br>2. 按命令域定义工具清单和 JSON Schema<br>3. 覆盖 global options、general、networking、file transfer、shell、app installation、debugging、security、scripting、device control、internal debugging、usb、environment context<br>4. 单测校验 help 顶层命令均有工具或 `adb_raw` 覆盖 | ADB 工具目录设计 | 1.5 |
| 【命令规划】(后端) 实现工具入参到 ADB 参数数组规划 | 1. 为各工具生成参数数组<br>2. 支持任意 `adb shell COMMAND...`<br>3. 支持 global options 和环境上下文白名单<br>4. 覆盖命令组装和非法顶层命令测试 | 接口定义 / 架构决策 | 2 |
| 【设备校验】(后端) 实现目标设备状态校验 | 1. 复用现有设备枚举能力<br>2. 对需要设备的工具校验 serial 和 connected 状态<br>3. 映射 offline、unauthorized、not found 稳定错误<br>4. 覆盖设备异常分支测试 | 错误处理策略 | 1 |
| 【敏感识别】(后端) 实现 ADB 风险分类 | 1. 覆盖 spec 18 类敏感操作<br>2. 识别顶层命令和 shell 命令风险<br>3. 生成风险说明和影响范围<br>4. 覆盖删除、卸载、清数据、安装、push/sync、root、server、敏感路径测试 | ADB 工具目录设计 / 风险与权衡 | 2 |
| 【确认机制】(后端) 实现一次性确认令牌 | 1. 令牌绑定会话、设备、完整 ADB 参数、风险和过期时间<br>2. 校验过期、重复使用、参数不匹配、取消<br>3. 令牌只保存 hash，不记录明文<br>4. 覆盖确认流程和安全失败测试 | 数据模型变更 / 错误处理策略 | 1.5 |
| 【输出安全】(后端) 实现输出截断、脱敏和审计 | 1. 限制 stdout/stderr 行数与字符数<br>2. 脱敏 Authorization、token、api_key、password、邮箱、手机号、设备序列号、密钥字段<br>3. 记录工具调用审计摘要<br>4. 覆盖脱敏、截断和审计测试 | 数据模型变更 / 风险与权衡 | 1.5 |
| 【执行取消】(后端) 实现工具执行和取消管理 | 1. 短命令复用 `CommandRunner`<br>2. 长命令复用 `StreamingCommandRunner` 并登记取消句柄<br>3. 超时和用户取消都能停止进程或标记取消<br>4. 覆盖 logcat、bugreport、push/pull 超时取消测试 | 架构决策 / 错误处理策略 | 2 |
| 【Spring适配】(后端) 接入 Spring AI ToolCallback | 1. 从工具目录生成 `ToolCallback`<br>2. `AiToolRegistry` 按范围返回 ADB MCP 工具<br>3. 工具上下文携带 conversationId、device、requestId<br>4. 覆盖工具回调入参解析和结果序列化测试 | 接口定义 / 关键决策与理由 | 1.5 |
| 【REST接口】(后端) 实现工具目录、调用、确认、取消接口 | 1. 新增 ADB MCP Controller<br>2. 提供工具目录、非流式调用、流式调用、确认、取消接口<br>3. 复用统一错误模型<br>4. 覆盖接口契约测试 | 接口定义 | 1.5 |
| 【AI编排】(后端) 将对话和日志分析接入 MCP 工具 | 1. 普通对话启用 ADB MCP 工具调用<br>2. 日志分析兼容入口内部通过 MCP 获取日志上下文<br>3. 工具事件通过 SSE 透传给前端<br>4. 旧日志分析接口保持响应结构兼容 | 架构决策 / 接口定义 | 2 |
| 【前端类型】(前端) 扩展 AI 工具事件和 API 封装 | 1. 新增工具定义、工具结果、确认请求、工具事件类型<br>2. 封装工具目录、确认、取消接口<br>3. 扩展流式对话事件解析<br>4. 覆盖前端纯函数解析测试 | 接口定义 / 变更文件清单 | 1 |
| 【前端确认】(前端) 实现工具状态和确认卡片 | 1. 侧边栏展示工具名、状态、参数摘要、结果摘要<br>2. 敏感操作确认卡片展示命令、设备、风险、影响、有效期<br>3. 支持确认、取消和关闭侧边栏取消运行中工具<br>4. 不修改主界面页签、日志、文件、应用状态 | 接口定义 / 关键决策与理由 | 1.5 |
| 【兼容回归】(全栈) 完成 ADB MCP 全能力验收 | 1. 验证全部 ADB 顶层命令域覆盖<br>2. 验证任意 shell 和敏感确认<br>3. 验证输出脱敏、截断、审计和取消<br>4. 验证现有设备、文件、应用、日志接口无回归 | 发布策略 / 风险与权衡 | 2 |
| **合计** | | | **23** |

**任务排序原则**：先模型和目录，再命令规划、设备校验、风险和确认，再执行安全与取消，再 Spring AI/REST 适配，最后前端和联调验收。

**任务依赖**：

```text
- 【工具模型】
- 【工具目录】 ← depends: 【工具模型】
- 【命令规划】 ← depends: 【工具目录】
- 【设备校验】 ← depends: 【命令规划】
- 【敏感识别】 ← depends: 【命令规划】
- 【确认机制】 ← depends: 【敏感识别】
- 【输出安全】 ← depends: 【工具模型】
- 【执行取消】 ← depends: 【设备校验】, 【确认机制】, 【输出安全】
- 【Spring适配】 ← depends: 【执行取消】
- 【REST接口】 ← depends: 【执行取消】
- 【AI编排】 ← depends: 【Spring适配】, 【REST接口】
- 【前端类型】 ← depends: 【REST接口】
- 【前端确认】 ← depends: 【前端类型】, 【AI编排】
- 【兼容回归】 ← depends: all
```

### Verify

```
设计自检：
- [x] 所有 spec 功能需求都有对应的技术方案
- [x] 所有技术决策都有理由
- [x] 接口定义完整（入参、出参、异常）
- [x] 数据模型变更明确（新增/修改/删除）
- [x] 任务拆分覆盖全部设计内容
- [x] 任务总量与需求规模匹配
- [x] 无实现代码（只有签名和结构）
- [x] 已按现有项目风格、AGENTS 约束和 design 模板检查 Risks/Rollout
```

补充检查：

- [x] `adb shell COMMAND...` 可执行能力已设计，且不经过宿主机 shell。
- [x] 敏感操作不是禁止，而是通过会话确认令牌二次执行。
- [x] 确认令牌绑定会话、设备、完整 ADB 参数、风险、过期时间和一次性消费。
- [x] AI/MCP 核心不依赖 `App.tsx` 或现有页面主体。
- [x] 删除或关闭 AI/MCP 模块后，现有设备、文件、应用、日志 REST 接口契约不变。
- [x] 第一阶段 Spring AI Tool Calling 与未来标准 MCP Server 传输适配解耦。

### Impact

- `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/**`：新增 ADB MCP 工具核心、执行、安全、确认、审计和适配层。
- `DevBridge-Server/src/main/java/com/devbridge/server/ai/tool/**`：扩展工具注册范围，向 Spring AI 暴露 ADB MCP 工具。
- `DevBridge-Server/src/main/java/com/devbridge/server/ai/conversation/**`：流式对话增加工具调用事件透传。
- `DevBridge-Server/src/main/java/com/devbridge/server/ai/analysis/**`：日志分析兼容入口内部改为 MCP 工具路径取数。
- `DevBridge-Server/src/main/java/com/devbridge/server/command/**`：复用现有命令执行器；只新增 MCP 长任务取消登记所需的最小扩展。
- `DevBridge-Server/src/main/java/com/devbridge/server/service/**`：复用 `ExecutableLocator`、`DeviceService`、`AndroidDeviceService`，不改变现有 REST 响应结构。
- `DevBridge-Front/src/app/ai/**`：扩展工具事件、确认卡片和取消交互。
- `DevBridge-Front/src/app/App.tsx`：不新增 MCP 逻辑；仅传递只读设备上下文和关闭/取消回调。
- 数据库变更：无。
- 外部依赖变更：无新增 AI 框架依赖；继续使用已引入的 Spring AI 1.0.9。
