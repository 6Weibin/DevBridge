> 来源: `changes/active/ai-assistant-research/spec.md`
> 生成时间: 2026-07-06
> 阶段: design

### Why

**背景与现状**

DevBridge 当前是 React/Vite 前端 + Spring Boot 本机服务 + Electron 包装的本地设备管理工具。前端主业务集中在 `DevBridge-Front/src/app/App.tsx`，其中已经维护当前设备、页签、实时日志桶、日志采集状态、文件选择和应用列表等状态。后端已经通过 `LogController`、`LogStreamService`、`LogCaptureService` 提供 Android/iOS 实时日志 SSE、日志落盘和导出能力，统一异常通过 `BusinessException` 和 `ApiExceptionHandler` 返回 `ApiError`。

AI 助手需要叠加到现有界面上，但不能把 Provider 调用、配置表单、日志分析逻辑塞进设备/日志业务里。设计采用前端独立 `ai` 目录承载浮动入口和侧边栏，后端独立 `ai` 包承载配置、Provider、脱敏和分析服务；现有 `App.tsx` 只提供只读上下文和少量采集回调，现有日志 SSE 协议保持不变。

**设计目标 / 非目标**

| 类型 | 说明 |
|------|------|
| ✅ 目标 | 在右下角新增 AI 浮动入口和右侧浮动侧边栏，关闭后主界面状态不变 |
| ✅ 目标 | 后端提供 AI 配置、连接测试、普通对话、日志分析接口，前端不持久化 API Key |
| ✅ 目标 | 通过 OpenAI-compatible 基线统一 DeepSeek、Qwen、OpenAI、GLM、ERNIE、自定义 Provider |
| ✅ 目标 | 日志分析复用现有前端日志快照和采集控制，不改现有日志 SSE 响应结构 |
| ✅ 目标 | 对发送给 AI 的日志上下文做脱敏、条数限制、字符限制和错误隔离 |
| ❌ 非目标 | 本期不落地 RAG 索引、长期会话数据库、多用户权限、费用统计、云端管理后台 |
| ❌ 非目标 | 不让浏览器直接调用第三方 AI Provider |
| ❌ 非目标 | 不修改现有设备、文件、应用和日志接口契约 |
| ❌ 非目标 | 本期不实现 RAG 索引、复杂 Agent 编排、MCP 工具市场和多模型观测看板 |

### What

#### 技术方案

**架构决策**

| 模块 | 职责 | 依赖 |
|------|------|------|
| `DevBridge-Front/src/app/ai/AiAssistantShell.tsx` | AI 入口总控，负责浮动按钮、配置状态加载、侧边栏/配置弹窗切换 | `aiApi.ts`、`AiConfigDialog`、`AiChatPanel`、`App.tsx` 传入的上下文 |
| `AiConfigDialog.tsx` | AI 配置表单、连接测试、保存配置、脱敏错误展示 | `aiApi.ts`、现有 `Dialog`、`Input`、`Select`、`Button` |
| `AiChatPanel.tsx` | 普通对话、日志分析触发、进行中状态、取消当前请求、分析结果展示 | `aiApi.ts`、当前设备上下文、日志快照、采集回调 |
| `aiApi.ts` | 统一封装 AI REST API 和前端错误转换 | 现有 `API_BASE` 约定和 `ApiError` 格式 |
| `com.devbridge.server.api.AiController` | 对外提供 AI 配置、连接测试、普通对话、日志分析接口 | `AiConfigService`、`AiConversationService`、`AiLogAnalysisService` |
| `com.devbridge.server.ai.config` | 管理本地 AI 配置、API Key 加密存储、状态脱敏 | `DevBridgeProperties`、JDK 加密/文件 API |
| `com.devbridge.server.ai.provider` | Spring AI 模型配置、ChatClient 调用、统一错误映射、超时控制 | Spring AI、`SensitiveDataMasker` |
| `com.devbridge.server.ai.analysis` | 构造日志分析请求、执行日志脱敏和上下文限制、规范化分析结果 | `AiProviderGateway`、`SensitiveDataMasker` |
| `com.devbridge.server.ai.tool` | 预留 DevBridge 工具能力注册边界，本期仅定义日志分析所需的受控入口 | Spring AI Tool Calling、现有设备/日志服务 Facade |
| `com.devbridge.server.ai.rag` | 预留 RAG 文档、向量存储、检索增强边界，本期不落索引与向量库 | Spring AI RAG、Vector Store 扩展点 |
| `com.devbridge.server.ai.observation` | 预留 AI 调用观测事件边界，本期只记录基础耗时和错误摘要 | Spring AI Observability、应用日志 |
| `SensitiveDataMasker` | 对日志、Provider 错误、配置状态做敏感信息脱敏 | 正则规则，不依赖设备业务 |
| `DevBridge-Electron/src/main.js` | 打包态传入 AI 配置目录，保证配置写入 userData 而非应用资源目录 | 现有后端启动参数 |

模块关系：

```text
App.tsx
  └─ AiAssistantShell
       ├─ AiConfigDialog ── aiApi ── AiController ── AiConfigService
       └─ AiChatPanel ──── aiApi ── AiController ── AiConversationService ── AiProviderGateway ── Spring AI ChatClient
                         └─────── AiController ── AiLogAnalysisService ── SensitiveDataMasker

Log page state in App.tsx ── read-only snapshot/callback ── AiChatPanel
Existing LogController/LogStreamService contract remains unchanged.
```

**数据模型变更**

不新增数据库表。新增本地文件型配置：

| 操作 | 表/实体 | 字段 | 类型 | 约束 | 说明 |
|------|---------|------|------|------|------|
| 新增 | `DevBridgeProperties` | `aiConfigRoot` | `String` | 默认 `target/devbridge-ai` | 后端 AI 配置和本地密钥材料目录 |
| 新增 | `ai-config.json` | `provider` | `String` | NOT BLANK | Provider 类型：`openai/deepseek/qwen/glm/ernie/custom-openai-compatible`，映射到 Spring AI ChatModel 配置 |
| 新增 | `ai-config.json` | `apiUrl` | `String` | NOT BLANK，URL 校验 | Provider API 基础地址 |
| 新增 | `ai-config.json` | `model` | `String` | NOT BLANK | 模型名称 |
| 新增 | `ai-config.json` | `encryptedApiKey` | `String` | NOT BLANK | AES-GCM 加密后的 API Key |
| 新增 | `ai-config.json` | `updatedAt` | `Instant` | NOT NULL | 配置更新时间 |
| 新增 | `ai-keystore.p12` | `ai-config-key` | SecretKey | 本机文件权限限制 | 加密 API Key 的本地随机密钥 |

后端视图对象：

| 类型 | 字段 | 说明 |
|------|------|------|
| `AiConfigStatus` | `configured`、`provider`、`model`、`apiUrlHost`、`updatedAt` | 配置状态，不包含 API Key |
| `AiConfigRequest` | `provider`、`apiUrl`、`apiKey`、`model` | 保存配置入参 |
| `AiConnectionTestResult` | `available`、`message`、`provider`、`model` | 连接测试结果 |
| `AiChatRequest` | `message`、`deviceContext` | 普通对话入参 |
| `AiChatResponse` | `answer`、`provider`、`model`、`elapsedMillis` | 普通对话出参 |
| `AiLogAnalysisRequest` | `question`、`deviceContext`、`logs`、`limits` | 日志分析入参，日志由前端快照提供 |
| `AiLogAnalysisResponse` | `summary`、`evidence`、`cause`、`actions`、`confidence`、`context` | 日志分析结果 |
| `AiLogLine` | `timestamp`、`level`、`pid`、`tag`、`message` | 前后端共用的日志上下文行 |
| `AiDeviceContext` | `platform`、`serial`、`model`、`osVersion`、`status` | 当前设备上下文，后端返回前先脱敏序列号 |

**接口定义**

| 接口 | 方法 | 路径/签名 | 入参 | 出参 | 说明 |
|------|------|----------|------|------|------|
| AI 配置状态 | GET | `/api/ai/config/status` | 无 | `AiConfigStatus` | 判断是否已配置，不返回 API Key |
| 保存 AI 配置 | PUT | `/api/ai/config` | `AiConfigRequest` | `AiConfigStatus` | 校验并保存 Provider 配置，API Key 加密落盘 |
| 测试 AI 连接 | POST | `/api/ai/config/test` | `AiConfigRequest` | `AiConnectionTestResult` | 使用入参临时测试，不覆盖已保存配置 |
| 普通 AI 对话 | POST | `/api/ai/chat` | `AiChatRequest` | `AiChatResponse` | 使用已保存配置发起普通文本对话 |
| 日志分析 | POST | `/api/ai/analyze/logs` | `AiLogAnalysisRequest` | `AiLogAnalysisResponse` | 接收前端日志快照，后端再次脱敏和限制后调用 AI |

关键后端 public 方法签名：

| 类 | 方法签名 | 说明 |
|----|----------|------|
| `AiConfigService` | `AiConfigStatus status()` | 获取脱敏配置状态 |
| `AiConfigService` | `AiConfigStatus save(AiConfigRequest request)` | 保存配置并加密 API Key |
| `AiConfigService` | `AiRuntimeConfig requireConfigured()` | 获取可调用 Provider 的完整运行时配置 |
| `AiConfigService` | `AiConnectionTestResult test(AiConfigRequest request)` | 使用临时配置测试连接 |
| `AiProviderGateway` | `AiProviderResponse chat(AiProviderRequest request)` | 屏蔽 Spring AI 细节的文本对话统一入口 |
| `SpringAiProviderGateway` | `AiProviderResponse chat(AiProviderRequest request)` | 基于 Spring AI `ChatClient` 的调用实现 |
| `AiToolRegistry` | `List<ToolCallback> toolCallbacks(AiToolScope scope)` | 预留工具调用注册入口，本期只暴露受控日志分析工具范围 |
| `AiObservationRecorder` | `void record(AiObservationEvent event)` | 记录 AI 调用耗时、模型、Provider、错误摘要 |
| `AiConversationService` | `AiChatResponse chat(AiChatRequest request)` | 普通对话业务入口 |
| `AiLogAnalysisService` | `AiLogAnalysisResponse analyze(AiLogAnalysisRequest request)` | 日志分析业务入口 |
| `SensitiveDataMasker` | `String maskText(String text)` | 对任意文本脱敏 |
| `SensitiveDataMasker` | `List<AiLogLine> maskLogs(List<AiLogLine> logs)` | 对日志列表脱敏 |

关键前端类型和组件契约：

| 对象 | 签名 | 说明 |
|------|------|------|
| `AiAssistantShellProps` | `{ backendOnline: boolean; device: Device \| null; streaming: boolean; getRecentLogs: () => LogLine[]; onStartLogCapture: () => void; }` | `App.tsx` 传入的最小上下文 |
| `AiConfigDialog` | `({ open, onOpenChange, onConfigured })` | 配置弹窗 |
| `AiChatPanel` | `({ device, streaming, getRecentLogs, onStartLogCapture })` | 侧边栏主体 |
| `aiApi.getConfigStatus` | `(): Promise<AiConfigStatus>` | 获取配置状态 |
| `aiApi.saveConfig` | `(request: AiConfigRequest): Promise<AiConfigStatus>` | 保存配置 |
| `aiApi.testConfig` | `(request: AiConfigRequest): Promise<AiConnectionTestResult>` | 连接测试 |
| `aiApi.chat` | `(request: AiChatRequest, signal: AbortSignal): Promise<AiChatResponse>` | 普通对话 |
| `aiApi.analyzeLogs` | `(request: AiLogAnalysisRequest, signal: AbortSignal): Promise<AiLogAnalysisResponse>` | 日志分析 |

**错误处理策略**

| 错误类型 | 处理方式 | HTTP状态码/异常类 |
|---------|---------|----------------|
| AI 未配置 | 返回稳定错误码，前端打开配置弹窗 | `BusinessException("AI_NOT_CONFIGURED", ..., 409)` |
| 配置字段缺失 | 返回字段级提示，前端保留输入 | `BusinessException("AI_CONFIG_INVALID", ..., 400)` |
| API URL 不允许 | 禁止保存或测试，提示 URL 协议/主机不允许 | `BusinessException("AI_API_URL_REJECTED", ..., 400)` |
| Provider 鉴权失败 | 脱敏 Provider 错误后返回 | `BusinessException("AI_PROVIDER_AUTH_FAILED", ..., 401)` |
| Provider 限流 | 返回限流提示，不重试多次 | `BusinessException("AI_PROVIDER_RATE_LIMITED", ..., 429)` |
| Provider 超时 | 终止请求，返回超时提示 | `BusinessException("AI_PROVIDER_TIMEOUT", ..., 504)` |
| Provider 响应不兼容 | 返回格式不兼容提示 | `BusinessException("AI_PROVIDER_RESPONSE_INVALID", ..., 502)` |
| 日志为空 | 不调用 Provider，返回可读提示 | `BusinessException("AI_LOG_CONTEXT_EMPTY", ..., 400)` |
| 平台不支持日志分析 | 不调用 Provider，返回平台不支持 | `BusinessException("AI_LOG_PLATFORM_UNSUPPORTED", ..., 400)` |
| 未预期异常 | 复用全局异常处理，不暴露请求体和日志正文 | `ApiExceptionHandler.handleUnexpectedException` |

#### 关键决策与理由

| 决策 | 可选方案 | 选择 | 理由 |
|------|---------|------|------|
| Provider 框架 | A: 轻量 OpenAI-compatible 客户端 / B: Spring AI / C: LangChain4j / D: Semantic Kernel Java / E: 前端直连 | B | 后续已明确需要工具调用、RAG、复杂 Agent 编排、多模型观测；Spring AI 与现有 Spring Boot 后端契合，官方支持 ChatClient、Tool Calling、RAG、Vector Store、Observability、MCP，能减少后续重构 |
| 日志上下文来源 | A: 前端传递当前日志快照 / B: 修改 `LogStreamService` 建全局日志缓存 / C: AI 后端单独启动日志进程 | A | 对现有日志链路改动最小；不会抢占或关闭用户手动日志采集；后端仍负责脱敏和限制 |
| AI 采集控制 | A: 前端 AI 组件通过 `onStartLogCapture` 调用现有采集逻辑 / B: 后端新增独立采集会话 / C: 直接改日志页按钮逻辑 | A | 复用现有 EventSource 和状态管理，避免同设备多进程冲突；主界面只增加最小回调 |
| API Key 存储 | A: 后端本地加密文件 / B: 前端 localStorage / C: 明文 application.yml | A | 前端不接触持久化 Key；不硬编码密钥；JDK 原生加密和文件权限即可满足本地工具的第一阶段安全要求 |
| AI 接口形态 | A: REST JSON + AbortController / B: EventSource 流式 / C: WebSocket | A | spec 只要求进行中状态和取消当前回复；REST 最简单，能复用现有错误格式；Spring AI 流式能力留到后续 Delta Spec |
| Agent/RAG 扩展边界 | A: 当前模块预留 Spring AI tool/rag/observation 子包 / B: 当前完全不考虑 / C: 本期直接实现 RAG 和 Agent | A | 用户已明确后续方向，先把包边界和 Gateway 定好，避免后续推倒重来；本期不实现超出 spec 的 RAG 索引和 Agent 编排 |
| 配置目录 | A: `devbridge.ai-config-root` / B: 写应用资源目录 / C: 写前端目录 | A | 开发态和 Electron 打包态都可配置；打包应用资源目录不应写入用户配置 |
| Provider URL 校验 | A: HTTPS 默认允许，HTTP 仅允许 loopback/private / B: 任意 URL / C: 固定厂商白名单 | A | 兼容云模型和本地/内网模型，同时降低 SSRF 与误配风险 |

#### 风险与权衡

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| 前端日志快照不是完整设备日志 | 中 | 中 | UI 展示本次日志条数和范围；无日志时提示先启动采集；后续如需全量采集走 Delta Spec |
| API Key 本地加密依赖同机密钥文件 | 中 | 中 | 配置目录设置用户级文件权限；状态接口不回显；日志不打印；后续可接入 OS Keychain |
| Spring AI 版本与 Spring Boot 版本兼容 | 中 | 中 | 编码前锁定与当前 Spring Boot 3.3.7 兼容的 Spring AI 版本；依赖只引入 ChatClient 所需 starter，RAG/Vector Store 依赖后续按需添加 |
| Provider OpenAI-compatible 细节差异 | 中 | 中 | 通过 Spring AI ChatModel 和 Gateway 统一错误映射；Provider 类型保留，先验证文本 Chat Completions 基线 |
| AI 分析耗时影响用户体验 | 中 | 中 | 前端支持取消当前请求；后端设置请求超时；失败不影响设备管理功能 |
| AI UI 覆盖已有弹窗 | 低 | 中 | 浮动入口和侧边栏层级低于确认删除、卸载、重命名等业务弹窗 |
| 现有 `App.tsx` 继续膨胀 | 中 | 高 | 只新增 Shell 挂载、日志快照读取和采集回调，AI 业务逻辑全部放入 `src/app/ai` |

**发布策略**：
- **发布方式**：一次性发布。AI 模块默认未配置时只展示配置入口，不影响现有功能。
- **回滚条件**：启动后端失败、现有设备列表不可用、现有日志采集不可用、前端构建失败。
- **数据迁移**：无数据库迁移。新增本地 AI 配置文件，删除配置目录即可回滚 AI 配置。

#### 变更文件清单

| 文件路径 | 操作 | 变更说明 |
|---------|------|---------|
| `DevBridge-Front/src/app/App.tsx` | 修改 | 挂载 `AiAssistantShell`，提供当前设备、采集状态、日志快照、启动采集回调 |
| `DevBridge-Front/src/app/ai/aiTypes.ts` | 新增 | 定义 AI 配置、对话、日志分析前端类型 |
| `DevBridge-Front/src/app/ai/aiApi.ts` | 新增 | 封装 AI 后端 API 和错误转换 |
| `DevBridge-Front/src/app/ai/AiAssistantShell.tsx` | 新增 | 浮动入口、配置状态判断、侧边栏和配置弹窗切换 |
| `DevBridge-Front/src/app/ai/AiConfigDialog.tsx` | 新增 | AI 配置表单和连接测试 |
| `DevBridge-Front/src/app/ai/AiChatPanel.tsx` | 新增 | 普通对话、日志分析和结果展示 |
| `DevBridge-Server/src/main/java/com/devbridge/server/config/DevBridgeProperties.java` | 修改 | 新增 `aiConfigRoot` 配置项 |
| `DevBridge-Server/src/main/resources/application.yml` | 修改 | 新增 AI 配置目录默认值 |
| `DevBridge-Server/src/main/java/com/devbridge/server/api/AiController.java` | 新增 | AI 配置、测试、对话、日志分析 REST 接口 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/config/*` | 新增 | AI 配置模型、加密存储、状态脱敏 |
| `DevBridge-Server/pom.xml` | 修改 | 引入与 Spring Boot 3.3.7 兼容的 Spring AI 依赖管理和 ChatClient 所需 starter |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/provider/*` | 新增 | Provider 请求/响应模型和 Spring AI Gateway |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/tool/*` | 新增 | 预留工具调用注册边界，本期只保留受控日志分析工具范围 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/rag/*` | 新增 | 预留 RAG 边界和接口类型，本期不接向量库 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/observation/*` | 新增 | 预留 AI 调用观测事件边界 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/analysis/*` | 新增 | 日志分析服务、日志限制、结果模型 |
| `DevBridge-Server/src/main/java/com/devbridge/server/ai/security/SensitiveDataMasker.java` | 新增 | 敏感信息脱敏 |
| `DevBridge-Server/src/test/java/com/devbridge/server/ai/**` | 新增 | AI 配置、Provider、脱敏、日志分析测试 |
| `DevBridge-Electron/src/main.js` | 修改 | 启动后端时传入 `--devbridge.ai-config-root` 到 userData 目录 |

### How

#### 任务拆分

| 任务名称 | 详细描述 | 关联设计章节 | 计划工作量(人天) |
|----------|---------|------------|--------------|
| 【配置模型】(后端) 新增 AI 配置模型和配置目录 | 1. 扩展 `DevBridgeProperties` 的 AI 配置目录<br>2. 定义配置请求、状态、运行时配置模型<br>3. 配置开发态默认目录<br>4. Electron 启动时传入 userData 下配置目录 | 数据模型变更 | 1 |
| 【配置存储】(后端) 实现 AI 配置加密保存和状态脱敏 | 1. 保存 Provider、API URL、模型和加密 API Key<br>2. 状态接口不回显 API Key<br>3. 配置目录使用用户级文件权限<br>4. 覆盖保存、读取、更新、脱敏测试 | 数据模型变更 / 接口定义 | 1.5 |
| 【AI框架】(后端) 引入 Spring AI 并建立 Gateway 边界 | 1. 锁定与当前 Spring Boot 兼容的 Spring AI 版本<br>2. 引入 ChatClient 所需依赖<br>3. 定义 `AiProviderGateway` 屏蔽框架细节<br>4. 预留 tool/rag/observation 子包边界 | 架构决策 / 数据模型变更 | 1.5 |
| 【Provider】(后端) 基于 Spring AI 实现对话调用和错误映射 | 1. 定义 Provider 请求/响应模型<br>2. 基于 Spring AI ChatClient 实现普通文本对话调用<br>3. 映射鉴权、限流、超时、格式不兼容错误<br>4. 覆盖成功和失败测试 | 架构决策 / 错误处理策略 | 2 |
| 【AI接口】(后端) 实现配置、连接测试、普通对话 REST 接口 | 1. 新增 `AiController` 配置状态、保存、测试接口<br>2. 新增普通对话接口<br>3. 复用 `BusinessException` 和 `ApiError`<br>4. 覆盖接口入参校验和错误响应测试 | 接口定义 | 1.5 |
| 【脱敏分析】(后端) 实现日志脱敏和日志分析服务 | 1. 对日志正文、设备序列号、Provider 错误脱敏<br>2. 限制日志条数、字符数和回复长度<br>3. 构造日志分析请求并规范化结果<br>4. 覆盖空日志、平台不支持、截断、脱敏测试 | 数据模型变更 / 接口定义 | 2 |
| 【AI入口】(前端) 实现浮动入口和右侧侧边栏容器 | 1. 新增 AI Shell 组件<br>2. 挂载到 `App.tsx` 根容器<br>3. 传入当前设备、采集状态、日志快照、启动采集回调<br>4. 验证关闭后主界面状态保持 | 架构决策 / 变更文件清单 | 1.5 |
| 【AI配置】(前端) 实现配置弹窗和连接测试 | 1. 提供 Provider、API URL、API Key、模型输入<br>2. 调用状态、保存、测试接口<br>3. 展示必填校验和脱敏错误<br>4. 保存后不回显 API Key 明文 | 接口定义 | 1 |
| 【AI对话】(前端) 实现普通对话和取消当前请求 | 1. 调用普通对话接口<br>2. 展示用户消息、AI 回复和进行中状态<br>3. 使用 AbortController 取消当前请求<br>4. Provider 失败时展示可读错误 | 接口定义 / 错误处理策略 | 1 |
| 【日志分析】(前端) 实现日志快照分析交互 | 1. 从现有日志桶读取当前设备最近日志<br>2. 无日志时引导启动采集<br>3. 调用日志分析接口并展示结构化结果<br>4. 验证不改日志页过滤和采集状态 | 架构决策 / 接口定义 | 1.5 |
| 【联调回归】(全栈) 完成端到端联调和回归验证 | 1. 联调配置、连接测试、普通对话、日志分析<br>2. 验证后端不可达、Provider 失败、设备断开<br>3. 验证 API Key 和日志脱敏<br>4. 验证设备、文件、应用、日志功能无回归 | 风险与权衡 / 发布策略 | 1.5 |
| **合计** | | | **16** |

**任务排序原则**：先配置模型和存储，再 Provider 和后端接口，再前端入口和交互，最后联调回归。

**任务依赖**：

```text
- 【配置模型】(后端) 新增 AI 配置模型和配置目录
- 【配置存储】(后端) 实现 AI 配置加密保存和状态脱敏 ← depends: 【配置模型】
- 【AI框架】(后端) 引入 Spring AI 并建立 Gateway 边界 ← depends: 【配置模型】
- 【Provider】(后端) 基于 Spring AI 实现对话调用和错误映射 ← depends: 【配置存储】, 【AI框架】
- 【AI接口】(后端) 实现配置、连接测试、普通对话 REST 接口 ← depends: 【配置存储】, 【Provider】
- 【脱敏分析】(后端) 实现日志脱敏和日志分析服务 ← depends: 【Provider】
- 【AI入口】(前端) 实现浮动入口和右侧侧边栏容器
- 【AI配置】(前端) 实现配置弹窗和连接测试 ← depends: 【AI接口】
- 【AI对话】(前端) 实现普通对话和取消当前请求 ← depends: 【AI接口】, 【AI入口】
- 【日志分析】(前端) 实现日志快照分析交互 ← depends: 【脱敏分析】, 【AI入口】
- 【联调回归】(全栈) 完成端到端联调和回归验证 ← depends: all
```

### Verify

设计自检：
- [x] 所有 spec 功能需求都有对应的技术方案
- [x] 所有技术决策都有理由
- [x] 接口定义完整（入参、出参、异常）
- [x] 数据模型变更明确（新增/修改/删除）
- [x] 任务拆分覆盖全部设计内容
- [x] 任务总量与需求规模匹配
- [x] 无实现代码（只有签名和结构）
- [x] 已按 .best-practices/ 约束检查 Risks/Rollout

### Impact

- 前端主界面：仅在 `App.tsx` 增加 AI Shell 挂载、日志快照读取和启动采集回调，不迁移现有业务状态。
- 前端 AI 模块：新增独立 `src/app/ai` 目录，承载配置、对话、日志分析 UI 和 API 封装。
- 后端 API：新增 `/api/ai/**`，不修改现有 `/api/devices/**`、`/api/logs/**`、`/api/runtime/**` 契约。
- 后端 AI 模块：新增配置、Provider、脱敏、日志分析服务，复用现有统一异常模型。
- Electron：新增 AI 配置目录启动参数，避免打包态写入应用资源目录。
- 数据库变更：否。
- 外部依赖变更：是，引入 Spring AI ChatClient 所需依赖；RAG、Vector Store、MCP、复杂 Agent 相关依赖后续按 Delta Spec 分阶段引入。
