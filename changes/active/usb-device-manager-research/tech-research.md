# 技术预研：跨平台 USB 手机设备管理与日志采集工具

## 1. 预研结论

- 结论：有条件可行
- 推荐方案：Spring Boot 本机服务 + H5 前端 + 外部官方/成熟命令行工具适配层
- 是否需要 Demo：是
- 下一步：先做最小 PoC，验证本机工具发现、设备枚举、日志流、文件拉取四个关键闭环，再进入正式 spec/design/coding

总体判断：Android 与 HarmonyOS 的设备发现、公共目录文件读取、日志采集、基础设备信息读取可以作为一期核心能力推进；iOS 能力需要降级定义，建议一期只承诺设备发现、配对状态识别、系统日志流、备份式数据导出，不承诺未越狱设备的通用文件系统浏览。Windows 上的 iOS 能力风险最高，应作为兼容增强项，而不是 MVP 阻塞项。

## 2. 预研目标

### 2.1 新需求目标

建设一个本机运行的 USB 手机设备管理工具。用户通过浏览器访问本地 H5 页面，后端 Spring Boot 服务调用本机 adb、hdc、libimobiledevice 等工具，实现 Android、HarmonyOS、iOS 设备管理、文件拉取、实时日志采集和日志导出。

### 2.2 关键决策问题

| 问题 | 结论 | 证据 |
|------|------|------|
| Java + H5 形态是否可行 | 可行 | 需求文档已定义 Spring Boot + H5 + ProcessBuilder 架构；DevBridge-Front 已有 H5 Demo 原型；DevBridge-Server 当前为空，可新建 Spring Boot 工程 |
| H5 能否直接完成 USB 通信 | 不建议 | WebUSB 存在浏览器、权限、设备协议和驱动限制；手机管理更适合由本机 Java 服务调用成熟 CLI |
| Android 是否可跨平台支持 | 可行 | adb 是 Android 官方命令行工具，支持 devices、shell、pull、logcat 等能力 |
| HarmonyOS 是否可跨平台支持 | 有条件可行 | 需求文档以 hdc 为工具入口；需安装 DevEco/HarmonyOS SDK 并验证目标设备授权、命令输出差异 |
| iOS 是否可完整跨平台支持 | 不可完整承诺 | 官方能力主要在 macOS/Xcode 生态；非 macOS 需依赖 libimobiledevice，文件访问受 iOS 沙盒、配对、备份机制限制 |
| 前端 Demo 是否可复用 | 部分可复用 | DevBridge-Front 是 React + Vite + Tailwind/Lucide 的 mock Demo，页面结构完整，但未接后端 API |
| 当前本机开发环境是否具备后端开发基础 | 具备 Java/Maven/Node，缺少设备工具 | 本机 Java 17、Maven、Node、pnpm 可用；adb、hdc、idevice_id 等命令当前未发现 |

### 2.3 范围与非范围

- 范围：单机本地服务、USB 连接设备发现、工具路径检测、Android/HarmonyOS 文件与日志、iOS 降级能力、React H5 Demo 复用、Spring Boot 后端技术路线。
- 非范围：越狱/root 后的私有数据读取、企业 MDM、云端设备农场、远程调试代理、驱动安装器完整开发、移动端 App 辅助程序。

## 3. 现状扫描

### 3.1 已读取材料

| 类型 | 路径/对象 | 用途 |
|------|-----------|------|
| 需求文档 | `跨平台 USB 手机设备管理与日志采集工具需求（Java + H5 实现）.md` | 明确产品形态、平台目标、工具链、接口草案和部署方式 |
| 前端文档 | `DevBridge-Front/README.md` | 确认 Demo 来源和运行方式 |
| 前端源码 | `DevBridge-Front/src/app/App.tsx` | 确认设备列表、文件树、日志视图、截图面板均为 mock 实现 |
| 前端依赖 | `DevBridge-Front/package.json` | 确认 React、Vite、Tailwind、Lucide、MUI/Radix 等依赖 |
| Vite 配置 | `DevBridge-Front/vite.config.ts` | 确认当前构建工具和别名配置 |
| 后端目录 | `DevBridge-Server/` | 当前为空目录，适合新建 Spring Boot 3 工程 |
| 官方/开源资料 | Android adb、MDN WebUSB、Oracle jpackage、libimobiledevice GitHub | 校验关键工具与浏览器能力边界 |

### 3.2 当前技术栈

| 层次 | 技术/版本 | 证据 | 备注 |
|------|-----------|------|------|
| 前端 | React + Vite 6.3.5 + Tailwind 4.1.12 | `DevBridge-Front/package.json` | 与需求文档的 Vue3 假设不一致，建议直接沿用 React Demo |
| UI | lucide-react、Radix UI、MUI、Tailwind | `DevBridge-Front/package.json` | Demo 已形成较完整工具型界面 |
| 后端 | 暂无 | `DevBridge-Server/` 为空 | 建议 Spring Boot 3 + Java 17 + Maven |
| 本机 Java | Java 17 可用；Maven 可用 | `java -version`、`mvn -version` | Maven 当前默认 JDK 显示为 25，正式工程需固定 Maven toolchain 或 JAVA_HOME 到 17 |
| 本机前端环境 | Node v25.8.1，pnpm 11.7.0 | `node --version`、`pnpm --version` | Vite 可支持开发，但生产建议锁定 Node LTS |
| 本机设备工具 | adb/hdc/idevice_id 未发现 | `command -v adb hdc idevice_id ...` | PoC 前需安装或配置工具路径 |

### 3.3 现有能力

| 能力 | 支持程度 | 证据 | 限制 |
|------|----------|------|------|
| 设备列表 UI | 已支持 UI 原型 | `App.tsx` 中 `DEVICES` mock 数据和侧栏列表 | 未接真实 API |
| 文件树 UI | 已支持 UI 原型 | `App.tsx` 中 `FILES` mock 数据和 `TreeNode` | 未实现远端目录懒加载、下载、权限失败提示 |
| 实时日志 UI | 已支持 UI 原型 | `App.tsx` 中 `setInterval` 生成 mock log | 未接 SSE/WebSocket，未处理进程生命周期 |
| 工具状态 UI | 部分支持 | `App.tsx` 中 adb/hdc/idevice_id 静态状态 | 需要后端工具探测 API |
| 截图 UI | 部分支持 | `ScreenshotPanel` 使用网络图片模拟截图 | 真截图命令各平台差异大，可作为后续能力 |
| 后端 API | 不支持 | `DevBridge-Server/` 为空 | 需要新建工程 |
| 跨平台打包 | 不支持 | 无后端构建配置 | 后续可用 Spring Boot fat jar + jpackage |

## 4. 能力匹配与差距

| 需求能力 | 现有支持 | 差距 | 复用/改造建议 |
|----------|----------|------|---------------|
| 检测并列出 USB 设备 | 前端已有展示壳 | 后端缺少 adb/hdc/idevice 枚举与输出解析 | 新增 `/api/devices`；先返回平台、serial、status、model，复杂信息延后 |
| 工具路径管理 | 前端有静态工具状态 | 缺少工具自动发现、配置持久化、版本探测 | 新增 `/api/tools/status`、`/api/tools/config`；支持 PATH 和显式路径 |
| Android 文件浏览/下载 | 前端文件树可复用 | 缺少 `adb shell ls/stat` 解析、`adb pull` 临时文件与下载流 | 一期限定 `/sdcard`、`/storage/emulated/0` 等公共目录；禁止任意本机路径写入 |
| HarmonyOS 文件浏览/下载 | 前端文件树可复用 | 需验证 hdc 输出格式和授权状态 | 适配 `hdc list targets`、`hdc file recv`、`hdc shell`/目录命令；PoC 必须真机验证 |
| iOS 文件读取 | UI 可复用但产品定义需调整 | 未越狱 iOS 无通用文件系统浏览；Windows 依赖复杂 | 一期改为“备份数据导出/日志采集”，不展示普通文件树或只展示备份解析结果 |
| 实时日志采集 | 前端日志窗口可复用 | 缺少长连接、过滤、进程停止、背压控制 | 推荐 SSE 起步，日志控制 API 管理进程；需要按 device/session 绑定 |
| 日志导出 | UI 有命令提示 | 缺少导出任务、文件存储、下载接口 | 新增导出任务，输出到应用受控目录，提供下载并设置大小/时间限制 |
| 安全与隐私 | 需求有“本地处理”描述 | 缺少本地监听限制、命令注入防护、日志脱敏策略 | 服务仅绑定 127.0.0.1；所有命令用 allowlist + 参数数组，不拼 shell |
| 打包分发 | 需求有方案 | 工程缺少 Maven、静态资源复制、jpackage 配置 | 二期补齐；MVP 先 fat jar + 前端 build 静态资源 |

## 5. 技术路线候选

### 5.1 方案 A：外部 CLI 适配层（推荐）

- 路线：Spring Boot 提供 REST + SSE/WebSocket；内部通过 `ProcessBuilder` 调用 adb、hdc、libimobiledevice 命令。前端沿用当前 React Demo，替换 mock 数据为 API 调用。
- 核心要点：
  - 后端维护少量平台适配类：AndroidAdb、HarmonyHdc、IosLibimobile，避免复杂插件化。
  - 命令执行统一走 `ProcessRunner`，只允许预定义命令和参数，不允许用户输入进入 shell。
  - 日志采集使用独立进程会话，前端断开后关闭进程，防止后台遗留。
  - 文件下载先拉到应用临时目录，再通过受控下载接口返回，完成后清理。
  - 工具路径从配置文件、环境 PATH、常见安装目录三层发现。
- 改动范围：新增 `DevBridge-Server` Spring Boot 工程；改造 `DevBridge-Front/src/app/App.tsx` 的数据层；增加前后端构建集成。
- 优点：实现成本低、跨平台边界清晰、可快速 PoC、与需求文档一致。
- 缺点：依赖外部工具安装和驱动；命令输出解析需兼容版本差异；iOS 能力受 libimobiledevice 限制。
- 适用条件：用户接受首次使用引导安装 adb/hdc/libimobiledevice，并接受 iOS 降级功能。

### 5.2 方案 B：Java 原生 USB/协议实现（不推荐）

- 路线：使用 Java USB/JNA/JNI 或第三方库直接实现设备协议。
- 核心要点：需要处理 USB 驱动、权限、Android/iOS/HarmonyOS 私有协议、配对认证、日志协议。
- 改动范围：后端底层大幅复杂化，可能需要平台原生模块。
- 优点：理论上减少外部 CLI 依赖，体验可控。
- 缺点：协议复杂、维护成本高、跨平台驱动问题多，iOS 私有协议和签名/配对风险大。
- 适用条件：只有在 CLI 无法满足且团队愿意长期维护底层协议时考虑。

### 5.3 方案 C：桌面容器化（Electron/Tauri）+ 本机后端（可选增强）

- 路线：保留 H5 UI，使用 Electron/Tauri 提供桌面壳，后端可内嵌 Java 服务或改为 Node/Rust native command。
- 核心要点：提升双击启动、托盘、文件选择器、自动更新体验。
- 改动范围：新增桌面壳和打包链路。
- 优点：桌面体验更完整。
- 缺点：分发复杂度上升；与当前 Java + H5 需求不完全一致；MVP 非必要。
- 适用条件：后续需要商业化分发、自动升级、系统托盘和原生权限提示。

## 6. 推荐方案

- 推荐：方案 A，外部 CLI 适配层。
- 推荐理由：
  - 与需求文档架构一致，Spring Boot + H5 可以直接落地。
  - adb、hdc、libimobiledevice 已经覆盖主要设备管理协议，避免重造底层轮子。
  - 当前前端 Demo 已具备设备列表、文件树、日志台、工具状态等界面资产，可快速接入真实数据。
  - 后端为空目录，采用 Spring Boot 3 从零建立清晰分层成本低。
- 不选其他方案的理由：
  - Java 原生 USB 方案过重，且会把最大不确定性推到驱动和私有协议层。
  - Electron/Tauri 解决的是桌面壳体验，不是 USB 能力本身，适合二期。
- 前置条件：
  - 明确 iOS 一期能力边界：发现、日志、备份导出，不承诺全文件浏览。
  - 提供工具安装/配置向导。
  - 后端服务默认只监听 `127.0.0.1`。
  - 文件写入限定在应用工作目录和用户确认的下载目录。
  - PoC 至少在 macOS 上验证 Android 或 iOS 真机；HarmonyOS 需要有可用真机和 hdc 工具。

## 7. 风险矩阵

| 风险 | 等级 | 触发条件 | 影响范围 | 发现方式 | 缓解措施 | 回退方案 | 是否需 Demo |
|------|------|----------|----------|----------|----------|----------|-------------|
| iOS 通用文件浏览不可达 | 高 | 未越狱设备、无 App 沙盒权限、Windows 缺失驱动/组件 | iOS 文件管理功能 | PoC 执行 idevicebackup2/ifuse 并验证目录可见性 | 产品上降级为备份导出；文案明确限制 | iOS 只保留设备发现和日志 | 是 |
| 命令注入或越权文件访问 | 高 | 用户输入路径、serial、包名直接拼接 shell | 本机安全、用户数据 | 安全代码审查、单元测试恶意参数 | 使用 ProcessBuilder 参数数组、命令 allowlist、路径归一化、禁止 shell 重定向 | 禁用相关接口或只读模式 | 是 |
| 日志流进程泄漏 | 高 | 前端断开、刷新页面、网络异常时未停止 adb logcat/hdc hilog | 本机资源、设备连接稳定性 | 进程列表、集成测试、超时监控 | session 绑定进程，断连/超时销毁，限制并发 | 停止日志服务并清理进程 | 是 |
| 外部工具未安装或版本差异 | 中 | adb/hdc/idevice 命令不存在或输出格式不同 | 设备发现、文件、日志 | `/api/tools/status` 和版本探测 | 工具路径配置、版本显示、输出解析容错 | 功能置灰并提示安装 | 是 |
| HarmonyOS 真机授权和命令差异 | 中 | 不同 HarmonyOS/OpenHarmony 版本、hdc 命令差异 | HarmonyOS 能力 | 真机 PoC | 平台适配层隔离，先实现最小命令 | 暂只支持设备发现/日志 | 是 |
| 大文件下载占满磁盘 | 中 | 拉取视频、备份、系统日志过大 | 本机磁盘、下载稳定性 | 文件大小预估、磁盘余量检查 | 限制单文件大小，临时目录配额，分块传输 | 取消任务并清理临时文件 | 是 |
| 日志包含敏感信息 | 中 | logcat/syslog 包含 token、手机号、路径等 | 隐私合规 | 日志预览、导出前提示 | 导出提醒、可选脱敏、受控本地存储 | 删除导出文件 | 否 |
| 前端 Demo 与需求技术栈不一致 | 低 | 需求写 Vue3，但现有 Demo 是 React | 前端开发路线 | 已读 `package.json` | 直接沿用 React，避免重写 | 若必须 Vue3，另行评估迁移 | 否 |
| Maven 默认 JDK 与目标 Java 17 不一致 | 低 | 本机 Maven 使用 JDK 25 构建 | 构建兼容 | `mvn -version` | 配置 Maven compiler release 17 或 toolchains | 固定 JAVA_HOME 到 17 | 否 |

## 8. 安全与质量评估

- 性能：设备枚举可串行或短超时并行；日志流需限速、环形缓冲和最大行数；文件拉取必须分块下载，避免一次性读入内存。
- 可扩展性：平台适配只保留 Android/Harmony/iOS 三个直接实现，不做过度插件化；后续新增截图、安装包管理时在各适配类内补命令。
- 可维护性：后端对象建议保持简单：Device、ToolStatus、RemoteFile、LogSession、CommandResult；避免复杂层级和过长调用链。
- 高可用：本工具是单机应用，重点是局部失败隔离。某个平台工具不可用不应影响其他平台；单个日志进程失败不应导致服务退出。
- 数据一致性：文件导出任务需有明确状态机：created/running/succeeded/failed/cancelled；失败时清理临时文件。
- 安全：必须避免 shell 拼接、任意本机路径写入、目录穿越、局域网暴露、敏感日志默认持久化；API 可加入本机随机 token 或首次启动生成的 session key，降低本机恶意网页 CSRF 风险。
- 可观测性：记录工具路径、命令类型、耗时、退出码、错误摘要；不要记录完整敏感命令输出。日志会话需要开始/结束/异常事件。

## 9. 成本评估

| 工作项 | 工作量 | 依赖 | 说明 |
|--------|--------|------|------|
| Spring Boot 3 工程初始化 | 0.5 人天 | Java 17、Maven | 创建基础工程、健康检查、静态资源服务 |
| 工具发现与配置 | 1 人天 | adb/hdc/libimobiledevice 安装路径 | PATH + 显式配置 + 版本探测 |
| Android 设备发现/信息/日志 PoC | 1-1.5 人天 | Android 真机、adb | 包含 devices、getprop、logcat |
| Android 文件浏览/下载 PoC | 1-2 人天 | Android 真机授权 | 限公共目录，处理路径安全 |
| HarmonyOS hdc PoC | 1-2 人天 | HarmonyOS 真机、hdc | 需验证命令和输出格式 |
| iOS libimobiledevice PoC | 1-2 人天 | iPhone、配对、libimobiledevice | 验证 idevice_id、idevicesyslog、backup |
| 前端 API 接入 | 1-1.5 人天 | 后端接口稳定 | 替换 mock、加载/错误/空态 |
| 实时日志 SSE/WebSocket | 1-1.5 人天 | 后端进程管理 | 断连清理、过滤、导出 |
| 打包集成 | 1 人天 | 前后端构建 | 先 fat jar，jpackage 二期 |
| 单元/集成测试 | 1-2 人天 | mock 命令输出样本 | 解析器、命令安全、任务状态 |

MVP 预估：5-8 人天可完成 Android 主链路 + 前端接入 + 基础工具状态；三平台可用的第一版预计 10-15 人天，取决于真机和工具安装环境。

## 10. Demo/PoC 建议

- 是否需要 Demo：是。
- 验证目标：
  - 后端能发现本机工具路径并返回状态。
  - Android 或 HarmonyOS 真机能被枚举并读取基础信息。
  - 日志命令能通过 SSE/WebSocket 实时推送，并能在前端断开后正确停止。
  - 文件拉取能安全落到临时目录并通过浏览器下载。
  - iOS 能力边界用真机验证，确认是否只做日志/备份导出。
- Demo 范围：
  - `DevBridge-Server` 新建最小 Spring Boot 工程。
  - `DevBridge-Front` 只替换设备列表、工具状态、日志流中的一到两个接口。
  - 不做完整打包、不做复杂权限系统、不做多用户。
- 验收标准：
  - `GET /api/tools/status` 能返回 adb/hdc/idevice 工具状态。
  - `GET /api/devices` 在无工具时返回空设备和明确错误，不抛 500。
  - 接入一台真实设备时能返回 serial/status/platform。
  - 日志页面启动后有真实日志行，停止后后端进程退出。
  - 任意恶意路径参数不能写出应用临时目录。
- 不做内容：
  - iOS 通用文件系统浏览。
  - 驱动自动安装。
  - 桌面安装包和自动更新。
  - root/越狱能力。

## 11. 建议的后端接口边界

一期接口建议保持少而清晰：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tools/status` | 查询 adb、hdc、idevice_id、idevicesyslog 等工具是否可用、路径、版本、错误 |
| PUT | `/api/tools/config` | 保存用户指定工具路径 |
| GET | `/api/devices` | 枚举设备，返回平台、serial、status、model、osVersion |
| GET | `/api/devices/{platform}/{serial}/files` | 浏览允许范围内的远端目录 |
| POST | `/api/devices/{platform}/{serial}/files/download` | 创建文件下载任务或直接返回文件流 |
| GET | `/api/logs/stream` | SSE 实时日志流，参数包含 platform、serial、level/filter |
| POST | `/api/logs/export` | 导出日志文件 |
| POST | `/api/tasks/{taskId}/cancel` | 取消长任务或日志会话 |

说明：实时日志一期推荐 SSE，因为日志是服务端到浏览器的单向流，协议更简单；如果后续需要前端在同一连接内动态切换过滤、暂停、继续，再升级 WebSocket。

## 12. 官方/开源资料参考

- Android Debug Bridge 官方文档：https://developer.android.com/tools/adb
- Android logcat 官方文档：https://developer.android.com/tools/logcat
- MDN WebUSB API：https://developer.mozilla.org/en-US/docs/Web/API/WebUSB_API
- Oracle jpackage 文档：https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html
- libimobiledevice 项目：https://github.com/libimobiledevice/libimobiledevice
- libimobiledevice 工具集：https://github.com/libimobiledevice/libimobiledevice/tree/master/tools

## 13. 后续建议

1. 先确认产品口径：iOS 一期是否接受“日志 + 备份导出”，不做通用文件树。
2. 做最小 PoC：优先 Android 主链路，因为 adb 的确定性最高，能最快验证整体架构。
3. 保留当前 React Demo，不建议为了贴合原需求文档改成 Vue3；重写前端不能降低 USB 侧风险。
4. PoC 通过后再进入正式 spec/design，补充权限边界、错误码、任务状态、工具安装指引和测试样本。
5. 正式开发时以安全为先：ProcessBuilder 参数数组、命令白名单、路径限制、127.0.0.1 监听、日志脱敏提示必须进入验收标准。
