# Agent 标识与事件协议

## 1. 文档信息

| 项目 | 内容 |
|------|------|
| 任务 | M0-03：定义 Agent 标识和事件协议 |
| 协议版本 | 1.0.0 |
| 传输边界 | 协议与 SSE、WebSocket 或进程内传输解耦；M1 首选 REST + SSE |
| 真相来源 | 后端持久化 Task、Checkpoint 和 Event Store |

## 2. 标识体系

### 2.1 标识定义与所有权

| 标识 | 作用域 | 生成方 | 生命周期 | 规则 |
|------|--------|--------|----------|------|
| `conversationId` | 历史聊天 | 后端 Conversation Store | 用户删除前 | 一个会话可包含多个任务和 Turn |
| `taskId` | Agent 任务 | 后端 Agent Runtime | 任务归档/删除前 | 一次可恢复业务目标唯一 |
| `turnId` | 用户轮次 | 后端 Agent Runtime | 随任务保留 | 一次用户输入及其推进过程唯一 |
| `stepId` | 计划步骤 | 后端 Planner/Runtime | 随任务保留 | 计划修订后旧步骤 ID 不复用 |
| `toolCallId` | 单次工具调用 | 后端 Tool Gateway | 随任务和审计保留 | 每次调用独立，严禁一次模型请求内复用 |
| `modelCallId` | 单次模型请求 | 后端 Model Gateway | 随 Trace 保留 | 重试必须生成新 ID 并引用前一次调用 |
| `confirmationId` | 单次确认 | 后端 Confirmation Coordinator | 决策或过期后保留审计 | 绑定任务、步骤、工具和参数摘要 |
| `artifactId` | Artifact | 后端 Artifact Store | 按保留策略 | 不暴露本地文件路径 |
| `eventSequence` | 单任务事件 | 后端 Event Sequencer | 随任务事件保留 | 从 1 开始严格单调递增 |

除客户端创建幂等键外，客户端不得生成或覆盖上述控制标识。客户端提交的 `conversationId` 必须由后端校验归属；`taskId`、`turnId`、`stepId`、`toolCallId` 和序号只接受后端生成值。

### 2.2 标识格式

- 业务标识推荐使用 UUIDv7 的小写规范字符串，兼顾全局唯一和按时间索引。
- `eventSequence` 使用有符号 64 位正整数，持久化时不得以 JavaScript Number 传输；JSON 中使用十进制字符串。
- `eventId` 是派生值：`{taskId}:{eventSequence}`，不另设独立真相源。
- 标识不编码用户、设备序列号、Provider、风险或文件路径，避免泄露和重命名耦合。

## 3. Agent Event Envelope

所有事件使用统一 Envelope：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `schemaVersion` | string | 是 | Envelope 版本，当前 `1.0.0` |
| `eventId` | string | 是 | `{taskId}:{eventSequence}` |
| `eventSequence` | string | 是 | 单任务严格递增十进制序号 |
| `eventType` | AgentEventType | 是 | 事件类型 |
| `scope` | EventScope | 是 | `TASK`、`TURN`、`STEP`、`MODEL_CALL`、`TOOL_CALL`、`CONFIRMATION`、`OUTPUT` |
| `conversationId` | string | 是 | 所属会话 |
| `taskId` | string | 是 | 所属任务 |
| `turnId` | string | 条件 | Turn 创建后的事件必须存在 |
| `stepId` | string | 条件 | 步骤、模型、工具、确认事件必须存在 |
| `modelCallId` | string | 条件 | 模型事件必须存在 |
| `toolCallId` | string | 条件 | 工具和工具确认事件必须存在 |
| `confirmationId` | string | 条件 | 确认事件必须存在 |
| `occurredAt` | RFC 3339 | 是 | 事实发生时间，UTC |
| `recordedAt` | RFC 3339 | 是 | Event Store 提交时间，UTC |
| `taskVersion` | integer | 是 | 事件对应的任务乐观锁版本 |
| `producer` | string | 是 | `agent-runtime`、`model-gateway`、`tool-gateway` 等稳定生产者 |
| `correlationId` | string | 是 | 端到端 Trace 关联标识 |
| `causationEventId` | string | 否 | 直接导致本事件的前序事件 |
| `payloadVersion` | string | 是 | 当前事件载荷版本 |
| `payload` | object | 是 | 由 `eventType` 决定的有界结构化载荷 |

示例：

```json
{
  "schemaVersion": "1.0.0",
  "eventId": "019abcde-1111-7000-8000-000000000001:42",
  "eventSequence": "42",
  "eventType": "TOOL_COMPLETED",
  "scope": "TOOL_CALL",
  "conversationId": "019abcde-0000-7000-8000-000000000001",
  "taskId": "019abcde-1111-7000-8000-000000000001",
  "turnId": "019abcde-2222-7000-8000-000000000001",
  "stepId": "019abcde-3333-7000-8000-000000000001",
  "toolCallId": "019abcde-4444-7000-8000-000000000001",
  "occurredAt": "2026-07-14T08:00:00.120Z",
  "recordedAt": "2026-07-14T08:00:00.130Z",
  "taskVersion": 17,
  "producer": "tool-gateway",
  "correlationId": "019abcde-5555-7000-8000-000000000001",
  "causationEventId": "019abcde-1111-7000-8000-000000000001:41",
  "payloadVersion": "1.0.0",
  "payload": {
    "toolId": "desktop.process.list",
    "status": "SUCCEEDED",
    "summary": "查询完成，共 38 条结果",
    "artifactIds": []
  }
}
```

## 4. 事件类型

### 4.1 任务与轮次事件

| 事件 | 必要载荷 |
|------|----------|
| `TASK_CREATED` | 目标摘要、初始状态、预算摘要 |
| `TASK_STATE_CHANGED` | 原状态、新状态、原因码 |
| `TASK_PAUSE_REQUESTED` | 发起者、原因 |
| `TASK_RESUMED` | 恢复 Checkpoint、环境校验结果 |
| `TASK_CANCEL_REQUESTED` | 发起者、原因 |
| `TURN_CREATED` | 输入类型、输入摘要 |
| `TURN_COMPLETED` | 结果状态、最终输出引用 |
| `TASK_COMPLETED` | 最终结果引用、预算使用、耗时 |
| `TASK_FAILED` | 稳定错误、失败阶段、可重试性 |
| `TASK_CANCELED` | 取消传播结果、已完成副作用 |

`TASK_COMPLETED`、`TASK_FAILED`、`TASK_CANCELED` 是互斥终止事件，一个任务只能持久化其中一个。

### 4.2 计划与步骤事件

| 事件 | 必要载荷 |
|------|----------|
| `PLAN_CREATED` | 计划版本、步骤摘要 |
| `PLAN_REVISED` | 原版本、新版本、修订原因、保留步骤 |
| `STEP_CREATED` | 步骤类型、标题、依赖、风险提示 |
| `STEP_STARTED` | 尝试次数、前置校验结果 |
| `STEP_PROGRESS` | 有界任务级说明、进度值可选 |
| `STEP_COMPLETED` | 结构化结果摘要、后置校验结果 |
| `STEP_SKIPPED` | 跳过原因和依赖决策 |
| `STEP_FAILED` | 错误、重试或终止决策 |
| `STEP_COMPENSATED` | 补偿动作、结果和剩余影响 |

### 4.3 模型事件

| 事件 | 必要载荷 |
|------|----------|
| `MODEL_CALL_STARTED` | Provider、模型、调用阶段、预算 |
| `MODEL_OUTPUT_DELTA` | `outputId`、`chunkIndex`、内容类型、有界文本段 |
| `MODEL_TOOL_REQUESTED` | 模型请求摘要和后端分配的 `toolCallId` |
| `MODEL_CALL_COMPLETED` | Finish Reason、Token、耗时、输出引用 |
| `MODEL_CALL_FAILED` | 错误分类、阶段、可重试性 |

禁止发布模型隐藏推理、私有 chain-of-thought、Provider 内部调试提示或完整系统 Prompt。可展示的是由 Runtime 生成或模型显式输出的简短任务级“计划/决策说明”，且必须标记为摘要而非内部推理。

### 4.4 工具事件

采用 `tool-contract.md` 中定义的事件集合，至少包括请求、策略决定、确认要求、排队、开始、进度、输出可用、完成、失败、取消和阻断。所有工具事件必须同时具备 `stepId` 与独立 `toolCallId`。

### 4.5 确认事件

| 事件 | 必要载荷 |
|------|----------|
| `CONFIRMATION_REQUIRED` | 工具、参数摘要、风险、影响、有效期、外发摘要可选 |
| `CONFIRMATION_ACCEPTED` | 决策主体、时间、绑定摘要校验结果 |
| `CONFIRMATION_REJECTED` | 决策主体、时间、原因可选 |
| `CONFIRMATION_EXPIRED` | 过期时间、后续任务决策 |
| `CONFIRMATION_INVALIDATED` | 参数/环境变化或任务取消原因 |

确认接受事件持久化后 Runtime 才能从 `WAITING_CONFIRMATION` 自动继续。前端收到事件只负责更新展示，不负责触发“继续”Prompt。

### 4.6 输出、错误和存储事件

| 事件 | 必要载荷 |
|------|----------|
| `OUTPUT_STARTED` | `outputId`、格式、目标区域 |
| `OUTPUT_DELTA` | `outputId`、`chunkIndex`、有界内容段 |
| `OUTPUT_COMPLETED` | `outputId`、总块数、摘要、Artifact 可选 |
| `ARTIFACT_CREATED` | Artifact 元数据，不含真实路径 |
| `ERROR_REPORTED` | 稳定错误码、阶段、用户消息、Trace 引用 |
| `CHECKPOINT_SAVED` | Checkpoint ID、任务版本、恢复原因 |
| `RECOVERY_COMPLETED` | 恢复来源、跳过步骤、重新校验结果 |

## 5. 顺序、一致性与幂等

### 5.1 顺序规则

1. 事件生产者提交“待记录事实”给单任务 Event Sequencer，不自行分配序号。
2. Sequencer 在任务级临界区内分配下一个 `eventSequence`。
3. 事件先追加到持久 Event Store，再投递给实时订阅者。
4. 并发步骤按成功提交到 Sequencer 的顺序获得序号；`occurredAt` 只用于展示，不用于排序。
5. 客户端严格按 `eventSequence` 应用事件，检测到缺口时暂停后续应用并补拉缺失区间。
6. 事件发布失败不回滚已经持久化的业务事实；重连时补发。

### 5.2 幂等规则

- 唯一键为 `taskId + eventSequence`。
- 同一唯一键内容不同属于存储损坏，必须隔离任务并报警。
- 客户端、SSE 网关和投影器必须允许重复投递并按唯一键去重。
- `OUTPUT_DELTA` 额外以 `outputId + chunkIndex` 去重，完成事件记录总块数和内容摘要。
- 事件不得被原地修改；纠正通过新事件表达。

### 5.3 任务版本关系

- 任务状态变更、步骤结果、确认决策和终态事件必须记录对应 `taskVersion`。
- 一个任务版本可以产生多个展示事件，但任何改变任务事实的事件必须与持久状态在同一提交边界内完成。
- 事件序号和任务版本都不能作为时间戳替代品。

## 6. 订阅、重连和回放

### 6.1 REST/SSE 行为

- 订阅入口语义：`GET /agent/tasks/{taskId}/events?after={eventSequence}`。
- SSE `id` 使用 `eventId`，`event` 使用 `eventType`，`data` 使用完整 Envelope。
- 客户端可使用查询参数或 `Last-Event-ID` 提交游标；两者同时存在时必须一致。
- `after=0` 从第一条可用事件开始。
- 订阅建立前先确定持久高水位，补发历史后再无缝切换实时流。

### 6.2 重连规则

1. 客户端持久保存每个任务最后成功应用的序号。
2. 断线后指数退避重连，不能重新创建任务或重新发送用户消息。
3. 服务端从游标后一条开始按序补发。
4. 任务已终止时仍补发至终止事件，然后正常结束连接。
5. 游标大于当前高水位返回冲突；游标早于保留窗口返回 `EVENT_CURSOR_EXPIRED` 和任务快照版本。
6. 游标过期时客户端加载任务快照，再订阅快照包含的最后序号之后的事件。

### 6.3 连接与任务生命周期

- UI 关闭、刷新或 Electron Renderer 崩溃不自动取消可恢复任务。
- 用户显式取消调用任务命令接口，取消事件由 Runtime 发布。
- 非持久临时任务可采用“无订阅者超时取消”策略，但必须在创建时明确标记，Agent 业务任务默认持久。

## 7. 背压和载荷边界

1. Event Store 保存结构化事实，不保存无界逐行 stdout/stderr。
2. `*_DELTA` 事件按时间或字节窗口合并；单事件大小、每秒事件数和待发送队列都必须有上限。
3. 慢客户端不得阻塞模型、工具或 Event Store 写线程。
4. 实时发送队列溢出时断开慢订阅者并允许其按游标补拉，不能丢弃持久关键事件。
5. 高频进度可被合并或采样；状态变化、确认、错误、Artifact 和终止事件不可丢弃。
6. 超大 Markdown、表格、代码和日志按稳定块传输，完成后以摘要校验完整性。

## 8. 错误协议

事件和 API 错误统一包含：

- `code`
- `category`
- `message`
- `retryable`
- `taskId`
- `stage`
- `correlationId`
- 可选 `details`，必须有界和脱敏

不得包含 API Key、Authorization Header、确认令牌、完整系统 Prompt、未脱敏日志、文件全文、本机绝对敏感路径或后端完整堆栈。

## 9. 版本兼容

1. Envelope 和 Payload 分别版本化。
2. Envelope 主版本不兼容时拒绝订阅并提示客户端升级。
3. 同一主版本新增事件类型时，旧客户端必须忽略未知展示事件，但不能忽略未知终态、确认或安全事件；遇到后者应停止交互并刷新任务快照。
4. 事件持久化后不可批量重写版本；迁移使用新快照、兼容读取器或显式迁移记录。

## 10. 验收场景

1. 同一任务内两个并发工具完成顺序与 Sequencer 提交顺序一致，不会插入到已有事件之间。
2. 同一 Turn 内多个工具拥有不同 `toolCallId`，取消其中一个不会影响另一个。
3. 客户端处理到 25 后断线，以游标 25 重连时只收到 26 及以后事件。
4. 重复投递同一 `eventId` 不产生重复卡片、重复正文或重复终态。
5. 等待确认时先发布并持久化确认事件，确认后自动发布恢复和后续步骤事件。
6. 工具输出很长时聊天宽度和内存不随单行长度无限扩展，事件只携带有界块或 Artifact 引用。
7. 任何事件都能通过 `scope` 和对应标识唯一归属于任务、Turn、步骤、模型调用、工具调用或确认。
8. “正在思考”和执行过程只显示任务级摘要，不泄露模型私有推理。
