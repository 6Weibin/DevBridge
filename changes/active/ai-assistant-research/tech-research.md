# 技术预研：DevBridge AI 助手与日志分析能力

## 1. 预研结论

- 结论：有条件可行
- 推荐方案：后端新增独立 AI 模块 + 前端新增浮动 AI Shell + OpenAI-compatible 优先的 Provider Adapter
- 是否需要 Demo：是
- 下一步：先做最小 Demo 验证 Provider 调用、日志上下文压缩、流式分析响应和配置存储；Demo 通过后进入 spec/design。

本需求不建议直接在现有日志页中堆功能。当前系统已经具备设备实时日志采集、SSE 推送、日志落盘与导出能力，AI 能力应作为独立模块旁路接入：前端 AI 侧边栏只通过稳定 API 读取设备、日志和分析结果；后端 AI 服务通过内部 Facade 调用日志上下文，不反向侵入 `LogStreamService`。

## 2. 预研目标

### 2.1 新需求目标

在 DevBridge 前端右下角提供 AI 浮动入口。用户点击后打开浮动侧边栏，通过对话让 AI 完成实时日志采集、问题分析并返回诊断结果。若未配置 AI，则打开配置弹窗，支持 API URL、API Key、模型等配置。底层框架需要支持 DeepSeek、GLM、Qwen、ERNIE、OpenAI 等不同供应商，并尽量与现有功能解耦。

### 2.2 关键决策问题

| 问题 | 结论 | 证据 |
|------|------|------|
| 现有系统是否已有实时日志采集能力 | 已支持，可复用 | `DevBridge-Front/src/app/App.tsx` 使用 `EventSource` 连接 `/logs/stream`；`DevBridge-Server/src/main/java/com/devbridge/server/service/LogStreamService.java` 负责 adb/idevicesyslog 长进程和 SSE |
| 是否需要改动现有日志流协议 | 不建议 | 现有前端已维护每设备日志桶、过滤、渲染限制和导出；AI 可通过新增快照/分析 API 读取日志上下文 |
| 前端浮动图标和侧边栏能否低侵入添加 | 可行 | 前端是 React/Vite 单入口，已有 `lucide-react`、Radix/shadcn UI 组件；可在 `App` 根容器尾部挂载独立 `AiAssistantShell` |
| 多 Provider 是否可统一 | 有条件可行 | Spring AI 官方文档支持 `ChatClient`，并说明可创建多个 OpenAI-compatible endpoint；DeepSeek 和阿里云百炼 Qwen 官方文档均提供 OpenAI 兼容调用方式 |
| API Key 是否适合放前端 localStorage | 不适合正式版本 | 当前前端仅用 `localStorage` 存主题；AI Key 属于敏感数据，应由后端/Electron 安全存储或环境变量管理 |
| 是否可完全解耦 | 业务上可高度解耦，物理上不能完全零耦合 | AI 分析需要设备、日志、应用状态上下文，至少要通过稳定 API 或领域 Facade 读取现有能力 |

### 2.3 范围与非范围

- 范围：前端 AI 浮动入口、AI 配置弹窗、AI 对话侧边栏、Provider 抽象、日志上下文获取、分析接口、安全与风险评估。
- 非范围：本次不正式实现完整 AI 功能；不接入真实付费模型做生产调用；不设计复杂 Agent 编排、RAG 知识库、多用户权限体系。

## 3. 现状扫描

### 3.1 已读取材料

| 类型 | 路径/对象 | 用途 |
|------|-----------|------|
| 前端代码 | `DevBridge-Front/src/app/App.tsx` | 确认入口、日志状态、SSE、渲染结构和挂载点 |
| 前端依赖 | `DevBridge-Front/package.json` | 确认 React/Vite、lucide、Radix/shadcn 组件能力 |
| 后端依赖 | `DevBridge-Server/pom.xml` | 确认 Spring Boot 3.3.7、Java 17、当前依赖很轻 |
| 日志接口 | `DevBridge-Server/src/main/java/com/devbridge/server/api/LogController.java` | 确认日志流、停止会话、导出接口 |
| 日志服务 | `DevBridge-Server/src/main/java/com/devbridge/server/service/LogStreamService.java` | 确认实时采集、SSE、单设备会话管理、日志解析 |
| 日志落盘 | `DevBridge-Server/src/main/java/com/devbridge/server/service/LogCaptureService.java` | 确认按日期/平台/设备分目录、滚动文件和 zip 导出 |
| 后端配置 | `DevBridge-Server/src/main/java/com/devbridge/server/config/DevBridgeProperties.java`、`application.yml` | 确认配置体系、日志根目录、超时配置 |
| Electron | `DevBridge-Electron/src/main.js`、`preload.js` | 确认本地端口、CORS 桥接、userData/logs 目录和安全 preload |
| 官方资料 | Spring AI ChatClient、DeepSeek API、阿里云百炼 OpenAI 兼容文档、OpenAI Responses API | 判断通用 Provider 技术路线 |

### 3.2 当前技术栈

| 层次 | 技术/版本 | 证据 | 备注 |
|------|-----------|------|------|
| 前端 | React 18.3.1、Vite 6.3.5、TypeScript、Tailwind、lucide、Radix/shadcn | `DevBridge-Front/package.json` | 适合新增浮动按钮、Dialog、Sheet/Drawer |
| 后端 | Java 17、Spring Boot 3.3.7、Spring MVC、SSE | `DevBridge-Server/pom.xml` | 可新增 REST/SSE AI 接口 |
| 桌面端 | Electron、本地静态服务、本地后端 jar | `DevBridge-Electron/src/main.js` | 可承载本地安全存储，但 preload 当前只暴露启动进度 |
| 数据存储 | 无数据库，主要配置文件、运行目录、日志文件 | `application.yml`、`LogCaptureService` | AI 配置需要新增安全存储方案 |
| 外部能力 | adb、hdc、libimobiledevice 工具 | `DevBridgeProperties.tools` | AI 不应直接执行命令，应复用后端能力 |

### 3.3 现有能力

| 能力 | 支持程度 | 证据 | 限制 |
|------|----------|------|------|
| Android/iOS 实时日志 | 已支持 | `LogController.streamLogs`、`LogStreamService.streamAndroidLogs`、`streamIosLogs` | Harmony 暂不支持实时日志流 |
| 日志过滤 | 部分支持 | 前端按 level/filter 过滤；后端 `matches` 支持 level/filter | AI 分析需要更结构化的时间窗口、错误级别和上下文截取 |
| 日志落盘 | 已支持 | `LogCaptureService.createSession`、`zipSession` | 当前没有“读取最近 N 行/按会话取样”的内部 API |
| 多设备日志状态 | 已支持 | 前端 `logBucketsByDeviceRef`、`streamingDeviceKeys` | AI 需要明确绑定当前设备或用户选择设备 |
| 配置体系 | 部分支持 | `DevBridgeProperties` 管理后端配置；前端 localStorage 仅存主题 | API Key 不适合放在前端 localStorage |
| 前端浮层 | 部分支持 | 现有固定 topTip、右键菜单、模态逻辑 | 需要新增独立组件，避免继续扩大 `App.tsx` |
| Provider 框架 | 不支持 | 后端没有 AI 依赖或 AI 包 | 需要新增 AI 模块和 Provider 抽象 |

## 4. 能力匹配与差距

| 需求能力 | 现有支持 | 差距 | 复用/改造建议 |
|----------|----------|------|---------------|
| 右下角圆形 AI 图标 | 前端可直接实现 | 无现成 AI 入口 | 新增 `AiAssistantShell`，根级固定定位，使用 `Bot` 或 `Sparkles` 图标 |
| 点击后浮动侧边栏 | Radix/shadcn 组件可复用 | 现有 UI 大部分写在 `App.tsx` 内 | 侧边栏独立组件，接收 `currentDevice`、`logState`、`backendOnline` 等只读 props |
| 未配置时弹出配置 | 前端可做 Dialog | 缺少 AI 配置 API 和安全存储 | 后端提供 `/api/ai/config`，前端只展示是否已配置，不回显 API Key 明文 |
| 对话式日志采集 | 后端已有日志采集 | AI 无法直接控制采集生命周期 | 新增 AI command 到后端：`start_log_capture`、`stop_log_capture`、`get_recent_logs`，由后端转调日志服务 |
| 日志分析结果回复 | 无 | 缺 prompt、上下文压缩、模型调用 | 新增 `AiAnalysisService`，构造脱敏日志摘要和诊断 prompt |
| 多 AI 接入 | 无 | Provider 差异、鉴权、参数差异 | 先统一 OpenAI-compatible Chat Completions，Provider 特性作为可选扩展字段 |
| 模块化低侵入 | 现有模块清晰 | `App.tsx` 已很大，继续修改风险高 | 新建 `src/app/ai/*`、`server/ai/*`，现有代码仅增加挂载点和只读 Facade |

## 5. 技术路线候选

### 5.1 方案 A：薄 Provider Adapter + OpenAI-compatible HTTP（推荐）

- 路线：后端新增 `com.devbridge.server.ai` 包，定义 `AiProviderClient`、`AiChatRequest`、`AiChatResponse`、`AiConfigService`、`AiAnalysisService`；默认实现 `OpenAiCompatibleClient`，用 Spring `RestClient` 或 `WebClient` 调用用户配置的 `baseUrl`。
- 核心要点：
  - 内部统一为 `messages[]`、`model`、`temperature`、`maxTokens`、`stream`。
  - DeepSeek、Qwen、OpenAI 走 OpenAI-compatible；GLM/ERNIE 若兼容接口满足要求可复用，否则新增小型 Adapter。
  - AI 分析只拿“最近 N 行 + 错误聚合 + 设备摘要 + 用户问题”，避免把完整日志无节制发给模型。
  - API Key 只在后端使用，前端只传配置输入，不参与模型直连。
- 改动范围：
  - 后端新增 `api/AiController.java`、`ai/*`、配置模型类和测试。
  - 前端新增 `src/app/ai/*`，在 `App.tsx` 根节点挂载一个组件。
  - Electron 可后续增加安全存储 IPC 或把 AI 配置放在后端用户数据目录。
- 优点：依赖少、可控、符合当前轻量后端；对现有功能侵入小；便于针对国产 Provider 做细节兼容。
- 缺点：需要自己维护流式解析、错误码映射、重试/超时、部分 Provider 参数差异。
- 适用条件：第一阶段以文本对话和日志分析为主，不立即做复杂工具调用和多模态。

### 5.2 方案 B：引入 Spring AI

- 路线：后端引入 Spring AI，使用 `ChatClient`、`OpenAiChatModel` 和多模型配置，AI 业务基于 Spring AI 封装。
- 核心要点：Spring AI 官方文档说明 `ChatClient` 支持同步和流式模式，也支持多个 OpenAI-compatible endpoint；可保留观测能力和 Builder 自定义。
- 改动范围：新增 Spring AI BOM/依赖、配置类、多 Provider Bean、业务服务和测试。
- 优点：标准化程度更高，后续工具调用、结构化输出、观测扩展更完整。
- 缺点：当前项目依赖很轻，引入 Spring AI 会扩大依赖和版本治理成本；国产 Provider 不一定都能被框架原生覆盖，仍需兼容层。
- 适用条件：确认后续要做 Agent、工具调用、结构化输出、RAG 或统一观测后再引入。

### 5.3 方案 C：前端直连各 AI Provider

- 路线：前端配置 API URL/API Key/模型，浏览器直接调用 AI 服务。
- 核心要点：实现最快，后端只提供日志数据。
- 改动范围：前端新增 AI SDK/HTTP 调用和本地配置。
- 优点：PoC 很快。
- 缺点：API Key 暴露在浏览器环境；跨域不可控；日志敏感数据直接从前端出网；Electron 与 H5 安全边界变差。
- 适用条件：仅适合一次性本地演示，不适合作为正式方案。

## 6. 推荐方案

- 推荐：方案 A，先做薄 Provider Adapter + OpenAI-compatible HTTP。
- 推荐理由：
  - 与现有项目形态匹配。后端当前只有 Spring Web/Validation/Test，轻量 Adapter 能避免为初期需求引入过重框架。
  - 满足多 Provider 的主体需求。DeepSeek 官方文档明确 OpenAI/Anthropic 兼容；阿里云百炼 Qwen 文档明确 OpenAI Chat 兼容，并提供 base_url 和流式示例；OpenAI-compatible 可以覆盖第一批主要模型。
  - 安全边界清晰。模型请求由后端发起，前端不持久化 API Key，不把敏感日志直接暴露给浏览器 SDK。
  - 对现有功能侵入低。日志服务保持采集职责，AI 模块通过 Facade 获取上下文。
- 不选其他方案的理由：
  - Spring AI 可作为第二阶段升级，不适合在 Provider 细节和业务边界尚未验证前引入。
  - 前端直连 Provider 不满足 API Key 和日志数据安全要求。
- 前置条件：
  - 明确 AI 配置存储位置：开发态可用后端本地配置文件；Electron 正式版建议放 `app.getPath('userData')` 下并做最小权限保护。
  - 明确日志出网策略：默认脱敏 serial、包名、路径、用户输入、token、手机号/邮箱等敏感片段。
  - 明确 Provider 兼容基线：第一阶段只承诺 OpenAI-compatible Chat Completions 文本能力。

## 7. 建议架构

### 7.1 前端模块

建议新增目录：

```text
DevBridge-Front/src/app/ai/
  AiAssistantShell.tsx
  AiConfigDialog.tsx
  AiChatPanel.tsx
  aiApi.ts
  aiTypes.ts
```

前端职责：

- `AiAssistantShell`：右下角圆形按钮、打开/关闭侧边栏、未配置时打开配置弹窗。
- `AiConfigDialog`：配置 API URL、API Key、模型、Provider 类型、测试连接。
- `AiChatPanel`：展示对话、当前设备上下文、分析状态、流式回复。
- `aiApi.ts`：封装 `/api/ai/config/status`、`/api/ai/config`、`/api/ai/chat`、`/api/ai/analyze/logs`。

`App.tsx` 只做一处挂载：

```tsx
<AiAssistantShell
  backendOnline={backendOnline}
  device={sel.id === WAITING_DEVICE.id ? null : sel}
  streaming={streaming}
/>
```

不建议把 AI 对话、配置、日志分析逻辑继续写入 `App.tsx`，否则该文件复杂度会继续上升。

### 7.2 后端模块

建议新增包：

```text
DevBridge-Server/src/main/java/com/devbridge/server/ai/
  AiConfig.java
  AiConfigService.java
  AiProviderClient.java
  OpenAiCompatibleClient.java
  AiConversationService.java
  AiLogAnalysisService.java
  LogContextFacade.java
  SensitiveDataMasker.java

DevBridge-Server/src/main/java/com/devbridge/server/api/
  AiController.java
```

核心接口草案：

| API | 方法 | 用途 | 安全要求 |
|-----|------|------|----------|
| `/api/ai/config/status` | GET | 判断是否已配置，不返回 API Key | 只返回 `configured`、`provider`、`model`、`baseUrlHost` |
| `/api/ai/config` | PUT | 保存配置 | API Key 后端存储；日志不打印明文 |
| `/api/ai/config/test` | POST | 测试连接 | 短超时，返回脱敏错误 |
| `/api/ai/chat` | POST/SSE | 普通对话 | 控制 maxTokens、超时和请求体大小 |
| `/api/ai/analyze/logs` | POST/SSE | 日志采集与分析 | 设备合法性校验、日志脱敏、限制行数 |

日志上下文输入建议：

```json
{
  "device": {
    "platform": "android",
    "model": "Pixel",
    "osVersion": "14",
    "serialMasked": "abc***xyz"
  },
  "window": {
    "level": "E",
    "maxLines": 500,
    "timeRange": "last_5_minutes"
  },
  "summary": {
    "errorCount": 12,
    "topTags": ["ActivityManager", "AndroidRuntime"],
    "patterns": ["FATAL EXCEPTION", "ANR"]
  },
  "logs": [
    {"timestamp": "...", "level": "E", "tag": "AndroidRuntime", "message": "..."}
  ],
  "question": "帮我分析启动后闪退原因"
}
```

### 7.3 解耦边界

- 日志模块继续负责采集、解析、SSE 和落盘。
- AI 模块只通过 `LogContextFacade` 读取当前日志快照或启动/停止采集，不直接操作 `StreamingCommandRunner`。
- 前端 AI Shell 只读当前设备上下文，不改现有 tab、日志过滤、导出逻辑。
- Provider Client 不依赖设备业务，只接受通用 `AiChatRequest`。

## 8. 风险矩阵

| 风险 | 等级 | 触发条件 | 影响范围 | 发现方式 | 缓解措施 | 回退方案 | 是否需 Demo |
|------|------|----------|----------|----------|----------|----------|-------------|
| API Key 泄露 | 高 | 前端 localStorage 存 Key、日志打印 Key、异常回显 Authorization | 用户账号和模型费用风险 | 安全审查、日志 grep、接口测试 | Key 只存后端；响应永不回显；日志脱敏 | 清空配置并禁用 AI | 是 |
| 日志敏感信息出网 | 高 | 设备日志包含 token、手机号、邮箱、路径、业务数据 | 数据合规和隐私风险 | 脱敏单测、人工样本验证 | 默认脱敏；限制发送行数；配置中提示出网风险 | 只允许本地/私有模型 | 是 |
| Provider 兼容不足 | 中 | 不同供应商参数、流式 chunk、错误码不同 | AI 功能不可用或体验不稳定 | Provider Mock 测试、真实最小调用 | OpenAI-compatible 为基线；Provider adapter 分层 | 切回非流式或禁用该 Provider | 是 |
| AI 分析上下文过大 | 中 | 实时日志量大、错误风暴、用户要求全量分析 | 延迟、费用、内存、模型截断 | 性能压测、token 统计 | 取样、聚合、最大行数、maxTokens | 返回提示让用户缩小范围 | 是 |
| 日志采集会话冲突 | 中 | AI 自动采集与用户手动采集同一设备 | 重复进程、日志丢失、导出状态异常 | 集成测试、手动切设备验证 | 复用现有单设备会话策略；AI 只请求状态和快照 | AI 分析只读现有日志 | 是 |
| 前端 `App.tsx` 继续膨胀 | 中 | AI 逻辑直接写入主文件 | 可维护性下降、回归风险 | 代码审查、文件复杂度 | 独立 `src/app/ai` 目录；主文件只挂载 | 删除 AI 目录即可回退 | 否 |
| 外部模型不可用或慢 | 中 | 网络失败、限流、模型超时 | 对话卡顿、用户误判工具故障 | 超时测试、错误码模拟 | 请求超时、取消、重试一次、友好错误 | 停用 AI，不影响原功能 | 是 |
| Prompt 注入 | 中 | 日志中包含诱导模型忽略规则的文本 | 分析结论被污染 | Prompt 攻击样本测试 | 系统提示区分“日志是不可信输入”；输出结构约束 | 降级为规则摘要 | 是 |
| Electron 配置迁移 | 低 | 开发态和打包态运行目录不同 | 配置丢失或不可读 | 打包态测试 | 使用 userData 路径；配置版本号 | 重新配置 | 否 |

## 9. 安全与质量评估

- 性能：AI 分析必须限制日志行数、字符数和 token 预算；前端侧边栏流式渲染应做增量追加，避免每个 chunk 重排大列表。后端 Provider 请求设置连接超时、读取超时和最大响应时长。
- 可扩展性：Provider 抽象只保留当前真实需要的字段，不预设复杂插件体系。新增 Provider 时扩展 Adapter，不改业务分析服务。
- 可维护性：前端 AI 组件独立目录，后端 AI 包独立。方法需要控制在 80 行内，配置对象避免超过 8 个参数。
- 高可用：AI 模块失败不能影响设备列表、文件管理、实时日志。所有 AI API 失败应返回明确错误并允许关闭侧边栏。
- 数据一致性：配置保存需要原子写入；保存新配置前可先测试连接，但不强制，因为内网模型可能临时不可达。
- 安全：
  - API Key 不进入前端持久化。
  - 不打印 Authorization、API Key、原始大段日志。
  - base URL 需要校验协议为 `https` 或本机/内网白名单；防止 SSRF 到本机敏感地址。
  - 对用户输入和模型输出按文本渲染，不使用 `dangerouslySetInnerHTML`，避免 XSS。
  - 模型请求需限制请求体大小、日志行数和调用频率，避免滥用费用。
- 可观测性：记录 Provider、模型、耗时、是否流式、输入日志行数、错误码；不记录 API Key 和完整日志正文。

## 10. 成本评估

| 工作项 | 工作量 | 依赖 | 说明 |
|--------|--------|------|------|
| 前端 AI Shell、侧边栏、配置弹窗 | 1.5~2 人天 | React、lucide、Radix/shadcn | 独立组件，挂载到 `App.tsx` |
| 后端 AI 配置 API 和安全存储 | 1~1.5 人天 | Spring MVC、配置文件或 Electron userData | 包括 Key 脱敏、状态接口、测试连接 |
| OpenAI-compatible Provider Adapter | 1~2 人天 | Spring HTTP Client | 包括非流式/流式、错误映射、超时 |
| 日志上下文 Facade 与脱敏 | 1~2 人天 | 现有日志服务 | 需要补最近日志快照能力或复用前端传入日志 |
| 日志分析 Prompt 与结果结构 | 1 人天 | Provider Adapter | 输出原因、证据日志、建议动作、置信度 |
| 测试与安全验证 | 1.5~2 人天 | JUnit、前端构建 | 覆盖配置、脱敏、Provider Mock、AI 不影响原功能 |
| Electron 打包态配置适配 | 0.5~1 人天 | Electron main/preload | 若采用 Electron 安全存储则需要 IPC |

整体预估：7~11 人天可完成一个可用版本；如果引入 Spring AI、工具调用、RAG 或多 Provider 真实联调，成本会上升。

## 11. Demo/PoC 建议

- 是否需要 Demo：需要。
- 验证目标：
  1. 前端右下角浮动入口和侧边栏可在所有 tab 上覆盖显示，不影响现有设备、日志、文件操作。
  2. 未配置时能打开配置弹窗，保存后不回显 API Key 明文。
  3. 后端可以通过一个 OpenAI-compatible Mock Provider 完成普通对话和流式响应。
  4. AI 日志分析能读取最近日志样本、完成脱敏、构造 prompt 并返回结构化诊断。
  5. AI 失败时不影响现有实时日志采集。
- Demo 范围：
  - 新增独立前端 AI 组件。
  - 新增后端 `/api/ai/config/status`、`/api/ai/config`、`/api/ai/chat/mock` 或 Mock Provider。
  - 用本地假 Provider 验证接口和 UI，不依赖真实 API Key。
- 验收标准：
  - `pnpm build` 通过。
  - `mvn test` 通过。
  - 手动打开页面，点击 AI 图标时：未配置打开 Dialog；配置后打开侧边栏并可发起分析。
  - 日志脱敏单测覆盖 token、手机号、邮箱、serial、Authorization。
- 不做内容：不做完整 Agent 编排、不做 RAG、不做多模型真实联调、不做云端账号体系。

## 12. 后续建议

1. 先确认是否接受“后端代理模型调用、前端不保存 Key”的安全边界。
2. 确认第一阶段 Provider 基线为 OpenAI-compatible Chat Completions；DeepSeek/Qwen/OpenAI 优先，GLM/ERNIE 作为 Adapter 验证项。
3. 进入 Demo 时新增 `changes/active/ai-assistant-research/demo-plan.md`，只做可删除的最小验证，不直接把 Demo 写成完整生产功能。
4. Demo 通过后再进入正式 `spec/design/coding`，补齐接口规格、配置存储策略、脱敏规则和测试清单。

## 13. 官方资料参考

- Spring AI ChatClient 文档：支持同步/流式模型调用、多 ChatModel，以及多个 OpenAI-compatible endpoint。
- DeepSeek API 文档：DeepSeek API 使用兼容 OpenAI/Anthropic 的 API 格式，并给出 `base_url`、`api_key`、`model` 和流式参数示例。
- 阿里云百炼 Qwen 文档：千问模型支持 OpenAI Chat 接口兼容，调整 API Key、BASE_URL 和模型名称即可迁移，并提供流式调用示例。
- OpenAI Responses API 文档：Responses API 支持结构化的输入/输出消息对象，可作为未来兼容 OpenAI 新接口的参考。
