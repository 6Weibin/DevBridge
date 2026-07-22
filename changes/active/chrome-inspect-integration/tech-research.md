# 技术预研：集成 chrome://inspect 风格 APP 前端调试能力

## 1. 预研结论

- 结论：有条件可行。
- 推荐方案：不要直接嵌入 `chrome://inspect` 内部页面，而是在 DevBridge 内实现等价的 Android WebView/Chrome DevTools Protocol 调试链路：设备目标发现、`adb forward`、CDP `/json/list` 目标读取、启动 DevTools 前端。
- 是否需要 Demo：是。该需求受真实设备、目标 APP 是否开启 WebView 调试、WebView/Chrome 版本影响，必须用一台 Android 调试设备和一个已开启 `WebView.setWebContentsDebuggingEnabled(true)` 的测试 APP 验证。
- 下一步：先做隔离 PoC/Demo，验证目标发现、端口转发、目标列表读取、打开 DevTools 页面后，再进入正式 spec/design。

直接把 `chrome://inspect` 当作普通页面嵌入当前 React/Electron 页面不可取：`chrome://` 是 Chrome 内部页面，不能作为普通 Web URL 被前端稳定嵌入、复用或分发。可行方向是复刻它背后的官方调试流程，而不是嵌入它本身。

## 2. 预研目标

### 2.1 新需求目标

用户希望在 DevBridge 当前工具下直接调试 APP 前端代码，类似 Chrome 的 `chrome://inspect` 能力：选择手机设备和 APP WebView 目标，打开调试器，查看 Console、Network、Elements、Sources，并能断点调试 H5/Hybrid 页面。

### 2.2 关键决策问题

| 问题 | 结论 | 证据 |
|------|------|------|
| 当前项目是否已有 Chrome Inspect / DevTools 集成？ | 没有 | `rg devtools/inspect/remote-debug` 仅命中 ADB forward 风险和工具目录说明，没有业务调试模块。 |
| 当前项目是否具备实现该能力的基础设施？ | 部分具备 | 后端已有 Spring Boot 本地 API、ADB 工具定位、设备连接校验、安全命令执行器；前端已有 React 页面；Electron 已可启动本地前后端服务。 |
| 能否直接嵌入 `chrome://inspect`？ | 不建议/基本不可作为产品方案 | `chrome://inspect` 属 Chrome 内部页面，不是可被普通 WebView 稳定嵌入的公开 Web 应用。 |
| 能否实现等价能力？ | Android WebView 场景有条件可行 | Chrome 官方远程调试文档要求 Android 设备 USB 调试、目标 WebView 开启调试；CDP 提供 `/json/list` 与 `webSocketDebuggerUrl` 用于目标发现和调试连接。 |
| 是否覆盖 iOS APP 前端调试？ | 不能用 `chrome://inspect` 覆盖 | iOS WKWebView 调试依赖 Safari/WebKit Remote Inspector 路线，不是 ADB/CDP/chrome://inspect 路线；当前预研建议先限定 Android WebView。 |

### 2.3 范围与非范围

- 范围：Android 设备、ADB、本地端口转发、WebView/Chrome DevTools Protocol 目标发现、DevTools 前端打开方式、当前 DevBridge 后端/前端/Electron 集成成本。
- 非范围：iOS Safari Web Inspector 深度集成、绕过 APP 调试开关、Hook/注入/Root 后强制开启 WebView 调试、完整生产代码实现。

## 3. 现状扫描

### 3.1 已读取材料

| 类型 | 路径/对象 | 用途 |
|------|-----------|------|
| 后端代码 | `DevBridge-Server/src/main/java/com/devbridge/server/command/CommandRunner.java` | 确认外部命令执行方式、超时、是否可安全执行 ADB。 |
| 后端代码 | `DevBridge-Server/src/main/java/com/devbridge/server/service/AndroidDeviceService.java` | 确认 Android ADB 工具定位、设备连接校验、命令组装方式。 |
| 后端代码 | `DevBridge-Server/src/main/java/com/devbridge/server/api/DeviceController.java` | 确认现有设备 API 边界，当前没有调试 API。 |
| 后端代码 | `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/execution/AdbCommandPlanner.java` | 确认现有 ADB MCP 已覆盖 `forward`、`shell`、`jdwp` 等顶层命令。 |
| 后端配置 | `DevBridge-Server/src/main/resources/application.yml` | 确认后端绑定 `127.0.0.1:8080`，适合本机调试端口能力。 |
| 前端代码 | `DevBridge-Front/src/app/App.tsx` | 确认主页面形态和设备页签，目前页签为信息/文件/日志/应用。 |
| 前端配置 | `DevBridge-Front/package.json` | 确认 React 18、Vite 6 技术栈。 |
| 桌面端代码 | `DevBridge-Electron/src/main.js` | 确认 Electron 启动本地前后端、CORS 桥接和本机端口模型。 |
| 桌面端配置 | `DevBridge-Electron/package.json` | 确认 Electron 31、electron-builder 打包链路。 |
| 官方资料 | Chrome DevTools Remote debugging / WebView debugging / CDP 文档 | 确认 Android WebView 远程调试前置条件和 CDP endpoint。 |

### 3.2 当前技术栈

| 层次 | 技术/版本 | 证据 | 备注 |
|------|-----------|------|------|
| 后端 | Spring Boot 3.3.7、Java 17 | `DevBridge-Server/pom.xml` | 本地 REST API，适合作为 ADB/CDP 编排层。 |
| 前端 | React 18.3.1、Vite 6.3.5 | `DevBridge-Front/package.json` | 可新增“前端调试/WebView 调试”页面。 |
| 桌面端 | Electron 31.7.7 | `DevBridge-Electron/package.json` | 可打开新窗口或外部浏览器承载 DevTools 前端。 |
| 命令执行 | `ProcessBuilder(List<String>)` | `CommandRunner.java` | 避免 shell 字符串拼接，适合安全执行 `adb forward`、`adb shell`。 |
| ADB 设备能力 | 自研 `AndroidDeviceService` | `AndroidDeviceService.java` | 已有 adb 定位、设备连接校验、`-s serial` 命令组装。 |
| 数据 | 无数据库 | `application.yml` 和依赖扫描 | 调试会话建议先用内存态，必要时写本地文件。 |

### 3.3 现有能力

| 能力 | 支持程度 | 证据 | 限制 |
|------|----------|------|------|
| 枚举 Android 设备 | 已支持 | `DeviceController#listDevices`、`AndroidDeviceService#ensureConnected` | 只判断设备连接/授权，不感知 WebView 调试目标。 |
| 执行受控 ADB 命令 | 已支持 | `CommandRunner#run(List<String>)`、`AndroidDeviceService#adbCommand` | `AndroidDeviceService` 的 `adb()` 私有，正式开发需抽出公共 ADB 执行组件或新增专用服务。 |
| ADB forward 能力 | 部分支持 | `AdbCommandPlanner` 允许 `forward` 顶层命令；`AdbToolCatalog` 有 `adb_forward` | MCP 是 AI 工具执行链路，不适合作为业务调试会话生命周期的唯一实现。 |
| 本机 HTTP API | 已支持 | `application.yml` 绑定 `127.0.0.1:8080` | 需要新增 Debug Controller/API。 |
| Electron 打开本地页面/服务 | 已支持 | `DevBridge-Electron/src/main.js` | 需要新增打开 DevTools 窗口或外部 Chrome 的动作。 |
| DevTools/CDP 目标发现 | 不支持 | 搜索未发现 `devtools_remote`、`webSocketDebuggerUrl`、`/json/list` 相关业务逻辑 | 需要新增。 |
| 调试端口生命周期管理 | 不支持 | 当前没有端口分配、`adb forward --remove` 清理、会话表 | 需要新增。 |
| iOS WebView 调试 | 不支持 | 当前 iOS 能力基于 libimobiledevice 工具，未发现 WebKit Remote Inspector 集成 | 与 `chrome://inspect` 技术路线不同。 |

## 4. 能力匹配与差距

| 需求能力 | 现有支持 | 差距 | 复用/改造建议 |
|----------|----------|------|---------------|
| 选择设备后发现可调试 APP 前端目标 | 已有设备枚举和 ADB 执行基础 | 缺少 `adb shell cat /proc/net/unix` 或等效 socket 发现逻辑，缺少目标解析模型 | 新增 `AndroidWebViewDebugService`，复用 adb 定位和连接校验。 |
| 建立本机到设备 WebView 调试端口 | ADB raw/MCP 支持 `forward`，命令执行器安全 | 缺少端口分配、冲突处理、会话记录、清理 | 新增受控 `adb -s <serial> forward tcp:<port> localabstract:<socket>`，只监听本机。 |
| 拉取调试目标列表 | 无 | 缺少 HTTP client 查询 `http://127.0.0.1:<port>/json/list`、`/json/version` | 后端新增 CDP target client，返回 title/url/type/devtoolsFrontendUrl/webSocketDebuggerUrl。 |
| 打开调试器 | Electron 可打开窗口，前端可触发 API | 缺少 DevTools frontend 选择和打开方式 | MVP 先打开外部 Chrome/系统浏览器；增强版再用 Electron `BrowserWindow` 打开兼容 DevTools frontend。 |
| 页面内集成调试体验 | React 可新增页面 | 缺少嵌入式 DevTools UI 和资源 | 不建议第一阶段做完整内嵌；先做目标列表 + 打开调试窗口。 |
| 结束调试 | 无 | 缺少 `adb forward --remove tcp:<port>`、窗口关闭回调、异常清理 | 调试会话使用后端内存注册表，提供 stop API，Electron 退出时调用清理。 |

## 5. 技术路线候选

### 5.1 方案 A：复刻 chrome://inspect 工作流，外部/独立窗口打开 DevTools（推荐）

- 路线：
  1. 后端新增 `AndroidWebViewDebugService` 和 `/api/debug/android/...`。
  2. 使用 ADB 检测设备上 `devtools_remote`/`webview_devtools_remote_<pid>` socket。
  3. 为每个目标分配本地端口并执行 `adb forward tcp:<port> localabstract:<socket>`。
  4. 后端查询 `http://127.0.0.1:<port>/json/list` 得到 `webSocketDebuggerUrl`、`devtoolsFrontendUrl`。
  5. 前端展示 APP/WebView 目标列表，点击“调试”后打开外部 Chrome 或 Electron 新窗口。
  6. 用户结束后调用 stop API，后端执行 `adb forward --remove tcp:<port>`。
- 核心要点：
  - 只绑定和访问 `127.0.0.1`。
  - socket 名、端口、设备序列号、目标 ID 都要校验。
  - 会话要有 TTL 和清理逻辑。
  - 对用户明确提示：只有开启 WebView 调试的 APP 才能发现。
- 改动范围：
  - 后端新增 debug controller/service/model/test。
  - 前端新增调试页签或按钮。
  - Electron 可选新增“打开调试窗口/外部浏览器”桥接。
- 优点：改动最小、与官方流程一致、风险可控、可先 PoC。
- 缺点：体验不等于完全内嵌；依赖用户本机 Chrome 或 Electron 对 DevTools 前端 URL 的兼容性。
- 适用条件：主要目标是 Android WebView/H5 Hybrid 调试。

### 5.2 方案 B：在 DevBridge 内嵌完整 DevTools Frontend

- 路线：在 Electron/React 中嵌入或打包 DevTools frontend 资源，通过 CDP WebSocket 连接目标。
- 核心要点：需要匹配 Chrome/WebView 版本、处理跨域和 WebSocket、维护 DevTools frontend 资源。
- 改动范围：Electron 窗口、资源打包、安全策略、前端路由、后端 CDP 代理。
- 优点：用户体验更像“工具内直接调试”。
- 缺点：维护成本高、版本兼容风险高、打包体积和安全边界复杂。
- 适用条件：方案 A 验证通过且产品强要求完全内嵌。

### 5.3 方案 C：尝试直接嵌入 `chrome://inspect`

- 路线：让 Electron/Chromium 窗口加载 `chrome://inspect`。
- 核心要点：依赖 Chromium 内部页面行为。
- 改动范围：Electron 窗口配置。
- 优点：看起来接近用户描述。
- 缺点：不是稳定公开集成接口，无法保证目标发现、权限、打包后可用性、跨平台一致性；不适合作为正式产品方案。
- 适用条件：仅可作为人工验证参考，不建议立项实现。

## 6. 推荐方案

- 推荐：方案 A，复刻 `chrome://inspect` 背后的 ADB + CDP 工作流，先提供“目标列表 + 打开调试器”的 MVP。
- 推荐理由：
  - 与 Chrome 官方 Android WebView 远程调试机制一致。
  - 当前项目已有 ADB 执行、设备选择、Electron 桌面容器和本地 API 基础。
  - 不需要引入数据库，也不需要大规模重构。
  - 安全边界可以限制在本机端口和显式用户操作内。
- 不选其他方案的理由：
  - 方案 B 的完整内嵌 DevTools 维护成本和兼容风险明显更高，不适合第一阶段。
  - 方案 C 依赖 `chrome://` 内部页面，不是可控、可测试、可打包的产品接口。
- 前置条件：
  - Android 设备已开启 USB 调试并授权。
  - 目标 APP 是 WebView/Chromium 内核页面，且应用侧开启 `WebView.setWebContentsDebuggingEnabled(true)`。
  - 用户理解调试端口暴露风险，并明确点击启动调试。
  - iOS 调试另立预研，不纳入本方案。

## 7. 风险矩阵

| 风险 | 等级 | 触发条件 | 影响范围 | 发现方式 | 缓解措施 | 回退方案 | 是否需 Demo |
|------|------|----------|----------|----------|----------|----------|-------------|
| 目标 APP 未开启 WebView 调试 | 高 | Release 包或第三方 APP 未调用 `WebView.setWebContentsDebuggingEnabled(true)` | 无法发现或连接 WebView 目标 | `devtools_remote` socket 不存在，`/json/list` 为空 | UI 明确提示前置条件；只支持 debug/可调试包 | 回退为提示用户改用 debug 包或 APP 侧开启调试 | 是 |
| 直接嵌入 `chrome://inspect` 不稳定 | 高 | Electron 加载 Chrome 内部页面或打包环境变化 | 功能不可用或跨平台失败 | PoC 加载失败/页面受限 | 不采用直接嵌入；使用 ADB+CDP 等价链路 | 打开外部 Chrome 或系统浏览器 | 否 |
| 本地 CDP 端口暴露调试控制能力 | 高 | 端口被其他本机进程访问 | 页面数据、Storage、JS 执行可被控制 | 安全评审、端口扫描 | 仅使用 `127.0.0.1`、随机空闲端口、用户确认、会话 TTL | 立即 `adb forward --remove` 并关闭会话 | 是 |
| 端口转发未清理 | 中 | 应用异常退出、窗口直接关闭、设备断开 | 端口占用、后续连接混乱 | `adb forward --list`、后端会话状态 | stop API、TTL、应用退出清理、启动时清理 DevBridge 标记端口 | 手动/自动执行 `adb forward --remove tcp:<port>` | 是 |
| 多设备/多 WebView 目标冲突 | 中 | 多设备同时连接、多个 APP WebView socket | 错连目标或列表混乱 | 多设备测试 | 会话绑定 serial + socket + localPort；前端显示设备和包名 | 单设备限制作为 MVP 回退 | 是 |
| DevTools frontend 与 WebView 版本不兼容 | 中 | 本机 Chrome/Electron DevTools frontend 版本差距大 | 页面打开但部分面板异常 | 真实设备打开 Console/Sources/Network 验证 | MVP 优先外部 Chrome；内嵌作为二期 | 提供复制 `webSocketDebuggerUrl`/打开外部 Chrome | 是 |
| iOS 用户误认为也支持 | 中 | 选择 iOS 设备尝试调试 | 功能预期不一致 | UI/需求评审 | 明确标注“当前仅 Android WebView” | 单独预研 Safari/WebKit Remote Inspector | 否 |
| ADB 命令安全边界扩大 | 中 | 直接开放 raw shell/forward 给前端 | 命令注入或误操作 | 代码审查、单测 | 后端只暴露白名单动作，继续使用 `ProcessBuilder(List<String>)` | 禁用调试模块开关 | 是 |

## 8. 安全与质量评估

- 性能：调试链路主要是 ADB port forward 与 CDP WebSocket，单会话资源较小；需要限制并发会话数和目标列表刷新频率。
- 可扩展性：后端建议新增独立 `debug` 模块，不塞进现有 `DeviceController`；后续可扩展 iOS/WebKit 或 Harmony 调试路线。
- 可维护性：保留 ADB 命令白名单和模型化 API，不让前端直接拼任意命令；关键解析逻辑补单测。
- 高可用：设备断开、APP 退出、socket 消失时返回明确错误；会话停止必须幂等。
- 数据一致性：无数据库，调试会话用内存态即可；正式版本可选写入本地 session 文件用于异常恢复。
- 安全：CDP 可读写页面、执行 JS、查看 Storage/Network，必须本机访问、用户确认、会话 TTL、显式关闭；日志避免记录完整 URL token、Cookie、Authorization。
- 可观测性：记录 sessionId、serial、socket、localPort、状态变化和错误码；敏感 URL/headers 脱敏。

## 9. 成本评估

| 工作项 | 工作量 | 依赖 | 说明 |
|--------|--------|------|------|
| PoC：目标发现 + adb forward + `/json/list` | 1~2 人天 | Android 设备、开启 WebView 调试的测试 APP | 验证核心可行性。 |
| 后端生产化 debug API/service/model/test | 2~3 人天 | PoC 通过 | 包含端口分配、会话生命周期、错误处理、单测。 |
| 前端调试页签/目标列表/启动停止交互 | 1~2 人天 | 后端 API | 展示目标、状态、错误提示和安全确认。 |
| Electron 打开调试窗口/外部 Chrome 适配 | 1~2 人天 | Electron 31 | MVP 可先打开系统默认浏览器，增强为专用窗口。 |
| 安全与异常清理 | 1 人天 | 后端会话模型 | 包含 TTL、退出清理、端口释放。 |
| 多设备/多目标兼容测试 | 1~2 人天 | 多设备环境 | 覆盖设备断开、APP 重启、多个 WebView。 |

## 10. Demo/PoC 建议

- 是否需要 Demo：是。
- 验证目标：
  1. 在真实 Android 设备上发现 `webview_devtools_remote` 或 `webview_devtools_remote_<pid>`。
  2. DevBridge 后端能建立 `adb forward` 到本地随机端口。
  3. 后端能读取 `http://127.0.0.1:<port>/json/list` 并解析目标。
  4. 点击目标能打开 DevTools，并完成 Console 查看、Sources 断点、Network 查看中的至少两项。
  5. 停止会话后端口转发被清理。
- Demo 范围：
  - 可只做隔离脚本或 `changes/active/chrome-inspect-integration/demo/` 下的最小 Spring 测试接口。
  - 不做完整 UI、不做 iOS、不做 DevTools frontend 打包。
- 验收标准：
  - `adb -s <serial> shell cat /proc/net/unix` 能发现目标 socket。
  - `adb -s <serial> forward tcp:<port> localabstract:<socket>` 成功。
  - `curl http://127.0.0.1:<port>/json/list` 返回包含 `webSocketDebuggerUrl` 的目标列表。
  - 打开 DevTools 后可查看目标页面 Console/Sources/Network。
  - 执行 `adb -s <serial> forward --remove tcp:<port>` 后端口不可访问。
- 不做内容：不绕过 APP 调试限制，不尝试 root/hook，不承诺调试第三方 release APP，不承诺 iOS。

## 11. 后续建议

1. 先确认需求边界：第一阶段只支持 Android WebView/Chrome 目标；iOS 另开 Safari/WebKit Remote Inspector 预研。
2. 进入 PoC 前准备一个开启 `WebView.setWebContentsDebuggingEnabled(true)` 的测试 APP 和一台真实 Android 设备。
3. PoC 通过后进入正式 spec/design，重点定义 API、会话生命周期、端口安全、UI 安全提示和清理策略。

## 12. 官方依据

| 来源 | 关键结论 |
|------|----------|
| Chrome DevTools：Remote debug Android devices | Android 远程调试通过 USB 调试和 Chrome DevTools 连接移动端 Chrome/WebView 目标，用户通常在桌面 Chrome 的 `chrome://inspect` 中查看 Remote Target。 |
| Chrome DevTools：Remote debug WebViews | Android APP 的 WebView 需要应用侧启用 WebView debugging，之后可通过 Chrome DevTools inspect WebView 内容。 |
| Android API：`WebView.setWebContentsDebuggingEnabled(boolean)` | 从 Android 4.4/KITKAT 起可开启 WebView 内容调试；这是 APP 侧能力，不应由 DevBridge 强制绕过。 |
| Chrome DevTools Protocol | CDP 暴露 `/json/version`、`/json/list`、`webSocketDebuggerUrl` 等目标发现与 WebSocket 调试入口，可用于实现 `chrome://inspect` 等价自动化流程。 |
