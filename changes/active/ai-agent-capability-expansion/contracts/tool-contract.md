# 中立工具契约

## 1. 文档信息

| 项目 | 内容 |
|------|------|
| 任务 | M0-02：定义中立工具契约 |
| 契约版本 | 1.0.0 |
| 适用范围 | Agent Runtime、Tool Gateway、领域工具、底层适配器、UI 事件和审计 |
| 不依赖 | ADB 类型、Local Shell 类型、Spring AI 类型、MCP SDK 类型、前端消息类型 |

## 2. 设计原则

1. 领域核心只依赖本契约，框架和协议通过 Adapter 转换。
2. 工具定义、调用意图、策略决策、执行结果和执行事件相互分离。
3. 任何工具执行前都必须经过 Schema、平台、权限、风险、幂等和资源校验。
4. 模型不能指定或降低最终风险等级，风险由本地策略计算。
5. 大型输出不直接进入内存结果、事件或模型上下文，使用摘要和 Artifact 引用。
6. 契约字段以版本管理；未知主版本必须拒绝，未知可选字段必须忽略。

## 3. 公共类型

### 3.1 ToolDefinition

描述一个可发现、可授权和可路由的工具，不包含运行状态。

| 字段 | 类型 | 必填 | 约束 |
|------|------|------|------|
| `schemaVersion` | string | 是 | 语义化版本；当前为 `1.0.0` |
| `toolId` | string | 是 | 全局稳定 ID，格式 `domain.capability.action` |
| `displayName` | string | 是 | 用户可见名称，不含协议或框架术语 |
| `description` | string | 是 | 清楚说明用途、边界和主要副作用 |
| `source` | ToolSource | 是 | 工具来源和适配器信息 |
| `inputSchema` | JSON Schema | 是 | 参数 Schema；禁止自由拼接命令代替结构化参数 |
| `outputSchema` | JSON Schema | 是 | 结构化结果 Schema；大型正文通过 Artifact 表达 |
| `capabilities` | string[] | 是 | 可路由能力标签，如 `device.read`、`process.control` |
| `platforms` | Platform[] | 是 | 明确支持的平台集合 |
| `accessMode` | ToolAccessMode | 是 | `READ`、`WRITE`、`CONTROL` 或 `MIXED` |
| `idempotency` | ToolIdempotency | 是 | 幂等属性和幂等键生成要求 |
| `riskProfile` | ToolRiskProfile | 是 | 本地静态风险基线，不是最终策略决定 |
| `executionProfile` | ToolExecutionProfile | 是 | 超时、输出、并发和取消能力 |
| `enabled` | boolean | 是 | 是否可发现；禁用工具不能被模型调用 |
| `deprecated` | boolean | 是 | 是否已弃用 |
| `replacementToolId` | string | 否 | 弃用后的替代工具 |

示例：

```json
{
  "schemaVersion": "1.0.0",
  "toolId": "desktop.process.list",
  "displayName": "本机进程查询",
  "description": "查询本机进程摘要，只读，不终止进程",
  "source": {
    "kind": "LOCAL_ADAPTER",
    "provider": "local-shell",
    "adapterVersion": "1.0.0"
  },
  "inputSchema": {
    "type": "object",
    "properties": {
      "filter": { "type": "string", "maxLength": 256 }
    },
    "additionalProperties": false
  },
  "outputSchema": {
    "type": "object",
    "required": ["items"],
    "properties": {
      "items": { "type": "array" },
      "artifactId": { "type": "string" }
    }
  },
  "capabilities": ["desktop.process.read"],
  "platforms": ["MACOS", "WINDOWS", "LINUX"],
  "accessMode": "READ",
  "idempotency": { "mode": "NATURAL", "keyRequired": false },
  "riskProfile": { "minimumLevel": "LOW", "canEscalate": true },
  "executionProfile": {
    "defaultTimeoutMs": 30000,
    "maxTimeoutMs": 120000,
    "maxInlineOutputBytes": 65536,
    "supportsCancellation": true,
    "supportsStreaming": false,
    "resourceScopes": ["host"]
  },
  "enabled": true,
  "deprecated": false
}
```

### 3.2 ToolCallRequest

表示 Runtime 已决定尝试的一次工具调用。请求持久化后才能进入策略和执行阶段。

| 字段 | 类型 | 必填 | 约束 |
|------|------|------|------|
| `schemaVersion` | string | 是 | 请求契约版本 |
| `conversationId` | string | 是 | 所属会话 |
| `taskId` | string | 是 | 所属任务 |
| `turnId` | string | 是 | 所属用户轮次 |
| `stepId` | string | 是 | 所属计划步骤 |
| `toolCallId` | string | 是 | 每次调用全局唯一，不得由一次模型请求复用 |
| `toolId` | string | 是 | 对应 `ToolDefinition.toolId` |
| `toolSchemaVersion` | string | 是 | 计划时看到的工具版本 |
| `arguments` | object | 是 | 必须通过 `inputSchema` 校验 |
| `argumentDigest` | string | 是 | 规范化参数的 SHA-256 摘要，用于确认和审计绑定 |
| `idempotencyKey` | string | 条件 | 写操作或声明需要幂等键的工具必须提供 |
| `requestedBy` | ToolCaller | 是 | 发起主体：Agent、Workflow、用户或系统 |
| `executionContext` | ToolExecutionContext | 是 | 平台、设备、工作区、资源和预算引用 |
| `createdAt` | RFC 3339 | 是 | UTC 时间 |

约束：

- `arguments` 只表达业务参数，不接受预拼接的 Shell 字符串作为通用字段。
- 底层任意命令工具可以把命令定义为其专属 Schema 字段，但仍需执行器使用参数化规划和本地策略校验。
- Runtime、Tool Gateway 和审计必须保留同一 `toolCallId`。

### 3.3 ToolCallResult

表示一次调用的最终事实，不承担实时进度传输。

| 字段 | 类型 | 必填 | 约束 |
|------|------|------|------|
| `schemaVersion` | string | 是 | 结果契约版本 |
| `toolCallId` | string | 是 | 与请求严格一致 |
| `toolId` | string | 是 | 实际执行工具 |
| `status` | ToolCallStatus | 是 | 必须为终态值 |
| `riskDecision` | ToolRiskDecision | 是 | 实际风险、授权动作和策略来源 |
| `startedAt` | RFC 3339 | 否 | 未开始执行时可为空 |
| `finishedAt` | RFC 3339 | 是 | 结果确定时间 |
| `durationMs` | integer | 是 | 非负 |
| `output` | object | 否 | 通过 `outputSchema` 校验的有界结构化结果 |
| `summary` | string | 是 | 有界、脱敏、用户可读摘要 |
| `artifacts` | ArtifactReference[] | 是 | 完整输出、日志、文件或报告的引用 |
| `error` | ToolError | 否 | 失败、阻断、超时或结果未知时必填 |
| `exit` | ToolExit | 否 | 进程类工具的退出信息；非进程工具可为空 |
| `metrics` | ToolCallMetrics | 是 | 输入输出字节、丢弃量、重试数等 |
| `sideEffect` | ToolSideEffect | 是 | 是否产生副作用、是否验证、是否可补偿 |

`output`、`summary` 和 `error` 都必须有严格字节上限。超限内容写入 Artifact，结果中只保留摘要、范围和校验和。

### 3.4 ToolRiskLevel

| 值 | 默认策略 | 说明 |
|----|----------|------|
| `LOW` | 直接执行 | 只读或影响可忽略，仍需白名单和参数校验 |
| `MEDIUM` | 用户确认 | 可能修改状态、访问敏感数据或产生明显资源影响 |
| `HIGH` | 直接阻断 | 删除、卸载、越权访问或策略认定的不可接受操作；除非用户在产品配置中显式调整对应规则，模型无权调整 |
| `UNCLASSIFIED` | 阻断 | 无法完成风险判定，不能执行 |

风险等级不是模型输入中的可信结论。最终等级由本地策略基于工具、规范化参数、平台、资源、用户授权配置和数据分类计算，且只允许相对静态基线升级，不能由模型降级。

### 3.5 ToolCallStatus

非终态：

- `REQUESTED`
- `POLICY_EVALUATING`
- `WAITING_CONFIRMATION`
- `QUEUED`
- `RUNNING`
- `CANCELING`

终态：

- `SUCCEEDED`
- `FAILED`
- `CANCELED`
- `BLOCKED`
- `TIMED_OUT`
- `UNKNOWN`

`UNKNOWN` 只用于进程或连接异常后无法确定副作用是否发生的情况。该状态禁止自动重试写操作，必须先执行后置验证或人工处理。

### 3.6 ToolSource

| 字段 | 类型 | 说明 |
|------|------|------|
| `kind` | enum | `DOMAIN_SERVICE`、`LOCAL_ADAPTER`、`STANDARD_MCP`、`REMOTE_API`、`BUILT_IN` |
| `provider` | string | 稳定来源名，如 `device-service`、`local-shell` |
| `adapterVersion` | string | 适配器版本 |
| `serverId` | string | 外部 MCP 或远程服务标识，可选 |
| `transport` | string | `IN_PROCESS`、`HTTP`、`STDIO` 等，可选 |

UI 必须使用 `displayName`、`source.kind` 和能力元数据展示工具，不得根据令牌前缀、标题文本或错误码猜测工具来源。

### 3.7 ToolExecutionEvent

工具事件是 Agent Event 的载荷之一，必须携带 `taskId`、`turnId`、`stepId`、`toolCallId` 和任务级 `eventSequence`。工具事件类型包括：

- `TOOL_REQUESTED`
- `TOOL_POLICY_DECIDED`
- `TOOL_CONFIRMATION_REQUIRED`
- `TOOL_QUEUED`
- `TOOL_STARTED`
- `TOOL_PROGRESS`
- `TOOL_OUTPUT_AVAILABLE`
- `TOOL_COMPLETED`
- `TOOL_FAILED`
- `TOOL_CANCELED`
- `TOOL_BLOCKED`

进度事件必须有界、可合并并支持背压。实时 stdout/stderr 不得逐字符无界发送；应使用分块、摘要、范围和 Artifact 游标。

## 4. 支撑枚举与值对象

### 4.1 Platform

`ANDROID`、`IOS`、`HARMONY_OS`、`MACOS`、`WINDOWS`、`LINUX`、`WEB`、`PLATFORM_INDEPENDENT`。

工具只能在声明的平台上执行。平台不匹配必须返回 `BLOCKED`，不能尝试使用其他平台命令兜底。

### 4.2 ToolAccessMode

| 值 | 含义 |
|----|------|
| `READ` | 读取状态，不修改目标资源 |
| `WRITE` | 修改数据或配置 |
| `CONTROL` | 启停、重启、安装、卸载、输入模拟等控制操作 |
| `MIXED` | 具体风险依赖参数，执行前必须重新分类 |

### 4.3 ToolIdempotency

| 字段 | 取值 |
|------|------|
| `mode` | `NATURAL`、`KEYED`、`VERIFY_REQUIRED`、`NON_IDEMPOTENT` |
| `keyRequired` | 是否必须提供幂等键 |
| `verificationCapability` | 用于验证外部状态的能力标签，可选 |

### 4.4 ToolRiskDecision

必须包含：

- `level`
- `action`: `ALLOW`、`CONFIRM`、`BLOCK`
- `policyRuleId`
- `reasonCode`
- `reasonSummary`
- `evaluatedAt`
- `confirmationId`，仅需要确认时存在

### 4.5 ToolError

必须包含：

- 稳定 `code`。
- `category`: `VALIDATION`、`POLICY`、`RESOURCE`、`EXECUTION`、`TIMEOUT`、`CANCELED`、`PROTOCOL`、`UNKNOWN`。
- 用户可读 `message`。
- 有界、脱敏的 `detail`。
- `retryable`。
- `resultUncertain`。
- 可选 `causeReference`，指向后端受控日志或 Trace，不返回敏感堆栈。

### 4.6 ArtifactReference

必须包含：`artifactId`、`kind`、`mediaType`、`sizeBytes`、`sha256`、`compression`、`sensitivity`、`retentionUntil`、`rangeReadable` 和 `redacted`。Artifact 路径不得直接暴露给模型或前端。

## 5. Tool Gateway 处理顺序

每次调用按以下固定顺序执行：

1. 查找工具定义并校验契约主版本。
2. 按 JSON Schema 校验参数并拒绝未知字段。
3. 规范化平台、设备、路径、包名和工作区上下文。
4. 检查工具启用状态、调用主体权限和平台能力。
5. 计算资源键和读写锁模式。
6. 计算本地风险与数据策略，不接受模型指定的风险结论。
7. 检查幂等键和已有结果。
8. 执行确认或阻断策略。
9. 获取资源锁并进入有界执行队列。
10. 执行、传播取消、限制输出并写入 Artifact。
11. 执行后置验证，确定副作用状态。
12. 脱敏结果，持久化审计和事件，再向 Runtime 返回结果。

任何 Adapter 都不得绕过该处理顺序直接执行领域操作。

## 6. 现有模型映射

### 6.1 ADB 工具适配

| 现有概念 | 中立契约 |
|----------|----------|
| ADB 工具目录项 | `ToolDefinition`，`source.provider=adb`，平台为 `ANDROID` |
| ADB 调用参数 | `ToolCallRequest.arguments` |
| ADB 风险等级 | 映射为 `ToolRiskLevel`，保留原本更严格的规则 |
| ADB 确认令牌 | `ToolRiskDecision.confirmationId` 对应的持久 Confirmation |
| ADB 执行结果 | `ToolCallResult`；完整日志转 Artifact |
| ADB 工具事件 | `ToolExecutionEvent`，补齐任务、步骤和独立调用标识 |

### 6.2 Local Shell 工具适配

| 现有概念 | 中立契约 |
|----------|----------|
| Local Shell 工具目录项 | 独立 `ToolDefinition`，`source.provider=local-shell` |
| 工作目录策略 | `executionContext.workspace` 加 Tool Gateway 路径策略 |
| 命令规划结果 | Adapter 内部执行计划，不暴露为 ADB 类型 |
| Local Shell 风险等级 | 映射为 `ToolRiskLevel`，不再复用 ADB 枚举 |
| Local Shell 结果 | 直接构造 `ToolCallResult`，不再复用 ADB 结果模型 |
| 终端进程 | `ToolExit`、`ToolCallMetrics` 和 Artifact |

迁移期间允许兼容 Adapter 把旧类型转换为新契约，但 Agent Runtime、事件协议、UI 新代码和未来工具不得直接依赖旧 ADB 结果类型。

## 7. 版本兼容

1. `schemaVersion` 使用 `major.minor.patch`。
2. 主版本变化表示不兼容，旧任务必须迁移或明确拒绝恢复。
3. 次版本只能增加可选字段或枚举能力；执行方遇到未知必填能力必须拒绝。
4. Patch 版本只能修正文档或不改变语义的约束。
5. 任务必须记录计划时和执行时的工具版本；版本变化后恢复任务必须重新校验。
6. 工具定义快照或可重建摘要必须随 Task/Checkpoint 保存，防止升级后无法解释旧调用。

## 8. 验收场景

1. ADB 与 Local Shell 均可完整映射到公共类型，公共字段中不存在设备序列号、ADB 命令或 Shell 工作目录等来源专属必填字段。
2. UI 可仅根据 `ToolDefinition` 和 `ToolSource` 正确显示“ADB工具”“本机终端”等标题，不解析令牌前缀。
3. 100MB 工具输出只在结果中保留有界摘要和 Artifact 引用，不把完整内容装入 `ToolCallResult`。
4. 风险无法分类时返回 `UNCLASSIFIED/BLOCK`，模型不能通过参数要求改为低风险。
5. 相同 `toolCallId` 或幂等键重复到达时返回已有结果，不重复执行。
6. 平台为 iOS 的任务无法调用仅声明 `ANDROID` 的工具。
7. 未知主版本、非法参数、未知字段或禁用工具在执行前被拒绝。
