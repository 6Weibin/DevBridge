# Local Shell MCP 技术设计

> 来源：用户需求“当前 AI 助手也有操作本地电脑的能力，需要单独设计一套 Local Shell MCP 能力”
> 生成时间：2026-07-08
> 阶段：design

## 1. 背景与现状

DevBridge 当前 AI 助手已经具备 Spring AI 对话、流式回复、Markdown 渲染、历史会话、ADB MCP 工具调用、敏感 ADB 操作确认、工具执行过程展示和审计摘要能力。后端 AI 工具注册入口为 `AiToolRegistry`，当前只注册 `SpringAiAdbToolCallbackAdapter`；ADB MCP 核心位于 `com.devbridge.server.ai.mcp`，已经形成工具目录、命令规划、风险识别、确认令牌、执行器、输出脱敏、审计、REST 调试和 Spring AI ToolCallback 适配的完整链路。

本需求新增“AI 操作本地电脑”的能力。该能力与 ADB MCP 的目标不同：ADB MCP 操作移动设备；Local Shell MCP 操作宿主机本身，风险更高，必须独立建模，不能简单把宿主机 shell 字符串透传给模型执行。

## 2. 设计目标与非目标

### 2.1 目标

| 目标 | 说明 |
| --- | --- |
| 本机命令执行 | AI 可通过 MCP 工具执行本地电脑命令，支持 macOS、Windows、Linux。 |
| 受控工作目录 | 命令必须指定或默认落在允许的工作目录，禁止默认从系统根目录任意执行。 |
| 风险识别 | 对删除、覆盖、移动、权限变更、进程终止、网络下载执行、包管理、系统配置、环境变量泄露等命令识别风险。 |
| 对话确认 | 高风险和破坏性命令首次调用不执行，必须在 AI 对话中展示确认卡片，用户确认后才能执行绑定命令。 |
| 输出安全 | stdout/stderr 必须限长、脱敏、可截断，避免密钥、token、账号信息、路径隐私直接进入模型上下文。 |
| 审计与可取消 | 每次调用记录审计摘要；长命令可取消；超时自动终止进程。 |
| 与 ADB 解耦 | Local Shell MCP 独立包和独立配置，不侵入现有设备、文件、应用、日志功能。 |
| Spring AI 与标准 MCP 兼容 | 第一阶段接入 Spring AI ToolCallback，工具 Schema 保持可迁移到标准 MCP Server。 |

### 2.2 非目标

| 非目标 | 说明 |
| --- | --- |
| 不做完整远程控制终端 | 第一阶段不实现全功能交互式 TTY，不做 curses/vim/top 这类交互程序自动操作。 |
| 不绕过用户确认 | 高风险命令不能通过 prompt 或工具参数绕过确认。 |
| 不直接开放任意文件读写 API | 文件操作通过 shell 命令能力完成，仍受风险识别和工作目录边界约束。 |
| 不复用 ADB 包模型命名 | 不把 Local Shell 做成 `Adb*` 类型的变体，避免语义污染。 |
| 不把命令执行逻辑写进前端 | 前端只展示工具过程和确认交互，不执行命令。 |

## 3. 总体架构

### 3.1 包结构

| 包 | 职责 |
| --- | --- |
| `com.devbridge.server.ai.mcp.common` | 抽取少量通用枚举和接口：风险级别、工具状态、通用确认状态、工具事件发布。避免大规模重构现有 ADB。 |
| `com.devbridge.server.ai.localshell.catalog` | Local Shell 工具目录和 JSON Schema 定义。 |
| `com.devbridge.server.ai.localshell.model` | 请求、结果、计划、风险评估、确认挑战、审计事件、运行中进程等模型。 |
| `com.devbridge.server.ai.localshell.policy` | 工作目录策略、环境变量策略、命令风险识别、命令解析和敏感参数判断。 |
| `com.devbridge.server.ai.localshell.confirmation` | 一次性确认令牌生成、绑定、校验、消费和取消。 |
| `com.devbridge.server.ai.localshell.execution` | 命令执行、流式读取、超时终止、取消运行中进程。 |
| `com.devbridge.server.ai.localshell.security` | 输出脱敏、输出截断、命令摘要脱敏。 |
| `com.devbridge.server.ai.localshell.audit` | Local Shell 工具调用审计摘要。 |
| `com.devbridge.server.ai.localshell.adapter.spring` | Spring AI ToolCallback 适配。 |
| `com.devbridge.server.ai.localshell.adapter.rest` | 前端确认、调试、取消使用的 REST/SSE 入口。 |

### 3.2 调用链

```text
AI Chat
  └─ AiToolRegistry
      ├─ SpringAiAdbToolCallbackAdapter
      └─ SpringAiLocalShellToolCallbackAdapter
          └─ LocalShellMcpToolService
              ├─ LocalShellToolCatalog
              ├─ LocalShellPolicyService
              ├─ LocalShellRiskClassifier
              ├─ LocalShellConfirmationService
              ├─ LocalShellCommandExecutor
              ├─ LocalShellOutputSanitizer
              └─ LocalShellAuditRecorder

Front AI Panel
  └─ aiApi
      ├─ ADB MCP REST
      └─ Local Shell MCP REST
```

## 4. 工具目录设计

### 4.1 P0 工具

| 工具名 | 能力 | 默认风险 | 说明 |
| --- | --- | --- | --- |
| `local_shell_exec` | 执行单条本机命令 | 动态 | P0 核心工具。支持 `commandLine` 或 `argv` 二选一。 |
| `local_shell_pwd` | 查询当前允许工作目录 | LOW | 只读，不需要确认。 |
| `local_shell_list_dir` | 列出允许目录内容 | LOW | 等价安全封装，降低模型滥用 `ls/find` 的概率。 |
| `local_shell_read_text` | 读取允许目录内小文本文件 | MEDIUM | 默认限大小和扩展名；敏感路径或大文件需要确认或拒绝。 |
| `local_shell_process_status` | 查询当前工具启动的运行中进程 | LOW | 只展示本应用管理的进程。 |
| `local_shell_cancel` | 取消当前运行中 shell 工具 | LOW | 只能取消本应用启动的进程。 |

### 4.2 P1 工具

| 工具名 | 能力 | 说明 |
| --- | --- | --- |
| `local_shell_write_text` | 写入允许目录内文本文件 | 高风险，P1 再做，必须确认。 |
| `local_shell_session_start` | 创建受控非交互 shell session | 支持连续上下文，但不做 TTY。 |
| `local_shell_session_write` | 向 session 写入命令 | 必须复用同一风险识别和确认机制。 |
| `local_shell_session_close` | 关闭 session | 释放进程资源。 |

第一阶段建议优先落地 `local_shell_exec`，其它 P0 工具作为更安全的语义化工具同时提供给模型，减少模型把所有事情都塞进通用命令。

## 5. 执行模式

### 5.1 `argv` 模式

请求提供：

```json
{
  "mode": "ARGV",
  "argv": ["git", "status", "--short"],
  "workingDirectory": "/Users/xxx/project",
  "timeoutMillis": 10000
}
```

后端使用 `ProcessBuilder(argv)` 执行，不经过系统 shell。该模式优先推荐给模型。

### 5.2 `commandLine` 模式

请求提供：

```json
{
  "mode": "SHELL",
  "commandLine": "git status --short | head -20",
  "workingDirectory": "/Users/xxx/project",
  "timeoutMillis": 10000
}
```

后端按平台选择受控 shell：

| 平台 | shell |
| --- | --- |
| macOS/Linux | `/bin/zsh -lc`，不存在则 `/bin/bash -lc`，再降级 `/bin/sh -lc` |
| Windows | `powershell.exe -NoProfile -NonInteractive -Command`，可配置降级 `cmd.exe /d /s /c` |

`commandLine` 模式风险更高：只读命令可直执；包含管道、重定向、命令替换、下载执行、文件写入、进程终止等行为时必须确认或拒绝。

## 6. 安全边界

### 6.1 配置开关

新增配置：

```yaml
devbridge:
  ai-mcp-local-shell:
    enabled: false
    confirmation-ttl: 2m
    default-timeout: 10s
    max-timeout: 120s
    max-output-chars: 60000
    max-output-lines: 1200
    allowed-working-directories:
      - ${user.home}
    denied-working-directories:
      - /
      - /System
      - /private/etc
      - /etc
      - /bin
      - /sbin
      - /usr/bin
      - C:\\Windows
      - C:\\Program Files
    allowed-environment-keys:
      - PATH
      - JAVA_HOME
      - ANDROID_HOME
    require-confirmation-for-shell-mode: true
```

建议默认 `enabled=false`，首次打开 AI 设置时由用户显式开启。原因：Local Shell 操作的是宿主机，风险显著高于 ADB 设备操作。开发阶段可在本地配置打开。

### 6.2 工作目录策略

| 规则 | 说明 |
| --- | --- |
| 必须归一化路径 | 使用 `Path.toRealPath()` 或可降级的规范化，防止 `..` 逃逸。 |
| 默认目录 | Electron 下默认使用用户选择的工作目录或应用运行目录；Web 独立后端默认使用项目根。 |
| 禁止根目录默认执行 | 未指定工作目录时不能默认为 `/` 或系统目录。 |
| 拒绝敏感系统目录写操作 | `/System`、`/etc`、`C:\Windows` 等系统目录中的写、删、改权限操作直接拒绝或必须最高级确认。 |
| 支持用户配置 | 允许用户在 AI 设置中配置允许目录列表。 |

### 6.3 环境变量策略

| 规则 | 说明 |
| --- | --- |
| 默认继承最小环境 | 只保留 `PATH`、语言、必要运行时变量。 |
| 禁止模型注入敏感环境变量 | `OPENAI_API_KEY`、`*_KEY`、`*_TOKEN`、`PASSWORD` 等不允许从请求传入。 |
| 输出脱敏 | stdout/stderr 中出现密钥模式必须脱敏。 |

### 6.4 风险级别

| 风险 | 是否确认 | 示例 |
| --- | --- | --- |
| LOW | 不确认 | `pwd`、`ls`、`git status`、`java -version`、`node -v` |
| MEDIUM | 可配置确认 | `cat` 读取普通文件、`find` 大范围扫描、`ps`、`lsof`、`du` |
| HIGH | 必须确认 | `rm`、`mv` 覆盖、`cp` 覆盖、`chmod`、`chown`、`kill`、`npm install`、`brew install`、`curl -o` |
| CRITICAL | 默认拒绝或强确认 | `sudo`、`su`、`rm -rf /`、格式化磁盘、修改系统安全策略、下载后直接执行脚本 |

### 6.5 敏感命令识别

| 类型 | 命中条件 | 处理 |
| --- | --- | --- |
| 删除 | `rm`、`rmdir`、`del`、`Remove-Item`、`find ... -delete` | HIGH/CRITICAL，必须确认。 |
| 覆盖写 | `>`、`>>`、`tee`、`cp -f`、`mv`、`Set-Content` | HIGH，必须确认。 |
| 权限/所有权 | `chmod`、`chown`、`icacls` | HIGH，必须确认。 |
| 进程控制 | `kill`、`pkill`、`taskkill`、`launchctl`、`systemctl` | HIGH/CRITICAL。 |
| 包管理 | `npm install`、`brew install`、`pip install`、`apt`、`winget` | HIGH，必须确认。 |
| 网络下载 | `curl`、`wget`、`Invoke-WebRequest` | MEDIUM；下载并执行为 CRITICAL。 |
| 密钥读取 | 读取 `.env`、`.ssh`、`id_rsa`、`keychain`、配置凭据目录 | CRITICAL，默认拒绝或强确认并脱敏。 |
| 系统提权 | `sudo`、`su`、`osascript` 控制系统、PowerShell 提权 | CRITICAL，默认拒绝。 |
| 复合 shell | 管道、重定向、命令替换、`;`、`&&`、`||` | 至少 MEDIUM；含写/删/下载执行则升级。 |

## 7. 数据模型

### 7.1 请求

| 类型 | 字段 | 说明 |
| --- | --- | --- |
| `LocalShellMcpToolRequest` | `toolName` | 工具名。 |
|  | `conversationId` | 对话 ID，绑定确认令牌。 |
|  | `requestId` | 工具调用 ID，支持取消和审计。 |
|  | `arguments` | JSON 参数。 |
|  | `confirmationToken` | 可空，敏感操作二次执行时携带。 |

### 7.2 命令参数

| 类型 | 字段 | 说明 |
| --- | --- | --- |
| `LocalShellExecArguments` | `mode` | `ARGV` 或 `SHELL`。 |
|  | `argv` | `ARGV` 模式参数数组。 |
|  | `commandLine` | `SHELL` 模式命令行。 |
|  | `workingDirectory` | 工作目录。 |
|  | `environment` | 受控环境变量。 |
|  | `timeoutMillis` | 超时，不能超过配置上限。 |
|  | `stdin` | P0 不支持；P1 再考虑。 |

### 7.3 结果

| 类型 | 字段 | 说明 |
| --- | --- | --- |
| `LocalShellMcpToolResult` | `status` | `SUCCESS/FAILED/CONFIRMATION_REQUIRED/CANCELED`。 |
|  | `stdout`、`stderr` | 脱敏和截断后的输出。 |
|  | `exitCode` | 退出码。 |
|  | `timedOut` | 是否超时。 |
|  | `durationMillis` | 执行耗时。 |
|  | `truncated` | 是否截断。 |
|  | `riskLevel` | 实际风险。 |
|  | `confirmationRequired` | 是否需要确认。 |
|  | `confirmationToken` | 待确认令牌。 |
|  | `message` | 用户可读摘要。 |
|  | `errorCode` | 稳定错误码。 |
|  | `workingDirectory` | 实际工作目录。 |
|  | `commandSummary` | 脱敏后的命令摘要。 |

### 7.4 确认绑定

| 类型 | 字段 | 说明 |
| --- | --- | --- |
| `LocalShellConfirmationEntry` | `tokenHash` | 不存令牌明文。 |
|  | `conversationId` | 对话绑定。 |
|  | `commandHash` | 命令、工作目录、环境变量摘要绑定。 |
|  | `workingDirectoryHash` | 工作目录绑定。 |
|  | `riskLevel` | 风险绑定。 |
|  | `expiresAt` | 过期时间。 |
|  | `status` | `PENDING/APPROVED/CANCELED/EXPIRED/USED/MISMATCH`。 |

## 8. REST/SSE 接口

| 接口 | 方法 | 路径 | 说明 |
| --- | --- | --- | --- |
| 工具目录 | GET | `/api/ai/mcp/local-shell/tools` | 返回 Local Shell MCP 工具清单。 |
| 非流式调用 | POST | `/api/ai/mcp/local-shell/tools/call` | 执行短命令或返回确认结果。 |
| 流式调用 | POST | `/api/ai/mcp/local-shell/tools/call/stream` | 长命令增量输出。 |
| 确认执行 | POST | `/api/ai/mcp/local-shell/confirmations/{token}/approve` | 用户确认后执行绑定命令。 |
| 取消确认 | POST | `/api/ai/mcp/local-shell/confirmations/{token}/cancel` | 取消确认令牌。 |
| 取消运行 | POST | `/api/ai/mcp/local-shell/tools/running/{requestId}/cancel` | 终止本应用启动的命令进程。 |

SSE 事件沿用现有 AI 工具事件语义：

| 事件名 | 数据 |
| --- | --- |
| `tool-start` | 工具名、命令摘要、风险级别。 |
| `tool-output` | 脱敏增量输出。 |
| `tool-confirmation` | `LocalShellMcpToolResult`。 |
| `tool-result` | `LocalShellMcpToolResult`。 |
| `tool-error` | `LocalShellMcpToolResult`。 |

## 9. Spring AI 接入

### 9.1 `AiToolScope`

新增：

```text
LOCAL_SHELL
LOCAL_DEVELOPMENT
```

建议：

- 普通对话默认不自动启用 Local Shell。
- 当用户明确表达“执行本机命令、查看本地文件、运行构建、检查进程”等意图，或在设置中启用“本机工具能力”后，才向模型提供 Local Shell 工具。
- `AiConversationService` 根据用户消息和设置选择工具范围，避免模型在普通闲聊中看到高风险工具。

### 9.2 工具注册

`AiToolRegistry` 增加：

```text
SpringAiLocalShellToolCallbackAdapter
```

返回策略：

| Scope | 工具 |
| --- | --- |
| `ADB_DEVICE_MANAGEMENT` | ADB MCP |
| `LOG_ANALYSIS` | ADB 日志相关工具 |
| `LOCAL_SHELL` | Local Shell MCP |
| `LOCAL_DEVELOPMENT` | Local Shell MCP + ADB MCP，供本地调试场景使用 |

## 10. 前端设计

### 10.1 AI 设置

新增“本机命令能力”设置区：

| 字段 | 控件 | 默认 |
| --- | --- | --- |
| 启用 Local Shell MCP | 开关 | 关闭 |
| 允许工作目录 | 可增删路径输入 | 用户主目录或项目目录 |
| 默认超时 | 数字输入 | 10 秒 |
| 高风险命令必须确认 | 固定打开 | 不允许关闭 |
| Shell 模式必须确认 | 开关 | 打开 |

### 10.2 对话过程展示

复用现有工具执行卡片，但显示：

- 工具类型：`Local Shell`
- 命令摘要
- 工作目录
- 风险级别
- stdout/stderr 摘要
- 是否截断
- 取消按钮

### 10.3 确认卡片

确认卡片必须展示：

- 拟执行命令
- 工作目录
- 风险原因
- 影响范围
- 有效期
- 确认/取消按钮

## 11. 错误码

| 错误码 | 说明 |
| --- | --- |
| `LOCAL_SHELL_DISABLED` | Local Shell MCP 未启用。 |
| `LOCAL_SHELL_COMMAND_EMPTY` | 命令为空。 |
| `LOCAL_SHELL_WORKDIR_DENIED` | 工作目录不在允许范围或命中拒绝目录。 |
| `LOCAL_SHELL_COMMAND_DENIED` | 命令被策略拒绝。 |
| `LOCAL_SHELL_CONFIRMATION_REQUIRED` | 需要用户确认。 |
| `LOCAL_SHELL_CONFIRMATION_EXPIRED` | 确认令牌过期。 |
| `LOCAL_SHELL_CONFIRMATION_MISMATCH` | 令牌与命令不匹配。 |
| `LOCAL_SHELL_TIMEOUT` | 命令超时。 |
| `LOCAL_SHELL_CANCELED` | 用户取消。 |
| `LOCAL_SHELL_EXEC_FAILED` | 进程启动或执行失败。 |
| `LOCAL_SHELL_OUTPUT_TRUNCATED` | 输出被截断，作为结果标识。 |

## 12. 关键决策与理由

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 是否直接复用 ADB MCP 包 | 不复用包名，只复用模式 | ADB 操作设备，Local Shell 操作宿主机，风险边界不同。 |
| 是否抽通用 MCP 框架 | 只抽极少 `common` 类型 | 避免大规模重构现有 ADB MCP，控制改动范围。 |
| 是否默认启用 | 默认关闭 | 本机 shell 风险高于 ADB，必须用户显式授权。 |
| 执行方式 | `ARGV` 优先，`SHELL` 兼容 | `ARGV` 更安全，`SHELL` 支持真实终端语义。 |
| 高风险命令处理 | 确认令牌绑定完整命令 | 防止模型确认后篡改命令。 |
| 是否实现交互式终端 | P0 不做 | 复杂度和安全风险高，先满足非交互命令。 |
| 审计内容 | 只记摘要，不记完整输出 | 避免本地隐私和密钥进入日志。 |

## 13. 风险与控制

| 风险 | 控制措施 |
| --- | --- |
| 模型误删文件 | 删除、覆盖、移动必须确认，确认绑定完整命令和工作目录。 |
| 泄露密钥 | 敏感路径读取默认拒绝或强确认，输出脱敏。 |
| 长命令卡死 | 默认 10 秒超时，最大超时受配置限制，可取消。 |
| 下载执行恶意脚本 | `curl|sh`、`wget|bash`、PowerShell 下载执行识别为 CRITICAL，默认拒绝。 |
| 操作系统损坏 | 系统目录写入、提权、格式化、服务控制默认拒绝或强确认。 |
| 前端被绕过 | 所有确认在后端校验，前端只展示令牌，不决定安全。 |

## 14. 任务拆分

| 任务 | 内容 | 工作量 |
| --- | --- | --- |
| 后端模型与配置 | 新增 `ai-mcp-local-shell` 配置、请求/结果/计划/确认/审计模型。 | 1 天 |
| 工具目录 | 实现 `LocalShellToolCatalog` 和 P0 工具 Schema。 | 0.5 天 |
| 策略与风险识别 | 工作目录校验、环境变量过滤、风险识别、拒绝规则。 | 2 天 |
| 确认机制 | 一次性令牌、绑定校验、确认/取消接口。 | 1 天 |
| 命令执行 | `ARGV`/`SHELL` 执行、超时、取消、流式输出、输出脱敏。 | 2 天 |
| Spring AI 适配 | 新增 ToolCallback Adapter，接入 `AiToolRegistry` 和对话工具范围。 | 1 天 |
| REST/SSE 接口 | 工具目录、调用、流式调用、确认、取消接口。 | 1 天 |
| 前端设置与交互 | AI 设置增加本机命令能力；工具卡片支持 Local Shell；确认卡片复用/泛化。 | 1.5 天 |
| 测试与验收 | 单测覆盖风险识别、工作目录、确认令牌、执行超时、脱敏；联调 AI 对话。 | 2 天 |

合计约 12 人天。

## 15. 自检

- [x] 覆盖本机命令执行、确认、超时、取消、脱敏、审计。
- [x] 明确默认关闭和用户授权边界。
- [x] 明确不做完整交互式终端，避免第一阶段复杂度失控。
- [x] 保持 ADB MCP 与 Local Shell MCP 解耦。
- [x] 接口定义到签名/契约层，不包含实现代码。
- [x] 任务拆分可直接进入编码阶段。
