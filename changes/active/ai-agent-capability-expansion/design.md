# M2.5 AI 架构收敛设计

## 2026-07-21 Delta：直接执行收敛

### 技术设计

- `ToolPolicyPipeline` 仍执行契约、Schema、平台和风险基线校验，然后将 Adapter 的风险动作统一转为 `ALLOW`。
- `AiDataEgressPolicyService` 将 `CONFIRMATION_REQUIRED` 视为可直接外发，保留 `PROHIBITED` 和非本地模型的 `LOCAL_MODEL_ONLY` 硬阻断。
- 保留既有 Confirmation 数据模型和 API 以读取历史任务，新任务不再创建确认。
- 前端删除 AI 配置中的授权菜单和编辑表单，历史确认卡片仅作旧会话兼容。

### 阶段任务

- [x] D1：工具风险动作统一直接执行。
- [x] D2：普通敏感数据外发取消确认。
- [x] D3：移除 AI 配置授权页面。
- [x] D4：更新精简回归测试并完成 Electron 验证。

## 1. 目标

M2.5 不增加新的通用框架，目标是让 M1/M2 已实现的 Agent Runtime、Tool Gateway、确认、Checkpoint 和事件能力接管现有 AI 助手主链路，并逐步删除双轨实现。

## 2. M2.5 启动时事实

- 前端仍调用 `/api/ai/chat/stream`。
- `AiConversationService` 直接调用 Provider，并通过旧 `AiToolRegistry` 注册 ADB/Local Shell Callback。
- Agent Task API、Checkpoint、Agent Event 和 Tool Gateway 已存在，但没有驱动当前聊天。
- 用户确认后的自动续跑仍由前端重新拼装 Prompt，后端 `AgentRuntimeContinuationService` 只发布恢复事件，没有继续模型任务。

## 3. 设计原则

1. 保留现有 Chat API 和前端展示协议，先在后端内部切换主链路。
2. 不新建第二套 Conversation Facade、Workflow Engine 或事件总线。
3. 优先修改现有 `AiConversationService`、`AgentTaskApplicationService` 和 `AiToolRegistry`。
4. 每个阶段都必须形成可运行的业务闭环，不继续横向增加基础设施。
5. 新旧链路并存仅作为短期兼容状态，每个兼容点必须有明确删除任务。
6. 纯数据类型收敛放在主链路稳定之后，避免迁移过程中同时大规模改包名和调用方。

## 4. 目标主链路

```text
AiChatPanel
  -> /api/ai/chat/stream
  -> AiConversationService
  -> AgentTaskApplicationService
  -> Provider Gateway
  -> Tool Gateway
  -> Domain Tool / ADB / Local Shell
  -> Agent Event
  -> 兼容 SSE 映射
  -> AiChatPanel
```

敏感确认由后端保存 Checkpoint。用户批准后，同一 `taskId` 从 Checkpoint 继续，不再由前端构造“继续”Prompt。

## 5. 阶段任务

### M2.5-01 当前 Chat API 纳入 Agent Task 生命周期

- [x] 普通和流式对话创建持久 Agent Task。
- [x] 任务按 `CREATED -> PLANNING -> RUNNING -> COMPLETED/FAILED` 推进。
- [x] Provider ToolContext 携带 `taskId`、`turnId`、`stepId` 和 `modelCallId`。
- [x] Chat API 保持兼容，不要求前端立即切换 Agent Task API。
- [x] 覆盖正常完成、Provider 异常和流式异常状态测试。

### M2.5-02 当前模型工具调用迁入 Tool Gateway

- [x] `AiToolRegistry` 从统一 Tool Registry 发现工具，不再分别拼接 ADB/Local Shell Callback。
- [x] Spring AI Callback 只做协议转换，调用统一 Tool Gateway。
- [x] 旧工具结果映射仅保留为前端兼容层。
- [x] ADB、Local Shell 和领域工具统一经过 Schema、风险、幂等、锁、脱敏和审计。

### M2.5-03 Agent Event 接管聊天过程事件

- [x] 模型调用、工具调用、确认和终态进入同一任务事件序列。
- [x] 现有 Chat SSE 只直传高频正文，模型和输出控制状态由 Agent Event 统一记录，避免第二套终态和逐 Token 重复持久化。
- [x] 页面断开只关闭兼容订阅，不取消持久任务；Provider 异常和服务端执行超时由后端独立收尾。

### M2.5-04 后端确认自动续跑

- [x] Tool Gateway 的 `WAITING_CONFIRMATION` 生成 Agent Confirmation 和 Checkpoint。
- [x] 批准接口直接返回后端续跑 SSE，后端从 Checkpoint 重建原模型任务和确认绑定。
- [x] 原工具原参数复用确认身份；修改工具或参数不能消费旧授权，已完成调用由 Checkpoint 幂等跳过。
- [x] 删除前端 `confirmationContinuationPrompt`、`taskId` 和 `confirmationToken` 续跑编排。

### M2.5-05 删除双轨并收敛碎片模型

- [x] 删除旧 ADB/Local Shell Spring AI `ToolCallback` 适配器，当前 Chat 只使用统一 Tool Gateway。
- [x] 删除前端旧确认续跑 Prompt 和单实现 `AgentTaskContinuation` 接口。
- [x] 新增续跑快照聚合到现有 `AgentRecoveryState`；保留已有公共契约文件，避免无业务收益的大规模类型迁移。
- [x] 删除没有生产调用的 2 个旧 Callback 类和 1 个单实现接口，没有新增生产文件。

### M2.5-06 业务验收

- [x] 普通多轮对话。
- [x] 实时日志采集并分析。
- [x] ADB 敏感操作确认后自动继续。
- [x] Local Shell 敏感操作确认后自动继续。
- [x] SSE 订阅与任务生命周期解耦，Checkpoint 跨实例恢复兼容新旧文件。
- [x] 全量后端测试、前端构建、Electron 检查和实际 JAR 启动通过。

### M2.5 二次验收返修与确定性恢复

本节用于修正二次验收发现的主链路缺口。实现继续复用现有模块，不增加新的工作流引擎、事件总线或通用 Facade。

- [x] Checkpoint 加密保存原始中立工具请求和有界工具结果，批准后由后端直接调用 Tool Gateway，不再要求模型重新选择原工具。
- [x] 同一确认的并发批准只允许一个续跑执行；服务重启后可依靠 Checkpoint 和工具幂等结果继续总结。
- [x] 流式最终回复在任务完成前加密保存，并提供任务结果查询，使 SSE 断开后可以恢复最终内容。
- [x] 前端保存当前 `taskId`；用户明确取消时调用 Agent Task 取消 API，并向 Provider 和可取消工具传播信号。
- [x] 工具事件订阅改为任务调用级标识，避免同一会话并发流互相覆盖。
- [x] 同步 Chat 不暴露需要确认的工具；任务目标、设备摘要和恢复数据在落盘前脱敏或加密。
- [x] 只保留少量高价值闭环测试，覆盖确定性确认执行和最终结果持久化；取消与幂等继续复用既有专项测试。
- [x] 重新执行后端测试、前端构建、Electron 检查和隔离 JAR 启动验收。

### M2.5 三次验收稳定性返修

- [x] 确认批准后在 Checkpoint 中保留恢复关联标识，应用启动后分页扫描并后台恢复 `READY/RUNNING` 确认续跑任务。
- [x] 后台恢复使用丢弃型 SSE，不缓存无人订阅的模型正文；最终回复仍写入 Agent Task 供断线轮询恢复。
- [x] 已完成任务收到重复确认时重放既有结果，不加载过期 Checkpoint、不重新调用模型或工具。
- [x] 工具事件出现时重置最终回复边界，持久结果只保留最后一次工具调用后的业务回答。
- [x] 前端仅在取消接口明确返回 `CANCELED` 后显示取消成功；接口失败或终态冲突时展示真实错误并保留当前订阅。
- [x] 未新增生产文件、框架层或工具旁路；确认恢复继续复用 Agent Task、Checkpoint、Tool Gateway 和工具幂等结果。
- [x] 新增 3 个高价值后端测试，全量 267 个测试通过；前端构建、后端打包、隔离 JAR 启动、HTTP 200 和 54 个 MCP 工具注册通过。

## 6. 暂缓范围

- 新增多 Agent Supervisor。
- 动态工作流引擎。
- 新的 Provider 抽象层。
- 扩展更多设备平台工具。
- 大规模 RAG 和评测平台。

以上能力只有在 M2.5 主链路验收通过后才能继续。

# M3-01 历史聊天本地文件存储设计

## 1. 目标与边界

M3-01 只替换历史聊天的持久化来源，不提前实现 Working Memory、摘要、RAG、多 Agent 或前端大组件拆分。

- 后端本地文件是历史聊天唯一真相源，浏览器 `localStorage` 只作为一次性旧数据迁移来源。
- 支持 1000 个以上会话的分页列表；单次只向前端返回当前页摘要和活动会话的有界消息尾部。
- 1M Token 是单会话可持久化和后续检索的数据规模，不代表一次加载到 React、模型上下文或 Markdown 渲染器。
- 保留新建、选择续聊、重命名、删除以及“仅点击历史聊天不置顶”的现有产品行为。

## 2. 最小实现方案

1. 新增一个 `AiConversationStoreService`，使用“一个会话一个加密压缩文件 + 一个小型加密索引”的结构，不引入数据库、仓储接口或额外框架层。
2. 会话正文使用 JSON、GZIP 和现有 `AiConfigCrypto`，文件通过临时文件原子替换；写入统一进入 `StorageManager` 的 `CONVERSATION` 分类配额。
3. 前端每轮完成后只提交当前已加载的消息尾部；后端按稳定消息 ID 与已有文件合并，保留前端未加载的旧消息。
4. 列表接口分页返回摘要，详情接口只返回最近消息和总消息数，避免完整大会话进入 Electron Renderer。
5. 旧 `localStorage` 数据通过幂等迁移接口批量写入；仅当接口成功返回后，前端才删除旧键。
6. 继续复用现有 `AiController`、`aiApi.ts` 和 `AiChatPanel`，不新建 Controller、前端 Store 框架或 Conversation Facade。

## 3. 接口

| 接口 | 用途 |
|------|------|
| `GET /api/ai/conversations?page=&size=` | 分页读取会话摘要和活动会话 ID |
| `GET /api/ai/conversations/{id}?messageLimit=` | 读取会话详情及最近消息 |
| `PUT /api/ai/conversations/{id}` | 创建、更新、重命名或激活会话 |
| `DELETE /api/ai/conversations/{id}` | 删除会话文件和索引项 |
| `POST /api/ai/conversations/migrate` | 幂等迁移旧浏览器历史 |

## 4. 阶段任务

### M3-01 历史聊天本地文件存储

- [x] 实现加密压缩会话文件、原子索引、分页、消息尾部读取和稳定 ID 合并。
- [x] 将会话分类纳入统一 Storage Manager 配额。
- [x] 在现有 `AiController` 暴露会话查询、保存、删除和迁移接口。
- [x] 前端切换为后端真相源，成功迁移后删除旧 `localStorage` 数据。
- [x] 保留现有历史聊天交互，并提供 1000+ 会话的分页加载入口。
- [x] 使用少量高价值测试验证合并/删除、1000+ 分页和损坏索引重建。
- [x] 完成后端测试、前端构建和架构评估自检。

## 5. 架构评估自检

- [x] 符合本地模块化单体目标：只新增一个 Conversation Store 生产服务，没有数据库、Repository 接口、Facade、事件总线或新框架。
- [x] 符合前后端职责边界：后端文件和索引是历史真相源，前端只维护当前页摘要与活动会话消息尾部。
- [x] 符合长会话内存边界：列表不含正文，详情最多返回 200 条，前端每次最多提交 200 条，后端合并保留更早消息。
- [x] 符合安全目标：标题和正文均经过 GZIP + AES-GCM，路径 ID 有白名单校验，错误不返回会话正文和密钥。
- [x] 符合磁盘治理目标：会话文件和索引统一进入 `CONVERSATION` 配额，批量迁移复用一次配额许可，文件使用原子替换。
- [x] 符合迁移安全目标：旧 `localStorage` 只读，全部迁移成功后才删除；部分失败可按稳定会话和消息 ID 幂等重试。
- [x] 符合产品范围：现有设备、日志、应用、文件、Agent Runtime、Tool Gateway 和 MCP 业务链路未修改。
- [x] 验证完成：270 个后端测试通过，前端 Vite 构建通过，实际 JAR 会话创建、分页和有界详情接口返回 HTTP 200。

# M3-02 Working Memory 和上下文预算设计

## 1. 目标与边界

M3-02 只把当前模型调用的上下文组装权迁到后端，不实现 M3-03 分层摘要、M3-05 模型能力注册或 M3-06 完整成本/调用预算器。

- 后端从 Conversation Store 读取最近文本消息，前端普通对话请求不再上传历史正文。
- 按配置的模型上下文窗口、输出 Token 和系统/工具预留 Token 计算历史预算。
- 从最近消息向前选择，超出预算时停止；单条最近消息过长时生成有明确截断标记的有界副本。
- 只选择普通用户和 AI 文本；错误、工具卡片、过程卡片和系统消息不进入 Working Memory。
- 当前请求结束后 Working Memory 释放，不增加新的持久文件或缓存层。

## 2. 最小实现方案

1. 新增一个 `AiConversationContextBuilder`，复用 `AiConversationStoreService` 和 `SensitiveDataMasker`。
2. `DevBridgeProperties` 增加默认 32768 Token 上下文窗口和 10000 Token 系统/工具预留，可由部署配置覆盖；后续 M3-05/M3-06 再自动按模型能力提供精确预算。
3. `AiConversationService` 在创建 Provider 请求前构造一次 Working Memory，同时复用于 Prompt、Agent Event 预算摘要和确认恢复 Checkpoint。
4. 前端发送新问题前确保上一轮会话保存完成，请求中的 `history` 固定为空数组。
5. 不修改 Provider Gateway、Tool Gateway、Agent Task 状态机或现有领域工具。

## 3. 阶段任务

- [x] 实现 Conversation Store 历史读取、角色过滤、脱敏、Token 估算和最近消息选择。
- [x] 将 Working Memory 接入同步、流式和确认恢复主链路。
- [x] 前端停止构造最近 12 条历史，并保证上一轮保存完成后再发起下一轮。
- [x] 模型开始事件记录上下文窗口、历史预算、估算 Token、消息数和截断状态。
- [x] 使用少量测试覆盖最近消息优先、预算截断、非法消息过滤和无存储回退。
- [x] 完成全量测试、前端构建和架构评估自检。

## 4. 架构评估自检

- [x] 后端掌握上下文组装权，普通前端请求的 `history` 固定为空，不再由 UI 决定最近消息和截断规则。
- [x] Working Memory 只在单次请求内存在，复用 M3-01 Conversation Store，没有新增持久文件、缓存、数据库或消息中间件。
- [x] 只新增一个必要生产类 `AiConversationContextBuilder`；`AiConversationService` 仍是唯一 Provider 调用入口，构造器保持 8 个参数。
- [x] Token 预算同时扣除输出、系统/工具预留和当前问题；当前问题自身超限时在 Provider 调用前明确拒绝。
- [x] 上下文只包含脱敏后的 user/assistant 普通文本，不包含工具输出、过程、错误、系统消息、API Key 或确认令牌。
- [x] Provider Prompt 与确认恢复 Checkpoint 使用同一份历史，避免确认前后上下文规则不一致。
- [x] 模型事件只保存窗口、预算、估算量、消息数、来源和截断状态，不持久化历史正文。
- [x] 没有提前实现 M3-03 摘要、M3-05 模型能力注册、M3-06 完整预算器、RAG 或多 Agent。
- [x] 274 个后端测试通过，前端 Vite 构建、后端打包和隔离 JAR 启动通过；JAR 在 1.45 秒内启动并返回 HTTP 200。

# M3-03 对话与任务摘要设计

## 1. 目标与边界

M3-03 为超过 Working Memory 最近消息预算的较早历史生成可追溯摘要，不调用额外模型、不新增摘要数据库，也不把完整历史重新加载到前端。

- 摘要保存在原会话加密文件中，随会话原子更新。
- 摘要分为长期用户约束、较早对话结论和任务/工具执行结果三层。
- 每份摘要包含独立 Schema、版本、来源消息数量、首尾消息 ID 和来源摘要校验值。
- 最近消息继续由 M3-02 直接选择；只有较早历史进入摘要，避免摘要覆盖新鲜上下文。
- 摘要来源前缀每累计一批消息或来源内容变化时重建；来源不变时复用原版本。
- 本阶段采用确定性提取摘要，避免每轮额外模型调用造成延迟、费用和失败耦合；后续可在不改变存储契约的前提下增加模型增强摘要。

## 2. 最小实现方案

1. 新增纯逻辑 `AiConversationSummaryService`，从会话 JSON 中提取用户约束、历史问答结论和工具状态。
2. `AiConversationStoreService` 在保存时生成或复用摘要，并通过内部 Context Snapshot 向 Context Builder 提供摘要和最近消息。
3. `AiConversationContextBuilder` 为摘要预留不超过历史预算三分之一的 Token，剩余预算继续最近消息优先。
4. `AiChatRequest` 和 Agent 确认恢复上下文增加兼容摘要字段，保证确认前后使用同一摘要。
5. 模型事件只记录摘要版本、来源消息数和是否纳入上下文，不记录摘要正文。

## 3. 阶段任务

- [x] 实现分层确定性摘要、来源追踪、摘要版本和批量重建规则。
- [x] 将摘要嵌入现有加密会话文件并兼容 M3-01 旧文件。
- [x] Context Builder 按预算组合摘要和最近消息。
- [x] 确认恢复 Checkpoint 保存并恢复同一摘要。
- [x] 模型事件增加摘要版本、来源消息数和是否使用的观测字段。
- [x] 使用少量测试覆盖约束/工具保留、版本复用、来源变化重建和预算组合。
- [x] 完成全量测试、前端构建和架构评估自检。

## 4. 架构评估自检

- [x] 仅新增一个必要的生产服务 `AiConversationSummaryService`，没有新增数据库、Repository、缓存、调度器或第二套摘要文件。
- [x] 摘要与会话正文一起经 GZIP + AES-GCM 加密并原子替换，M3-01 旧文件缺失摘要字段时自动兼容为空摘要。
- [x] 摘要每累计 20 条较早消息重建，保留最近 40 至 59 条原始消息；Context Store 只返回摘要范围之后的消息，摘要与原始上下文不重叠。
- [x] 摘要最多使用历史 Token 预算的三分之一，最近消息仍然优先；Working Context 将摘要元数据聚合后保持显式构造参数不超过 8 个。
- [x] 工具结果只保留有界状态、命令和结果摘要，并明确标记为“不可信工具证据”，不能被视为授权或新的执行指令。
- [x] 确认恢复复用原 Working Memory 摘要；模型事件只记录摘要版本、来源数量和是否使用，不记录摘要正文。
- [x] 没有额外模型调用、RAG、多 Agent、后台任务框架或新的 Provider 抽象，不改动现有产品交互。
- [x] 277 个后端测试通过，前端 Vite 构建、后端打包和隔离 JAR 启动通过；JAR 在 1.294 秒内启动并返回 HTTP 200。

# M3-04 至 M3-12 收尾设计

## 1. 目标与范围

本阶段一次性完成 Device/Incident Memory、模型能力与预算、结构化输出、Provider 分阶段重试、本地 RAG 和三个固定业务工作流。

- 保持 Spring Boot 模块化单体，不引入数据库、向量数据库、消息中间件或通用工作流引擎。
- 模型能力与预算直接进入现有 `AiRuntimeConfig` 和 Context Builder，不再建第二套 Router。
- 结构化输出复用现有 `ToolSchemaValidator` 和 `ToolCapabilityRouter`，只补齐有界修复和失败语义。
- RAG 使用本地加密文件、有界切分和词法相关性检索，不为当前规模引入 embedding 模型和外部服务。
- 健康检查、日志诊断和构建安装诊断作为固定高层工具，内部步骤仍调用统一 Tool Gateway。

## 2. 最小实现

1. `AiDeviceIncidentMemory` 使用现有 AI 数据根目录和 Storage Manager，保存设备快照、故障特征、证据和验证结果，支持查询和删除。
2. `AiModelCapabilityRegistry` 根据 Provider 和模型名生成上下文、输出、Tool Calling、流式、多模态和 JSON 能力，明确排除 embedding/rerank/图像等非对话模型。
3. 请求预算由模型能力与部署上限取严格值，模型事件记录输入/输出、工具步数、耗时和重试预算。
4. Provider 只在未输出正文且未调用工具时重试；优先同模型一次，再使用已配置且能力匹配的一个备选 Provider。
5. `AiRagBoundary` 升级为本地文档导入、切分、索引、检索、引用、删除和重建边界，检索结果作为不可信证据进入 Prompt。
6. `AiFixedWorkflowService` 按固定顺序执行三个业务流程；每个子步骤有稳定 stepId/toolCallId，敏感的跨端构建安装在流程入口一次确认后才执行。

## 3. 阶段任务

- [x] M3-04：完成设备快照、Incident 记录、查询、删除和磁盘配额。
- [x] M3-05/06：完成模型能力注册、工具过滤、上下文/输出/步骤/时间预算和观测。
- [x] M3-07/08：完成结构化输出有界修复和 Provider 安全分阶段重试/降级。
- [x] M3-09：完成本地知识导入、索引、检索、引用、删除、重建和 Prompt 接入。
- [x] M3-10：完成设备健康检查、证据化评分和快照记录。
- [x] M3-11：完成有界日志窗口、异常识别、知识检索、Incident 记录和进程必停。
- [x] M3-12：完成本机构建、APK Artifact、安装、启动、日志验证和部分成功报告。
- [x] 完成全量后端测试、前端构建、后端打包、隔离启动和 M3 总体架构验收。

## 4. M3 总体架构验收

- [x] 保持 Spring Boot 模块化单体，没有引入数据库、向量数据库、消息中间件、通用工作流引擎或第二套 Provider 抽象。
- [x] Memory、RAG、固定工作流分别复用 Storage Manager、Tool Gateway、Task/Checkpoint、Confirmation、Artifact 和领域工具，没有绕开现有安全控制面。
- [x] RAG 和工具结果继续作为不可信证据进入 Prompt；Memory/RAG 文件采用 GZIP + AES-GCM、原子写入、配额治理和损坏隔离。
- [x] Provider 只在尚未输出正文且尚未调用工具时重试；同模型重试和兼容 Provider 降级均有严格上限。
- [x] 三个固定工作流使用稳定步骤标识；高风险跨端流程只在父流程确认后执行，子工具必须严格匹配父确认绑定，BLOCK 决策不能继承授权。
- [x] 日志流程在成功、异常和取消路径停止采集；跨端失败报告保留已完成步骤和已生成 APK Artifact。
- [x] 仅新增 5 个主要生产类，其他能力在既有边界内扩展，没有为 M3 创建通用 Agent/Workflow 框架或额外前端模块。
- [x] 286 个后端测试全部通过；前端 Vite 构建、后端打包和隔离 JAR 启动通过；JAR 在 0.951 秒内启动、HTTP 返回 200、MCP 注册 57 个工具。

# M4-01 Agent Registry 设计

## 1. 目标与边界

M4-01 只建立后端权威 Agent 定义和最小权限查询，不提前实现 Supervisor、专业 Agent Prompt、多 Agent 编排、模型路由或前端 Agent UI。

- 复用现有 `ToolCapabilityRouter`、`ToolRegistry`、模型能力和数据分类类型。
- Router 不再信任结构化请求中由模型提交的 Agent 能力，Agent 候选只来自后端注册表。
- 首批只注册 M3 已经可实际执行的设备健康、日志诊断和构建安装三个固定工作流 Agent。
- 每个 Agent 明确限定领域、平台、工具能力、访问模式、最高风险、模型约束和数据权限。

## 2. 最小实现

1. 新增单个 `AiAgentRegistry`，使用不可变内存定义，不增加数据库、配置中心、插件框架或 Provider 层。
2. Registry 根据领域、目标平台、模型能力和 Router 已选工具返回候选 Agent，并提供工具授权和数据权限查询。
3. `ToolCapabilityRouter` 保留旧 `agents` 字段用于请求格式兼容，但不把它作为授权来源；`FIXED_WORKFLOW` 和 `AGENT` 模式必须命中后端注册 Agent。
4. 当前普通对话和直接工具模式行为不变，M4-02/M4-03 再接入 Supervisor 和专业 Agent 执行逻辑。

## 3. 阶段任务

- [x] 新增三个固定工作流 Agent 定义及唯一 ID 校验。
- [x] 实现按领域、平台、模型、工具能力、访问模式和风险筛选候选 Agent。
- [x] 实现单 Agent 工具授权和数据权限查询。
- [x] Router 改为只使用后端 Agent Registry，拒绝模型伪造的 Agent 能力。
- [x] 完成精简测试、全量构建和 `architecture-assessment.md` 任务级验收。

## 4. M4-01 架构评估自检

- [x] Agent 定义由后端不可变注册表维护，模型提交的 `agents` 兼容字段不再拥有授权作用。
- [x] 首批 Agent 只对应 M3 已存在的三个固定工作流，没有提前创建 Supervisor、Worker Prompt、委派协议或动态编排器。
- [x] Agent 工具授权复用 Tool Registry 元数据，并同时校验能力、访问模式和静态风险；数据权限复用既有 `DataType`。
- [x] 普通对话和 `DIRECT_TOOL` 路由行为不变，只有 `FIXED_WORKFLOW` 和 `AGENT` 模式要求后端 Agent。
- [x] 仅新增 `AiAgentRegistry` 一个生产类并修改现有 `ToolCapabilityRouter`，没有数据库、配置中心、插件框架或重复 Registry。
- [x] 288 个后端测试全部通过；后端打包和隔离 JAR 启动通过，HTTP 返回 200，MCP 工具仍为 57 个。

# M4-02 Supervisor Agent 设计

## 1. 目标与边界

M4-02 在现有 Agent Task 内提供结构化委派和确定性结束判断，只调度 M4-01 已注册且 M3 已可执行的固定工作流 Agent。

- Supervisor 不获得 ADB、Local Shell、App、File 等底层工具，只能按 `agentId` 委派给 Registry 授权 Worker。
- 不调用额外规划模型，不创建通用图引擎、循环节点、消息总线或新的任务存储。
- 单计划最多 8 步，按输入顺序执行；步骤 ID 唯一，失败是否继续由结构化字段明确决定。
- 高风险计划在 Supervisor 入口统一确认，Worker 子调用仍经过 Tool Gateway 并严格继承父确认绑定。

## 2. 最小实现

1. 新增 `AiSupervisorAgentService`，解析结构化步骤、解析 Worker 唯一授权工具、创建稳定子步骤标识并调用 Tool Gateway。
2. 复用 `AiWorkflowToolAdapter` 注册 `agent.supervisor.execute`，动态风险取全部 Worker 的最高风险。
3. Supervisor 输出每个 Worker 的 Agent、工具、状态、摘要和结构化结果，并明确给出 `SUCCEEDED`、`PARTIAL` 或 `FAILED` 终态。
4. Tool Gateway 现有任务级事件继续记录每个委派工具；Supervisor 额外发布 PLAN 和 STEP 事件，载荷只含标识和结果摘要。
5. Prompt 仅增加“复杂且需要多个固定工作流时优先使用 Supervisor”的产品规则。

## 3. 阶段任务

- [x] 实现有界结构化计划、步骤唯一性和 Worker Agent 校验。
- [x] 实现 Worker 唯一工具解析、最小权限委派和稳定幂等标识。
- [x] 实现动态风险、父确认继承、失败停止/继续和终态判断。
- [x] 复用 Agent Event 发布计划及步骤观测事件。
- [x] 完成精简业务测试、全量验证和任务级架构自检。

## 4. M4-02 架构评估自检

- [x] Supervisor 只接收 `agentId`，不能指定任意工具；Worker 工具由后端 Registry 权限反向解析且必须唯一。
- [x] 所有 Worker 调用继续经过 Tool Gateway 的 Schema、风险、确认、幂等、资源、取消、脱敏、审计和事件链路。
- [x] 计划最多 8 步且无循环；步骤按顺序执行，失败停止/继续和 `SUCCEEDED/PARTIAL/FAILED` 终态均为确定性规则。
- [x] Supervisor 动态风险取全部 Worker 的最高风险；嵌套工作流始终传递最外层确认绑定，避免重复确认且不放宽 BLOCK。
- [x] 计划和 Worker 步骤使用现有 Agent Event，未新增事件总线、任务存储、图引擎或模型调用。
- [x] 仅新增 `AiSupervisorAgentService` 一个生产类，并复用现有 `AiWorkflowToolAdapter` 注册入口，没有提前实现 M4-03 专业 Agent Prompt。
- [x] 290 个后端测试全部通过；后端打包和隔离 JAR 启动通过，HTTP 返回 200，MCP 注册 58 个工具。

# M4-03 Device/Log/App/Local 专业 Agent 设计

## 1. 目标与边界

M4-03 新增四个可被 Supervisor 委派的专业 Worker Agent。为保持当前确认恢复语义，Worker 不在内部启动第二套隐藏模型循环，而是使用专业 Prompt、严格 Operation Schema 和后端工具映射执行单个领域步骤。

- Device Agent 只允许设备查询、健康、截图和连接诊断。
- Log Agent 只允许日志启动、读取、停止、状态和导出。
- App Agent 只允许应用查询、权限、安装、卸载、启动和停止。
- Local Agent 只允许现有 Local Shell 目录、文本、进程和命令操作。
- 一个 Worker 调用只执行一个结构化 Operation；多步骤任务由 Supervisor 顺序委派多个 Worker 步骤。

## 2. 最小实现

1. 扩展 `AiAgentRegistry` 的工具策略，区分 Supervisor 入口能力和 Worker 内部领域能力，并为四个 Agent 保存专业指令和数据权限。
2. 新增单个 `AiSpecialistAgentService`，把 `{operation, arguments}` 映射为白名单工具 ID，校验 Registry 权限后调用 Tool Gateway。
3. 新增单个 `AiSpecialistToolAdapter`，注册四个高层 Agent 工具，提供独立 Operation 枚举、统一输入/输出 Schema 和动态风险。
4. 中高风险操作在专业 Agent 入口确认，底层调用透传最外层确认；底层 BLOCK 仍直接阻断。
5. 输出统一包含 Agent、Operation、工具、状态、摘要、证据和错误，Supervisor 可直接聚合。

## 3. 阶段任务

- [x] 扩展 Registry 的入口工具、Worker 工具、专业指令和数据权限定义。
- [x] 实现四个 Agent 的白名单 Operation 到领域工具映射。
- [x] 实现动态风险、最外层确认继承和统一结构化输出。
- [x] 注册四个高层 Agent 工具并补充产品 Prompt。
- [x] 完成领域隔离、Supervisor 委派、敏感操作和输出 Schema 的精简测试。
- [x] 完成全量验证和 `architecture-assessment.md` 任务级自检。

## 4. M4-03 架构评估自检

- [x] 四个专业 Agent 分别拥有独立入口能力和 Worker 工具能力，跨领域 Operation 在进入 Tool Gateway 前被拒绝。
- [x] 模型只能提交白名单 Operation 和参数，不能提交或伪造底层工具 ID；实际映射由后端固定表和 Registry 双重校验。
- [x] 专业指令、平台、访问模式、最高风险和数据权限集中保存在已有 Agent Registry，没有新增重复配置中心。
- [x] 中高风险操作在专业 Agent 入口展示脱敏参数并确认；底层工具继承最外层确认，动态 BLOCK 仍直接阻断。
- [x] Worker 结果统一包含 Agent、Operation、工具、状态、摘要、证据和错误，可被 Supervisor 直接汇总。
- [x] 未启动嵌套模型工具循环，没有增加第二套 Provider、Checkpoint、确认、事件或 Agent Runtime。
- [x] 仅新增 `AiSpecialistAgentService` 和 `AiSpecialistToolAdapter` 两个生产类，Registry 只做必要字段收敛。
- [x] 292 个后端测试全部通过；后端打包和隔离 JAR 启动通过，HTTP 返回 200，MCP 注册 62 个工具。

# M4-04 至 M4-13 产品化收敛设计

## 最终验收整改

2026-07-16 全面验收发现控制面仍存在双轨、请求幂等和生命周期契约缺口。本轮只在现有模块化单体内整改，不增加新的运行时、数据库、消息队列或通用工作流框架。

### 技术方案

1. Agent Task 创建请求增加客户端幂等键和请求摘要，文件 Store 保存幂等索引；相同请求返回原任务，摘要冲突返回明确冲突。
2. 在现有 Task API 增加输入提交、暂停和恢复命令；输入等待项、超时和恢复点继续使用现有 Task、Checkpoint 和 Event 模型。
3. 任务预算由现有模型能力限制生成，在模型、工具和总时长入口统一校验，不建立第二套预算服务。
4. 确认请求按“Checkpoint -> 确认记录 -> 等待状态”顺序提交，恢复时通过持久状态对账补偿中断窗口；批准绑定会话和一次性令牌。
5. 取消先冻结新步骤并传播取消，清理完成或达到超时后再写入 `CANCELED`；失败结果保留在终态事件中。
6. 旧 ADB/Local Shell REST Controller 适配现有 Tool Gateway，不再直接进入旧执行服务。
7. 增加 Agent Runtime 总开关；关闭时普通聊天回退到既有 Provider 对话入口，Agent Task API 返回能力关闭状态。
8. Electron 显式传入全部 AI 数据根目录；备份恢复增加维护锁并拒绝活动任务期间在线覆盖。
9. Trace 改为尾部有界分页聚合；API Key 默认掩码返回，只有显式受认证揭示请求才返回明文。
10. 前端继续从 `AiChatPanel` 提取任务控制 Hook；真实评测执行路由/工具去重场景，Electron 压力脚本挂载 Markdown 渲染路径。

### 风险与发布

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 新旧前端请求未同步幂等字段 | 中 | 高 | 后端兼容生成临时键，前端升级后使用稳定 Turn 键 |
| 确认数据旧格式缺少会话字段 | 中 | 高 | Codec 兼容读取并要求旧记录重新确认，不自动放行 |
| 统一 Gateway 改变旧 REST 返回细节 | 中 | 中 | 保留旧响应 DTO，仅替换内部执行入口 |
| 在线恢复与运行任务冲突 | 低 | 高 | 维护锁和活动任务检查，要求停机恢复 |

- 发布方式：由 Agent Runtime Feature Flag 灰度启用。
- 回滚条件：任务重复创建、确认无法续跑、取消后进程残留或 Electron 数据目录不可写。
- 兼容性：关闭 Agent Runtime 后保留普通聊天和现有设备管理页面，旧 REST 响应结构保持不变。

### 整改任务

- [x] R1：任务创建幂等、等待输入、暂停恢复和预算强制执行。
- [x] R2：确认提交/恢复一致性、会话令牌绑定和取消终态顺序。
- [x] R3：ADB/Local Shell REST 统一 Tool Gateway，增加 Agent Runtime 回退开关。
- [x] R4：Electron 数据目录、维护恢复锁、Trace 分页和配置密钥最小暴露。
- [x] R5：前端控制职责收敛、真实评测和 Electron Markdown 压力验收。
- [x] R6：全量测试、构建、运行及 `architecture-assessment.md` 复核。

## 1. 目标与边界

剩余 M4 任务在现有 Agent Runtime、Tool Gateway、Provider Gateway、Agent Event、Audit、Conversation Store 和 `AiChatPanel` 边界内完成，不引入第二套 Agent/Provider 运行时、数据库、消息队列、通用工作流引擎或独立前端状态框架。

- Verification Agent 只允许只读证据检查；证据不足必须明确返回，不能把模型结论伪装为验证事实。
- 多模型路由只使用用户已经配置的 Provider，当前模型仍是首选；降级只能发生在没有正文输出和工具副作用的安全阶段。
- 前端拆分只提取会话列表、任务视图、输入区、过程时间线和历史存储 Hook，不改变现有视觉与后端权威控制语义。
- 长回复使用分段缓冲和稳定 Markdown 块；流式期间只更新尾段，完整正文仍可持久化和显示。
- Trace 复用 Agent Event 和 Tool Audit，禁止重复保存 Prompt、API Key、完整日志或工具正文。
- 测试、迁移、压力和灰度能力保持轻量，以产品关键路径和可回退为目标，不建设独立测试平台或发布平台。

## 2. 最小实现

1. 在现有 Agent Registry、专业工具 Adapter 中注册 Verification Agent；其检查项只能委派 Device/Log/App 的只读 Operation，并输出 `VERIFIED/PARTIAL/INSUFFICIENT`。
2. 在现有 Provider Gateway 前增加一个轻量模型路由器，按能力、质量、成本和短期可用性排序已配置模型；路由原因、实际 Provider、重试和降级进入现有任务事件。
3. 将 `AiChatPanel` 的展示和本地会话状态职责收敛到两个前端模块；请求、确认、取消和恢复仍留在控制组件。
4. AI 消息增加可选内容分段；流式缓冲使用数组和有界尾段，Markdown 只解析和挂载视口附近分段，完整正文继续保留和持久化。
5. 在现有 Agent Task API 增加 Trace 查询，聚合任务事件和工具审计；前端提供当前任务的轻量观测抽屉。
6. 使用少量业务级测试覆盖多工具、确认恢复、取消、并发、注入隔离、模型回归和长时数据边界；评测报告生成在构建输出目录，不引入在线评测服务。
7. 复用各 Store 已有 Schema 迁移、索引重建和损坏隔离，增加统一状态/备份入口；恢复以重建可恢复索引和隔离损坏单文件为主。
8. 增加 M4 功能开关：多 Agent、模型降级、Trace 和本地维护可独立关闭；关闭后普通对话和现有设备管理保持可用。

## 3. 阶段任务

- [x] M4-04：实现只读 Verification Agent、证据状态和 Supervisor 委派。
- [x] M4-05：实现多模型排序、安全降级、可用性冷却和路由原因记录。
- [x] M4-06：拆分会话列表、任务视图、输入区、过程时间线和会话 Store Hook。
- [x] M4-07：实现分段流式存储、稳定 Markdown 实时解析和屏外懒布局。
- [x] M4-08：实现 Task/Step/Tool/Model/RAG Trace 查询与前端观测面板。
- [x] M4-09：建立精简端到端 Agent 测试基座并覆盖关键恢复和安全路径。
- [x] M4-10：建立内置任务数据集、核心指标和 Provider 能力回归报告。
- [x] M4-11：实现本地数据维护状态、备份、索引恢复和损坏隔离验收。
- [x] M4-12：完成 1M Token、1000 会话、高频事件、并发工具和资源残留压力验收。
- [x] M4-13：实现功能开关、关闭路径和回退说明。
- [x] 完成全量后端测试、前端构建、Electron 检查、后端打包、隔离启动和架构评估。

## 4. 架构验收约束

- [x] 所有 Agent 工具仍经过统一 Tool Gateway，确认、幂等、取消、审计和事件没有旁路。
- [x] 前端不构造 Agent Prompt、不决定恢复节点，也不保存第二份权威任务状态。
- [x] Provider 降级不重放已产生正文或已执行工具的调用。
- [x] Trace、评测和维护接口不泄露密钥、完整 Prompt、完整日志和真实受保护文件路径。
- [x] 长回复不截断业务正文，流式 Markdown 保持实时解析，渲染和持久化避免高频全量复制。
- [x] 功能开关关闭后普通对话、设备管理和已有固定工作流不受影响。
- [x] 新增生产文件和测试文件数量保持必要且可解释，没有为不确定未来能力创建通用框架。

## 5. M4-04 至 M4-13 验收结果

- [x] Verification Agent 仅接受 Device/Log/App 只读 Operation，多个检查和结论引用输出 `VERIFIED/PARTIAL/INSUFFICIENT`；Supervisor 可按 Registry 委派。
- [x] 模型路由复用现有 Provider Gateway，按能力、质量、成本和 30 秒故障冷却排序；实际 Provider、模型、重试和选型原因进入 Agent Event。
- [x] `AiChatPanel` 降到 2000 行以内，纯展示、消息 Store、长回复分段和格式化职责已拆分；后端继续掌握 Prompt、确认和恢复。
- [x] 1M Token 等效正文完整保留为 668 个稳定 Markdown 段，最大段 5997 字符；128MB Node 堆下使用约 32MB，无业务截断。
- [x] Trace API 聚合最多 1000 条有序 Agent Event 和脱敏工具审计，前端当前任务观测抽屉展示模型、RAG、工具、重试和错误摘要。
- [x] 复用既有 Fake Provider、SSE、故障注入、并发、重启和 Prompt 注入测试基座；最终总计 313 个后端测试通过。
- [x] Provider 回归报告输出到 `target/agent-evaluation/agent-evaluation-report.json`，包含 Agent 路由准确率、Provider 能力兼容率、重复调用率和基线差异。
- [x] 本地维护提供受认证状态、流式 ZIP 备份、路径/配额校验恢复和会话索引重建；单会话损坏继续隔离。
- [x] 多 Agent、模型降级、Trace 和本地维护均可独立关闭；关闭多 Agent 后 MCP 工具从 63 回落到 57，首页仍返回 HTTP 200。
- [x] 前端构建、Electron 语法和资源准备、后端打包、隔离 JAR 启动均通过；启用 M4 时 MCP 注册 63 个工具。
- [x] Electron 压力验收完整保留 4,000,000 字符（约 1M Token、668 段），仅挂载 16 个视口附近分段，Renderer 私有内存约 214 MiB，无正文截断或 OOM。

# 最终验收缺口整改（2026-07-16）

## 1. 目标与边界

本轮只修复全面验收确认的安全、生命周期、恢复、预算和持久化缺口，不新增 Agent 框架、数据库、消息队列或前端状态框架。

1. Local Shell 授权必须先识别完整 Shell 语义，任何复合命令不得通过前缀授权绕过。
2. Provider 外发按真实数据来源分类，工具结果再次进入模型前必须执行同一外发策略。
3. 取消必须传播到真实子进程，并在清理超时后确定性进入终态。
4. 所有非终态任务在重启后必须恢复等待状态、重新入队或明确失败；重复恢复不得创建第二执行流。
5. 现有能力路由、暂停、恢复和等待输入 API 接入真实聊天链路，不建立第二套控制面。
6. 失败快照和预算补齐规格要求的必要字段；工具调用计数使用生产事件持久化。
7. 备份恢复采用目录快照替换和失败回滚；长对话保存避免不必要的多份完整正文副本。

## 2. 最小实施方案

- 扩展现有风险分类器的复合语义识别，不引入 Shell 解析框架。
- 在 Provider 请求中保留分类型外发项，并用工具回调包装器检查工具输出；确认继续复用现有 Confirmation/Checkpoint。
- 让 ADB/Local Shell 同步执行路径注册真实进程句柄；取消协调器使用现有受控执行器和固定超时。
- 扩展现有启动恢复服务处理全部非终态；恢复接口使用状态转换结果判断是否真正启动续跑。
- 普通聊天构造工具范围时调用现有 Capability Router；前端复用现有 Task API 和 SSE 解析器。
- 在现有 Task/Checkpoint/Event 模型中补充错误详情和预算计数，不新增独立预算服务。
- 恢复时先将当前目录切换为回滚目录，再原子替换备份快照；成功后清理回滚目录。

## 3. 整改任务

- [x] F1：修复 Local Shell 授权绕过和数据外发分类/工具结果治理。
- [x] F2：修复真实进程取消、取消超时和取消/完成竞态。
- [x] F3：补齐全部非终态重启恢复及恢复命令幂等。
- [x] F4：接入能力路由、暂停/恢复/等待输入产品闭环。
- [x] F5：补齐结构化失败结果、任务预算和生产工具事件。
- [x] F6：实现真正的快照恢复并降低长对话持久化瞬时复制。
- [x] F7：完成精简测试、全量构建和架构目标复验。

## 4. 最终验收结果

- [x] Local Shell 复合语义先于用户授权匹配，换行、反引号、重定向和复合操作不能通过低风险前缀绕过。
- [x] 用户输入、设备上下文、历史、摘要、RAG 和工具结果按来源执行外发策略，需确认数据绑定当前任务和模型调用。
- [x] ADB、Local Shell 和 Provider 取消传播到真实进程树；清理超过固定时限后任务仍可确定性收束。
- [x] 全部非终态任务在启动时恢复等待、重新入队或明确失败；重复恢复、重复确认和重复输入不创建第二执行流。
- [x] 普通聊天接入能力路由，暂停、恢复、取消和等待输入均复用同一 Agent Task、Checkpoint 和 SSE 主链路。
- [x] 失败结果、模型/工具/步骤/输出/并发/成本预算和生产调用事件均可持久化查询。
- [x] 备份恢复采用目录快照替换和失败回滚；长回复在页面状态、历史文件和摘要/上下文读取中保持分段或有界副本。
- [x] 后端 318 个测试通过；Vite 构建、1M Token Node 压测、Electron 渲染及重复持久化压测、后端打包和独立 JAR 启动通过。
- [x] 后端重启导致控制面 Cookie 轮换时，同源浏览器可自动恢复；跨域网页和无 Origin 本地请求仍需有效令牌。
