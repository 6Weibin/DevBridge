# DevBridge 项目记忆

更新时间：2026-07-06 21:39 +08:00

## 项目定位

DevBridge 是一个本机 USB 移动设备管理与日志采集工具，当前由三个工程组成：

- `DevBridge-Server`：Spring Boot 3 / Java 17 本机后端，默认监听 `127.0.0.1:8080`，提供 H5 静态资源、REST API、SSE 日志流和 AI 能力。
- `DevBridge-Front`：React + Vite + Tailwind 前端，展示设备、文件、日志、应用管理与 AI 助手界面。
- `DevBridge-Electron`：Electron 桌面壳，负责构建前端和后端 jar、准备运行资源、启动内置后端和静态服务，并打包桌面客户端。

产品目标是屏蔽 Android、iOS、HarmonyOS 的 USB 工具差异，通过本机 Web/桌面界面完成设备发现、详情查看、文件读取/预览/下载、实时日志采集/导出、Android 应用管理、AI 日志分析与 ADB MCP 工具调用。

## 技术栈与工程边界

| 工程 | 技术栈 | 关键职责 |
| --- | --- | --- |
| `DevBridge-Server` | Java 17、Spring Boot 3.3.7、Spring AI 1.0.9、Maven | 本机 REST/SSE API、外部工具执行、AI Provider 网关、MCP 工具服务 |
| `DevBridge-Front` | React 18、Vite 6、Tailwind、Radix/MUI、lucide | 单页设备管理 UI、AI 配置/聊天/日志分析 UI、SSE/Blob 下载处理 |
| `DevBridge-Electron` | Electron 31、electron-builder | 桌面启动编排、端口桥接、资源准备、mac zip 打包 |

后端 Maven 会把 `../DevBridge-Front/dist` 合入 Spring Boot 静态资源目录；Electron 打包时会先构建 Front 和 Server，再把前端 dist、后端 fat jar、tools、可选 jlink runtime 收集到 `DevBridge-Electron/resources`。

## 后端架构

后端入口是 `DevBridge-Server/src/main/java/com/devbridge/server/DevBridgeServerApplication.java`，通过 `@EnableConfigurationProperties(DevBridgeProperties.class)` 加载 `application.yml` 中的本机配置。

主要包职责：

- `api`：Controller 与全局异常处理，包含 `DeviceController`、`FileController`、`LogController`、`AppController`、`AiController`、`AiMcpController`、`ApiExceptionHandler`。
- `service`：设备、文件、日志、工具定位、运行环境服务，包含 `DeviceService`、`AndroidDeviceService`、`IosDeviceService`、`LogStreamService`、`LogCaptureService`、`ExecutableLocator`。
- `command`：`CommandRunner` 执行短命令，`StreamingCommandRunner` 执行长进程日志流；两者都用 `ProcessBuilder(List<String>)`，不拼 shell 字符串。
- `ai.config`：AI 多 Provider 配置、API Key 加密存储、配置校验。
- `ai.provider`：基于 Spring AI OpenAI-compatible 的 Provider Gateway。
- `ai.conversation`：普通对话和流式对话，拼接设备上下文，挂载 ADB MCP 工具。
- `ai.analysis`：日志分析，前端无日志时可通过 ADB MCP 补取最近 logcat。
- `ai.mcp`：ADB 工具目录、命令规划、设备校验、风险识别、确认令牌、执行、审计和 SSE 事件发布。

## 后端核心接口

| 能力 | 接口 |
| --- | --- |
| 工具状态 | `GET /api/tools/status` |
| 运行环境 | `GET /api/runtime/environment` |
| 设备列表 | `GET /api/devices` |
| 设备详情 | `GET /api/devices/{platform}/{serial}/detail` |
| Android 截图 | `GET /api/devices/{platform}/{serial}/screenshot` |
| ADB 诊断 | `GET /api/diagnostics/adb-devices` |
| 文件浏览/详情/下载/预览/删除/重命名/复制 | `/api/devices/{platform}/{serial}/files...` |
| 实时日志 | `GET /api/devices/{platform}/{serial}/logs/stream` |
| 停止日志会话 | `POST /api/logs/sessions/{sessionId}/stop` |
| 日志导出 | `GET /api/devices/{platform}/{serial}/logs/export` |
| Android 应用列表/详情/卸载 | `/api/devices/{platform}/{serial}/apps...` |
| AI 配置 | `/api/ai/config/status`、`GET/PUT /api/ai/config`、`POST /api/ai/config/test` |
| AI 对话 | `POST /api/ai/chat`、`POST /api/ai/chat/stream` |
| AI 日志分析 | `POST /api/ai/analyze/logs` |
| ADB MCP | `/api/ai/mcp/adb/tools`、`/tools/call`、`/tools/call/stream`、确认/取消接口 |

## 设备、文件、日志业务逻辑

### 设备发现

`DeviceService.listDevices()` 依次调用：

1. `adb devices`
2. `hdc list targets`
3. `idevice_id -l`

单个平台工具缺失或命令失败不会阻断其他平台。ADB 首次枚举失败时会显式执行 `adb start-server` 后重试一次。

工具定位由 `ExecutableLocator` 负责，优先级是内置工具目录 `tools/{os-arch}`、配置路径、PATH、常见安装目录。`RuntimeEnvironmentService` 暴露当前 OS/arch 和工具目录名，方便前端展示。

### Android 能力

`AndroidDeviceService` 是 Android 设备核心服务，封装：

- 详情：`getprop`、`dumpsys battery`、`wm size/density`、`df`、`cat /proc` 等。
- 文件：`adb shell ls -la`、`adb pull`、`rm`、`mv`、`cp`。
- 截图：`screencap -p` 到远端临时文件，再 pull 到本机临时目录。
- 应用：`cmd package list packages`、`dumpsys package`、`pm uninstall`。

删除、重命名、复制只允许文件，不支持目录；卸载应用前校验包名、应用存在性，并禁止系统应用直接卸载。

### 路径安全

`AndroidPathGuard` 当前拒绝空路径、相对路径、`..` 和控制字符，要求远端路径是绝对路径。注意：代码当前允许任意绝对路径；但 `DevBridge-Server/README.md` 描述文件浏览和下载仅允许 `/sdcard`、`/storage/emulated/0`，两者存在不一致。

### 实时日志

`LogStreamService`：

- Android：启动 `adb -s {serial} logcat -v threadtime -T 1000`。
- iOS：启动 `idevicesyslog -u {udid}`。
- SSE 事件名：正常日志为 `log`，工具 stderr 作为 `tool-error`，避免前端误判连接失败。
- 同一设备新日志会话会接管旧会话，避免 EventSource 重连残留。

`LogCaptureService` 会在实时日志流同时落盘，目录按日期、平台、设备型号分层，默认单文件 10MB、最多保留 20 个滚动文件；导出时将本次会话文件压缩为 zip。

## AI 与 MCP 逻辑

### AI 配置

支持 Provider：

- `openai`
- `deepseek`
- `qwen`
- `glm`
- `ernie`
- `custom-openai-compatible`

`AiConfigService` 使用多 Provider 配置结构，每个 Provider 独立保存 `apiUrl`、`model`、加密后的 `apiKey` 和更新时间。状态接口只返回脱敏摘要；详情接口会解密 API Key 供本机配置页回填。API URL 校验规则是公网必须 HTTPS，本机/私网允许 HTTP。

模型配置链路支持两种方式：用户可以继续手动填写模型，也可以在配置好 API URL 和 API Key 后点击“获取模型列表”，由后端代理拉取 Provider 模型列表并在前端下拉选择。前端 `AiConfigDialog` 内置各 Provider 的默认 URL 和默认模型；保存和连接测试仍要求 `model` 非空，模型列表拉取只要求 Provider、API URL 和 API Key。

### Provider 调用

`SpringAiProviderGateway` 使用 Spring AI `ChatClient` 和 `OpenAiApi`，所有厂商都按 OpenAI-compatible 方式调用。`AiProviderEndpointResolver` 负责处理 baseUrl 是否已带 `/v1`、`/v4` 或完整 `/chat/completions`，避免路径重复。

Provider 调用超时 60 秒，错误映射为稳定业务码，例如鉴权失败、限流、超时、Provider 响应无效。调用观测只记录 Provider、模型、成功状态、耗时和脱敏错误摘要，不记录完整 Prompt 或日志正文。

### AI 对话与日志分析

`AiConversationService` 会把当前设备上下文写入 Prompt，并注册 ADB MCP 工具回调。流式对话通过 SSE 返回模型增量，同时发布工具事件：

- `tool-start`
- `tool-output`
- `tool-confirmation`
- `tool-result`
- `tool-error`

`AiLogAnalysisService` 限制最多 500 行、60000 字符，并使用 `SensitiveDataMasker` 脱敏。当前设备为 Android 且前端未提供日志时，会通过 MCP 工具补取最近 logcat。

### ADB MCP

`AdbToolCatalog` 固定声明 ADB 工具目录，包含 devices/help/version/network/file_transfer/shell/install/uninstall/debugging/device_control/raw 等。执行流为：

`AdbMcpToolService` → `AdbCommandPlanner` → `AdbDeviceValidator` → `AdbRiskClassifier` → `AdbConfirmationService`（必要时）→ `AdbCommandExecutor` → `AdbToolAuditRecorder`。

高风险操作（安装、卸载、push/sync、reboot、root/remount/tcpip、敏感 shell 等）需要一次性确认令牌，令牌绑定会话、设备、参数 hash 和风险等级。

## 前端架构

前端入口 `src/main.tsx` 很薄，主业务集中在 `src/app/App.tsx`。`App.tsx` 同时维护设备列表、工具状态、运行环境、设备详情、文件树、日志、应用、截图等状态。

主页面结构：

- 左侧：设备列表、工具状态。
- 顶部：设备摘要、运行环境。
- 主区域：设备信息、文件管理、实时日志、应用管理四个页签。
- 右下/浮层：`AiAssistantShell` 提供 AI 配置和聊天入口。

主业务 API 基址在 `App.tsx` 中硬编码为 `http://127.0.0.1:8080`；AI API 封装 `src/app/ai/aiApi.ts` 优先读取 `VITE_API_BASE`，否则默认同样是 `http://127.0.0.1:8080`。

前端设备轮询默认每 3 秒刷新 `/api/devices`。实时日志使用 `EventSource` 连接后端 SSE，前端最多渲染 1000 行日志；AI POST SSE 不能用 EventSource，因此 `aiApi.ts` 使用 `fetch + reader` 手动解析 SSE。

## AI 配置前端现状

`AiConfigDialog.tsx` 分为“提示词配置”和“模型配置”两页。模型配置当前字段：

- Provider 下拉
- API URL 输入框
- API Key 输入框
- 模型输入框

切换 Provider 时会先缓存当前表单草稿，再尝试读取该 Provider 已保存的配置；未保存过时使用前端默认 URL/模型。保存和测试连接都要求 API URL、API Key、模型、提示词非空。

后续实现“获取模型列表”应优先复用 `aiApi.ts` 的 `requestJson` 错误处理模式，并在 `AiConfigDialog.tsx` 的模型配置区增加“获取模型列表”按钮、加载状态、候选下拉/选择能力，同时保留手动输入。

## Electron 桌面壳

Electron 端口：

- 前端静态服务：`127.0.0.1:15173`
- 后端 Spring Boot：`127.0.0.1:18180`
- 兼容旧前端 API：`8080` 请求会被 Electron 桥接/改写到 `18180`

启动流程：

1. 创建窗口并加载 splash。
2. 注册 CORS/API 桥。
3. 准备 runtime 目录。
4. 启动前端静态服务。
5. 启动或探测后端服务。
6. 服务就绪后加载主页面。

`BrowserWindow` 启用 `contextIsolation`，禁用 Node，开启 sandbox；`preload.js` 只暴露启动进度订阅。资源准备脚本会复制 Front dist、patch API 端口、复制 Server fat jar、复制 tools，并在可用时用 `jlink` 生成精简 JRE。

## 安全与风险

- 后端默认只监听 `127.0.0.1`，无 Spring Security 鉴权；主要依赖本机边界和 CORS。
- 本机其他进程仍可直接访问 `127.0.0.1` 接口，这是桌面单机工具的主要安全边界风险。
- 命令执行均为参数数组，避免 shell 注入。
- AI 配置文件不保存明文 API Key，使用本机生成的 AES/GCM key 加密。
- `SensitiveDataMasker` 会脱敏 Authorization、API Key/token/password、邮箱、手机号、序列号等。
- 文件路径代码与 README 访问范围声明不一致，涉及文件访问需求时要优先确认并修正行为边界。
- ADB MCP 对高风险命令有风险评级和确认令牌机制。

## 构建、运行与验证命令

| 任务 | 命令 |
| --- | --- |
| 后端测试 | `cd DevBridge-Server && mvn test` |
| 后端构建 | `cd DevBridge-Server && mvn -DskipTests package` |
| 后端启动 | `cd DevBridge-Server && scripts/start-server.sh` 或 `scripts/start-server.cmd` |
| 前端开发 | `cd DevBridge-Front && npm run dev` |
| 前端构建 | `cd DevBridge-Front && npm run build` |
| Electron 本地运行 | `cd DevBridge-Electron && npm start` |
| Electron 打包 | `cd DevBridge-Electron && npm run package` |

## 后续开发注意事项

1. 新增后端接口优先沿用 `BusinessException` + `ApiExceptionHandler` 的稳定错误响应，前端用 `requestJson` 转换为用户可读错误。
2. 外部命令必须继续使用 `List<String>` 参数数组，不能拼 shell 字符串。
3. AI Provider 相关 URL 需要经过 `AiConfigService` 或等价规则校验：公网 HTTPS，本机/私网 HTTP。
4. 新增 AI 前端接口应集中在 `src/app/ai/aiApi.ts` 和 `aiTypes.ts`，避免业务组件直接散落 fetch。
5. `AiConfigDialog` 已经有 Provider 草稿缓存和按 Provider 回填逻辑；新增模型列表时要避免切换 Provider 后覆盖用户当前手动输入。
6. Electron 会把前端构建产物内的 `127.0.0.1:8080` 改写/桥接到 `18180`，改动 API 基址时需同步考虑 Electron。
7. 后端 Java 新增/修改代码当前普遍包含中文类/方法/关键逻辑注释和 `by AI.Coding` 文件/类注释，应保持一致。
8. Android 文件访问边界存在 README 与代码不一致，涉及文件管理时需先决定是按 README 收窄还是更新文档。

## 当前需求“模型列表”相关定位

需求：模型配置中模型填写应支持配置好 API URL 和 API Key 后点击“获取模型列表”按钮，拉取对应厂商现有支持的全部模型，用户可选择使用，同时支持手动填写。

已确认现状：

- 前端模型输入和模型列表按钮在 `DevBridge-Front/src/app/ai/AiConfigDialog.tsx` 的 `ModelSettings` 内。
- AI API 封装在 `DevBridge-Front/src/app/ai/aiApi.ts`，模型列表函数为 `fetchModelList`。
- 前端类型在 `DevBridge-Front/src/app/ai/aiTypes.ts`，模型列表请求/响应类型为 `AiModelListRequest`、`AiModelListResponse`。
- 后端模型列表接口在 `DevBridge-Server/src/main/java/com/devbridge/server/api/AiController.java`，路径为 `POST /api/ai/config/models`。
- 后端配置校验在 `AiConfigService.runtimeForModelList(AiModelListRequest)`，该流程不要求模型字段。
- Provider endpoint 解析在 `AiProviderEndpointResolver`，模型列表路径随 baseUrl 是否版本化切换为 `/v1/models` 或 `/models`。
- Provider HTTP 调用集中在 `SpringAiProviderGateway.listModels`，解析 OpenAI-compatible `data[].id`，也兼容 `models` 字符串数组；host-only 地址遇到 404 会在 `/v1/models` 和 `/models` 间做一次受控兜底。
- 前端获取失败时保留手动输入，不清空当前模型字段。
