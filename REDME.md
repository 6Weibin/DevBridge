# Ai DevBridge

Ai DevBridge 是一套运行在本机的跨平台 USB 移动设备管理、日志采集与 AI 辅助诊断工具。项目通过统一的 Web/桌面界面屏蔽 Android、iOS 和 HarmonyOS 命令行工具差异，提供设备发现、设备详情、文件管理、实时日志、Android 应用管理、AI 对话、日志分析和受控工具调用能力。

当前仓库由 Spring Boot 服务端、React 前端和 Electron 桌面客户端三个主要工程组成，可分别开发，也可组合打包为桌面应用。

> 当前项目处于持续开发阶段。Android 能力最完整；iOS 与 HarmonyOS 能力会根据本机工具是否安装自动启用或降级。

## 核心能力

- 自动发现通过 USB 连接的 Android、iOS 和 HarmonyOS 设备。
- 查看设备系统、硬件、电池、屏幕、存储和运行状态。
- 浏览、预览、下载、复制、重命名和删除设备文件。
- 采集、筛选、暂停和导出 Android/iOS 实时日志。
- 查看 Android 应用列表、应用详情并执行受控卸载。
- 配置 OpenAI、DeepSeek、通义千问、智谱、文心及 OpenAI-compatible 模型。
- 使用流式 AI 对话分析设备上下文和日志，并保存本地会话历史。
- 通过 ADB MCP、本地 Shell 和领域工具完成受审计的 AI 工具调用。
- 对高风险操作执行风险分级、人工确认、输出脱敏、审计和取消控制。
- 支持 Agent 任务执行、暂停、恢复、补充输入、事件流、检查点和补偿处理。
- 支持本地知识记忆、轻量 RAG、工具产物和 AI 数据维护。

## 工程组成

| 工程 | 技术栈 | 主要职责 |
| --- | --- | --- |
| `DevBridge-Server` | Java 17、Spring Boot 3.3.7、Spring AI 1.0.9、Maven | REST/SSE API、设备命令执行、日志采集、AI Provider、MCP、Agent 运行时和本地数据存储 |
| `DevBridge-Front` | React 18、Vite 6、Tailwind CSS 4、MUI、Radix UI | 设备管理、文件/日志/应用界面、AI 配置与流式对话界面 |
| `DevBridge-Electron` | Electron 31、electron-builder | 桌面启动编排、内置后端与静态服务、运行资源收集和客户端打包 |

三个工程的构建关系如下：

```text
DevBridge-Front --生成 dist--> DevBridge-Server --生成 jar--+
        |                                               |
        +-------------------- dist ---------------------+--> DevBridge-Electron/resources
DevBridge-Server/tools ---------------------------------+
                                                         --> Electron 桌面客户端
```

服务端 Maven 构建会将 `DevBridge-Front/dist` 合入 Spring Boot 静态资源。Electron 构建则会重新构建前端与服务端，收集前端资源、后端可执行 JAR、设备工具和可选 Java Runtime，再生成桌面应用。

## 目录结构

```text
DevBridge/
├── DevBridge-Server/       Spring Boot 服务端、设备工具及服务端测试
├── DevBridge-Front/        React/Vite 前端
├── DevBridge-Electron/     Electron 桌面客户端与打包脚本
├── changes/active/         开发中的规格、设计、任务、验收和调试记录
├── distil-doc/             项目骨架、架构图、代码索引和项目记忆
├── init.sh                 macOS 初始化与完整构建脚本
├── init.cmd                Windows 初始化与完整构建脚本
├── build-version.json      产品构建版本信息
└── 跨平台 USB 手机设备管理与日志采集工具需求（Java + H5 实现）.md
                            原始产品需求说明
```

`node_modules`、`dist`、`target`、`release` 和 `DevBridge-Electron/resources` 中的大部分内容属于依赖或构建产物，不应作为业务源码修改。

## 环境要求

### 开发工具

- JDK 17 或更高版本。
- Maven 3.8 或更高版本。
- Node.js 20 或更高版本。
- pnpm，用于恢复和构建前端依赖。
- npm，用于恢复 Electron 锁定依赖和执行桌面构建。
- macOS 桌面打包建议使用带 `jlink` 的完整 JDK；缺少 `jlink` 时 Electron 会回退使用系统 `java`。

### 设备工具

| 平台 | 所需工具 | 说明 |
| --- | --- | --- |
| Android | `adb` | 仓库已包含 macOS arm64 和 Windows x64 的 Android Platform-Tools |
| HarmonyOS | `hdc` 或 `hdc_std` | 从 DevEco Studio/OpenHarmony 工具链安装，或通过服务端配置指定路径 |
| iOS | `idevice_id`、`idevicesyslog`、`idevicebackup2` | 通常由 `libimobiledevice` 提供 |

Android 手机需要开启“开发者选项”和“USB 调试”，首次连接时在手机端确认 RSA 授权。Windows 如无法识别设备，还需安装对应厂商 USB 驱动。

## 快速开始

### 方式一：一键初始化并构建全部工程

macOS：

```bash
./init.sh
```

Windows：

```bat
init.cmd
```

脚本会检查或补齐构建工具、安装锁定依赖，并依次构建前端、服务端和 Electron 目录包。只检查环境与工程结构时使用：

```bash
./init.sh --check-only
```

```bat
init.cmd --check-only
```

> `init.sh` 可能通过 Homebrew 安装缺失工具，`init.cmd` 可能通过系统包管理器安装缺失工具。请在执行前确认当前账号具备相应权限和网络访问能力。

### 方式二：启动 Web 开发环境

1. 安装并启动前端：

```bash
cd DevBridge-Front
pnpm install --frozen-lockfile
pnpm run dev
```

2. 在另一个终端启动服务端：

```bash
cd DevBridge-Server
mvn spring-boot:run
```

3. 访问前端开发地址：

```text
http://127.0.0.1:5173
```

服务端默认监听 `http://127.0.0.1:8080`。前端当前默认请求该地址，因此开发时应保持默认服务端端口。

### 方式三：运行 Electron 桌面客户端

```bash
cd DevBridge-Electron
npm ci
npm start
```

`npm start` 会自动构建前端和服务端、准备运行资源，然后启动 Electron。桌面端使用以下固定端口：

| 服务 | 地址 |
| --- | --- |
| Electron 前端静态服务 | `http://127.0.0.1:15173` |
| Electron 内置后端 | `http://127.0.0.1:18180` |

若 `18180` 已被其他进程占用，客户端会停止启动并提示释放端口。仅在明确需要复用已有后端时可运行：

```bash
DEVBRIDGE_ELECTRON_REUSE_BACKEND=1 npm start
```

## 构建与打包

### 前端

```bash
cd DevBridge-Front
pnpm install --frozen-lockfile
pnpm run build
```

构建产物位于 `DevBridge-Front/dist`。

### 服务端

服务端构建依赖 `DevBridge-Front/dist`，需要先完成前端构建：

```bash
cd DevBridge-Server
mvn package
```

跳过测试的快速构建：

```bash
mvn -DskipTests package
```

构建产物位于 `DevBridge-Server/target`。也可使用工程脚本启动：

```bash
DevBridge-Server/scripts/start-server.sh
```

```bat
DevBridge-Server\scripts\start-server.cmd
```

### Electron

生成未封装的应用目录：

```bash
cd DevBridge-Electron
npm ci
npm run package:dir
```

生成 macOS ZIP 安装包：

```bash
npm run package
```

输出目录为 `DevBridge-Electron/release`。当前 `package` 脚本明确构建 macOS ZIP；`package.json` 已配置 Windows/Linux 图标，但对应平台的完整安装包流程仍需在目标系统上验证和补充。

## 常用验证命令

```bash
# 服务端单元测试
cd DevBridge-Server && mvn test

# 前端生产构建
cd DevBridge-Front && pnpm run build

# AI 长响应稳定性检查
cd DevBridge-Front && pnpm run test:ai-stability

# Electron 渲染稳定性检查
cd DevBridge-Front && pnpm run test:ai-electron-stability

# Windows 内置 ADB 验证
DevBridge-Server\scripts\verify-windows-adb.cmd
```

## 主要服务端接口

| 能力 | 代表接口 |
| --- | --- |
| 运行环境与工具状态 | `GET /api/runtime/environment`、`GET /api/tools/status` |
| 设备 | `GET /api/devices`、`GET /api/devices/{platform}/{serial}/detail` |
| 截图与 ADB 诊断 | `GET /api/devices/{platform}/{serial}/screenshot`、`GET /api/diagnostics/adb-devices` |
| 文件管理 | `/api/devices/{platform}/{serial}/files...` |
| 实时日志与导出 | `/api/devices/{platform}/{serial}/logs...`、`POST /api/logs/sessions/{sessionId}/stop` |
| Android 应用管理 | `/api/devices/{platform}/{serial}/apps...` |
| AI 配置与模型列表 | `/api/ai/config...` |
| AI 对话、历史与日志分析 | `/api/ai/chat...`、`/api/ai/conversations...`、`POST /api/ai/analyze/logs` |
| Agent 任务 | `/api/ai/agent/tasks...` |
| ADB MCP | `/api/ai/mcp/adb...` |
| 本地 Shell MCP | `/api/ai/mcp/local-shell...` |
| 知识与 RAG | `/api/ai/knowledge...` |
| AI 存储、审计和产物 | `/api/ai/storage...`、`/api/ai/audit/tools...`、`/api/ai/artifacts...` |

服务端还通过 `/api/ai/mcp/standard/sse` 和 `/api/ai/mcp/standard/message` 提供标准 MCP WebMVC 端点。

## 配置与本地数据

服务端主配置位于 `DevBridge-Server/src/main/resources/application.yml`。常用配置包括：

- 命令与日志流超时。
- 内置设备工具目录和各工具的显式路径。
- 下载、日志、AI 配置、Agent、工具产物和审计数据目录。
- AI 上下文窗口、功能开关、线程池与存储配额。
- ADB MCP 开关和高风险操作确认有效期。
- 本地控制面令牌及允许访问的前端来源。

源码运行时，本地数据默认写入 `DevBridge-Server/target` 下的相关目录。Electron 运行时，后端日志和运行数据会写入操作系统分配给 Ai DevBridge 的用户数据/日志目录，不依赖源码目录。

可通过环境变量覆盖的关键配置包括：

- `DEVBRIDGE_CONTROL_PLANE_TOKEN`
- `DEVBRIDGE_AI_AGENT_RUNTIME_ENABLED`
- `DEVBRIDGE_AI_MULTI_AGENT_ENABLED`
- `DEVBRIDGE_AI_MODEL_FALLBACK_ENABLED`
- `DEVBRIDGE_AI_TRACE_ENABLED`
- `DEVBRIDGE_AI_LOCAL_DATA_MAINTENANCE_ENABLED`
- `DEVBRIDGE_AI_AGENT_INPUT_TIMEOUT`
- `DEVBRIDGE_ELECTRON_REUSE_BACKEND`

## 安全说明

- Web 服务默认只监听 `127.0.0.1`，不要在未补充认证、授权和网络隔离前改为公网或局域网监听。
- Electron 会为本机控制面生成随机令牌，并仅在主进程和后端子进程之间传递，不写入前端资源或磁盘。
- 外部命令通过参数数组执行，禁止将用户输入拼接为 Shell 命令字符串。
- AI API Key 使用本机生成的密钥进行 AES/GCM 加密存储，状态接口只返回脱敏信息。
- ADB 和本地 Shell 高风险操作需要一次性确认，并绑定会话、设备、参数与风险等级。
- 日志、工具输出和 AI 上下文包含脱敏与数据外发控制，但仍不应主动处理无关的敏感数据。
- 文件访问、应用卸载和设备控制属于敏感操作，开发新能力时必须保留路径校验、设备校验、审计和最小权限原则。

## 开发约定

- 优先遵循各工程现有目录、命名、异常处理、日志、注释和测试风格。
- 后端业务异常统一使用稳定错误码并交由全局异常处理转换响应。
- 新增外部工具时，应同时维护工具定位、状态展示、超时、输出上限、取消、脱敏和审计逻辑。
- 新增前端 AI API 应集中在 `DevBridge-Front/src/app/ai/aiApi.ts`，类型集中在 `aiTypes.ts`。
- 修改前端 API 基址、CORS 或自定义请求头时，需要同步检查 Electron 的端口改写和控制面桥接。
- 单个方法不超过 80 行，参数不超过 8 个；避免无业务价值的分层、抽象和扩展点。
- 核心业务、边界、异常和安全分支应有可读单元测试，目标覆盖率不低于 85%。

## 进一步阅读

- [服务端说明](./DevBridge-Server/README.md)
- [前端说明](./DevBridge-Front/README.md)
- [Electron 说明](./DevBridge-Electron/README.md)
- [内置工具说明](./DevBridge-Server/tools/TOOLS.md)
- [项目文档索引](./distil-doc/README.md)
- [项目架构与代码记忆](./distil-doc/PROJECT_MEMORY.md)
- [当前开发规格与记录](./changes/active/)
- [原始产品需求](./跨平台%20USB%20手机设备管理与日志采集工具需求（Java%20%2B%20H5%20实现）.md)

## 当前版本

当前 Maven/Electron 工程版本为 `0.1.0` 系列，产品构建序号由根目录 `build-version.json` 统一维护，并由前端构建脚本同步到相关工程资源。
