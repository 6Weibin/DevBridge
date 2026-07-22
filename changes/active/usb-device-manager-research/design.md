# Design：跨平台 USB 手机设备管理工具 MVP

> 来源: spec.md  
> 生成时间: 2026-06-29  
> 阶段: design

### Why

**背景与现状**

当前工程已经具备 Spring Boot 3 后端骨架、React + Vite 前端 Demo、内置 macOS/Windows ADB、工具路径定位、工具状态探测、设备枚举、ADB 诊断接口和运行环境诊断接口。后端现有核心类包括 `DeviceController`、`DeviceService`、`CommandRunner`、`ExecutableLocator`、`DeviceOutputParser`；前端当前由 `App.tsx` 集中承载设备列表、文件树、日志窗口和工具状态展示。

现有 `CommandRunner` 适合短命令调用，不适合实时日志这类长进程；现有文件树和日志窗口仍使用 mock 数据；设备详情只返回最小字段。MVP 需要在不重写架构的前提下补齐 Android 主链路：设备详情、公共目录浏览、单文件下载、实时 logcat 和日志导出，同时保持 HarmonyOS/iOS 的发现降级。

**设计目标 / 非目标**

| 类型 | 说明 |
|------|------|
| ✅ 目标 | 在现有 Spring Boot 服务内新增 Android 设备详情、文件、日志 API |
| ✅ 目标 | 复用 `ExecutableLocator` 和 `CommandRunner`，只为日志新增长进程流式能力 |
| ✅ 目标 | 前端优先展示真实接口数据，后端不可达时继续降级到演示数据 |
| ✅ 目标 | 所有设备路径、下载临时文件和外部命令调用都有明确安全边界 |
| ❌ 非目标 | 不实现数据库持久化、目录打包下载、桌面安装包和自动升级 |
| ❌ 非目标 | 不实现 iOS 通用文件系统浏览、HarmonyOS 文件下载和 hdc 自动下载 |
| ❌ 非目标 | 不引入插件框架、任务队列中间件或多用户权限系统 |

### What

#### 技术方案

**架构决策**

| 模块 | 职责 | 依赖 |
|------|------|------|
| `DeviceController` | 保留设备、工具、运行环境和诊断接口入口 | `DeviceService`、`ToolStatusService`、`RuntimeEnvironmentService` |
| `AndroidDeviceService` | Android 设备详情、文件列表、文件下载、日志导出 | `ExecutableLocator`、`CommandRunner`、`AndroidPathGuard` |
| `DeviceService` | 继续负责多平台设备发现和 ADB 诊断 | `ExecutableLocator`、`CommandRunner`、`DeviceOutputParser` |
| `FileController` | Android 文件列表与下载 API | `AndroidDeviceService` |
| `LogController` | 实时日志 SSE、日志停止和日志导出 API | `LogStreamService`、`AndroidDeviceService` |
| `LogStreamService` | 管理单设备实时日志会话、进程生命周期和 SSE 推送 | `StreamingCommandRunner`、`ExecutableLocator` |
| `StreamingCommandRunner` | 启动长进程并逐行回调 stdout/stderr | JDK `ProcessBuilder` |
| `AndroidPathGuard` | 校验远端路径只在允许根目录内 | 无 |
| `RemoteFileParser` | 解析 Android 目录列表输出 | 无 |
| `ApiExceptionHandler` | 统一业务错误响应，避免 Controller 分散处理 | `ApiError`、`BusinessException` |
| `App.tsx` 数据层 | 接入真实设备详情、文件树、下载、日志流和错误提示 | 后端 REST/SSE API |

模块关系：

```text
H5 App.tsx
  -> DeviceController     -> DeviceService / ToolStatusService / RuntimeEnvironmentService
  -> FileController       -> AndroidDeviceService -> CommandRunner / AndroidPathGuard
  -> LogController        -> LogStreamService     -> StreamingCommandRunner
                         -> AndroidDeviceService -> CommandRunner
```

**数据模型变更**

本次不引入数据库表，全部状态保存在内存或请求生命周期内。

| 操作 | 表/实体 | 字段 | 类型 | 约束 | 说明 |
|------|---------|------|------|------|------|
| 新增 | `DeviceDetail` | `id`、`serial`、`platform`、`status`、`brand`、`model`、`osVersion`、`apiLevel`、`battery`、`resolution`、`storage` | record 字段 | 字符串允许为空，status 非空 | Android 设备详情返回模型 |
| 新增 | `RemoteFileNode` | `name`、`path`、`type`、`sizeBytes`、`modified`、`permissions`、`owner`、`group` | record 字段 | path、type 非空 | 远端文件/目录列表模型 |
| 新增 | `RemoteFileType` | `FILE`、`DIRECTORY` | enum | 非空 | 文件类型枚举 |
| 新增 | `LogEvent` | `id`、`timestamp`、`level`、`pid`、`tag`、`message`、`eventType` | record 字段 | id 非空 | SSE 推送日志事件模型 |
| 新增 | `LogSessionInfo` | `sessionId`、`platform`、`serial`、`status`、`startedAt` | record 字段 | sessionId 非空 | 日志会话状态模型 |
| 新增 | `ApiError` | `code`、`message`、`detail` | record 字段 | code、message 非空 | 统一错误响应 |
| 新增 | `BusinessException` | `errorCode`、`message`、`httpStatus` | class 字段 | 非空 | 后端业务异常 |
| 修改 | `DevBridgeProperties` | `downloadTempRoot`、`maxDownloadBytes`、`logStreamTimeout` | 配置字段 | 有默认值 | 下载临时目录、下载大小、日志会话超时 |

实体关系：`DeviceInfo` 1:N `RemoteFileNode` 查询结果；`DeviceInfo` 1:1 `LogSessionInfo` 活跃日志会话；`LogSessionInfo` 1:N `LogEvent` 推送事件。

**接口定义**

| 接口 | 方法 | 路径/签名 | 入参 | 出参 | 说明 |
|------|------|----------|------|------|------|
| 查询设备详情 | GET | `/api/devices/{platform}/{serial}/detail` | `platform`、`serial` | `DeviceDetail` | MVP 只支持 Android 详情；其他平台返回能力不支持 |
| 浏览远端目录 | GET | `/api/devices/{platform}/{serial}/files` | `platform`、`serial`、`path` | `List<RemoteFileNode>` | Android 允许目录内文件列表 |
| 下载远端文件 | GET | `/api/devices/{platform}/{serial}/files/download` | `platform`、`serial`、`path` | `application/octet-stream` | Android 单文件下载 |
| 实时日志流 | GET | `/api/devices/{platform}/{serial}/logs/stream` | `platform`、`serial`、`level`、`filter` | `text/event-stream` | Android logcat SSE |
| 停止日志流 | POST | `/api/logs/sessions/{sessionId}/stop` | `sessionId` | `LogSessionInfo` | 显式停止日志会话 |
| 导出日志 | GET | `/api/devices/{platform}/{serial}/logs/export` | `platform`、`serial` | `text/plain` 下载响应 | Android logcat 快照导出 |
| 工具状态 | GET | `/api/tools/status` | 无 | `List<ToolStatus>` | 复用现有接口 |
| 设备列表 | GET | `/api/devices` | 无 | `List<DeviceInfo>` | 复用现有接口 |
| 运行环境 | GET | `/api/runtime/environment` | 无 | `RuntimeEnvironment` | 复用现有接口 |

关键服务签名：

| 类 | 方法签名 | 说明 |
|----|----------|------|
| `AndroidDeviceService` | `DeviceDetail getDetail(String serial)` | 获取 Android 设备详情 |
| `AndroidDeviceService` | `List<RemoteFileNode> listFiles(String serial, String path)` | 浏览 Android 公共目录 |
| `AndroidDeviceService` | `Path pullFile(String serial, String remotePath)` | 拉取单个远端文件到应用临时目录 |
| `AndroidDeviceService` | `Path exportLogs(String serial)` | 导出 logcat 快照到临时文件 |
| `LogStreamService` | `SseEmitter streamAndroidLogs(String serial, LogStreamQuery query)` | 创建 Android 日志 SSE 会话 |
| `LogStreamService` | `LogSessionInfo stop(String sessionId)` | 停止指定日志会话 |
| `StreamingCommandRunner` | `StreamingProcess start(List<String> command, Duration timeout, Consumer<String> stdout, Consumer<String> stderr)` | 启动长进程并逐行回调 |
| `StreamingProcess` | `String id()`、`boolean isAlive()`、`void stop()` | 长进程句柄 |
| `AndroidPathGuard` | `String validateRemotePath(String path)` | 校验并规范化远端路径 |
| `RemoteFileParser` | `List<RemoteFileNode> parseAndroidLs(List<String> lines, String parentPath)` | 解析目录列表输出 |
| `ApiExceptionHandler` | `ResponseEntity<ApiError> handleBusinessException(BusinessException ex)` | 统一业务异常响应 |

**错误处理策略**

| 错误类型 | 处理方式 | HTTP状态码/异常类 |
|---------|---------|----------------|
| 平台不支持 | 返回 `PLATFORM_UNSUPPORTED` | 400 / `BusinessException` |
| 工具缺失 | 返回 `TOOL_NOT_FOUND`，说明缺失工具名 | 409 / `BusinessException` |
| 设备未连接 | 返回 `DEVICE_NOT_CONNECTED` | 409 / `BusinessException` |
| 设备未授权 | 返回 `DEVICE_UNAUTHORIZED` | 409 / `BusinessException` |
| 路径越界 | 返回 `REMOTE_PATH_FORBIDDEN`，不执行设备命令 | 400 / `BusinessException` |
| 远端路径不存在 | 返回 `REMOTE_PATH_NOT_FOUND` | 404 / `BusinessException` |
| 下载超限 | 返回 `FILE_TOO_LARGE` | 413 / `BusinessException` |
| 命令超时 | 返回 `COMMAND_TIMEOUT` | 504 / `BusinessException` |
| 日志会话已存在 | 返回 `LOG_SESSION_EXISTS` | 409 / `BusinessException` |
| 日志会话不存在 | 返回 `LOG_SESSION_NOT_FOUND` | 404 / `BusinessException` |
| 未预期异常 | 返回通用错误，不暴露完整命令输出 | 500 / `Exception` |

#### 关键决策与理由

| 决策 | 可选方案 | 选择 | 理由 |
|------|---------|------|------|
| 实时日志协议 | A: SSE / B: WebSocket | SSE | MVP 只有服务端向浏览器推送日志，SSE 更简单，断开事件易于清理 |
| 文件下载方式 | A: 先拉到临时文件再返回 / B: 直接管道转发 adb 输出 | 先拉到临时文件再返回 | adb pull 语义更稳定，便于大小限制、失败清理和浏览器下载响应 |
| 平台适配范围 | A: 只做 Android 主链路 / B: 三平台文件日志全量 | Android 主链路 + 其他平台降级 | adb 已验证，hdc/iOS 文件能力未验证，避免 P0 被外部环境阻塞 |
| 状态存储 | A: 内存状态 / B: 数据库 | 内存状态 | MVP 是单机工具，日志会话和临时文件状态不需要跨重启持久化 |
| 错误响应 | A: Controller 各自处理 / B: 统一异常处理 | 统一异常处理 | 前端需要稳定错误码，后端避免重复 try/catch |
| 命令执行 | A: 拼 shell 字符串 / B: 参数数组 allowlist | 参数数组 allowlist | 防止命令注入，复用现有 `CommandRunner` 安全边界 |
| 前端改造范围 | A: 保持 `App.tsx` 集中改造 / B: 大拆组件 | 保持集中改造，必要处提取小函数 | 当前 Demo 单文件成型，MVP 优先降低改动面；后续再做组件化 |
| 同源打包 | A: 后端提供前端静态资源 / B: 继续分离部署 | 后端提供静态资源，同时保留开发分离 | 交付形态要求本机单进程使用，开发阶段仍需 Vite 热更新 |

#### 风险与权衡

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| adb `ls` 输出在不同 Android 版本格式不同 | 中 | 文件列表解析失败 | 采用稳定字段命令输出格式，保留解析样本测试，失败返回业务错误 |
| logcat 长进程泄漏 | 中 | 本机资源占用、设备连接异常 | `SseEmitter` 完成/超时/错误时统一停止进程，单设备限制 1 个会话 |
| 大文件占用磁盘 | 中 | 本机磁盘压力 | 配置最大下载字节数，下载完成/失败清理临时文件 |
| 路径校验绕过 | 低 | 越权读取设备路径 | 所有文件操作先经过 `AndroidPathGuard`，拒绝 `..` 和非允许根目录 |
| Windows USB 驱动缺失 | 中 | Windows 设备不可见 | `/api/tools/status` 和 README 指引驱动/授权检查，功能保持降级 |
| 前端单文件继续变大 | 中 | 后续维护成本上升 | MVP 只提取 API helper 和小组件，编码后如超过可维护阈值再按功能拆分 |

**发布策略**：
- **发布方式**：MVP 一次性交付到本地工程，默认开发模式前后端分离，验收通过后启用后端静态资源同源访问。
- **回滚条件**：`mvn test`、`pnpm build`、`/api/devices`、`/api/tools/status` 任一 P0 验证失败时回滚对应功能改动。
- **数据迁移**：无数据库迁移。

#### 变更文件清单

| 文件路径 | 操作 | 变更说明 |
|---------|------|---------|
| `DevBridge-Server/src/main/java/com/devbridge/server/api/DeviceController.java` | 修改 | 保留现有设备、工具、诊断入口 |
| `DevBridge-Server/src/main/java/com/devbridge/server/api/FileController.java` | 新增 | 远端目录列表和文件下载接口 |
| `DevBridge-Server/src/main/java/com/devbridge/server/api/LogController.java` | 新增 | 日志 SSE、停止和导出接口 |
| `DevBridge-Server/src/main/java/com/devbridge/server/api/ApiExceptionHandler.java` | 新增 | 统一异常响应 |
| `DevBridge-Server/src/main/java/com/devbridge/server/service/AndroidDeviceService.java` | 新增 | Android 详情、文件、日志导出能力 |
| `DevBridge-Server/src/main/java/com/devbridge/server/service/LogStreamService.java` | 新增 | 实时日志会话管理 |
| `DevBridge-Server/src/main/java/com/devbridge/server/command/StreamingCommandRunner.java` | 新增 | 长进程流式命令执行 |
| `DevBridge-Server/src/main/java/com/devbridge/server/service/AndroidPathGuard.java` | 新增 | Android 远端路径校验 |
| `DevBridge-Server/src/main/java/com/devbridge/server/service/RemoteFileParser.java` | 新增 | 远端目录输出解析 |
| `DevBridge-Server/src/main/java/com/devbridge/server/model/*.java` | 新增/修改 | 设备详情、远端文件、日志事件、错误响应模型 |
| `DevBridge-Server/src/main/java/com/devbridge/server/config/DevBridgeProperties.java` | 修改 | 增加下载和日志配置 |
| `DevBridge-Server/src/main/resources/application.yml` | 修改 | 增加下载和日志默认配置 |
| `DevBridge-Server/src/test/java/com/devbridge/server/**` | 新增/修改 | 新增解析、路径、错误和日志会话测试 |
| `DevBridge-Front/src/app/App.tsx` | 修改 | 接入真实设备详情、文件和日志接口 |
| `DevBridge-Server/pom.xml` | 修改 | 集成前端静态资源时增加资源复制配置 |
| `DevBridge-Server/src/main/resources/static/**` | 新增 | 前端构建产物同源访问目录 |
| `DevBridge-Server/README.md` | 修改 | 更新启动、验证和能力限制 |
| `changes/active/usb-device-manager-research/*` | 修改 | 同步任务、验证和限制记录 |

### How

#### 任务拆分

| 任务名称 | 详细描述 | 关联设计章节 | 计划工作量(人天) |
|----------|---------|------------|--------------|
| 【错误模型】(后端) 建立统一业务错误响应 | 1. 新增 `ApiError`、`BusinessException` 和统一异常处理<br>2. 覆盖平台不支持、工具缺失、路径拒绝、命令超时等错误码<br>3. 保证错误响应不暴露完整敏感命令输出 | 错误处理策略 | 0.5 |
| 【设备详情】(后端) 实现 Android 设备详情服务 | 1. 新增 `DeviceDetail` 模型和详情接口<br>2. 通过 Android 命令读取品牌、型号、系统版本、API、电量、分辨率、存储摘要<br>3. 读取失败字段返回空值并保留基础设备信息<br>4. 增加命令输出解析和失败测试 | 数据模型变更 / 接口定义 | 1 |
| 【路径安全】(后端) 实现 Android 远端路径校验 | 1. 新增 `AndroidPathGuard`<br>2. 允许 `/sdcard`、`/storage/emulated/0` 根目录<br>3. 拒绝空路径、`..`、非允许根目录和控制字符<br>4. 增加路径边界单元测试 | 架构决策 / 错误处理策略 | 0.5 |
| 【文件列表】(后端) 实现 Android 公共目录浏览 | 1. 新增 `RemoteFileNode`、`RemoteFileType` 和 `RemoteFileParser`<br>2. 新增文件列表接口<br>3. 返回名称、路径、类型、大小、修改时间和权限摘要<br>4. 增加目录不存在和解析样本测试 | 数据模型变更 / 接口定义 | 1.5 |
| 【文件下载】(后端) 实现 Android 单文件下载 | 1. 新增下载配置和临时目录管理<br>2. 新增单文件下载接口<br>3. 完成、失败和取消后清理临时文件<br>4. 增加下载失败、路径非法、超限测试 | 接口定义 / 风险与权衡 | 1.5 |
| 【流式命令】(后端) 新增长进程执行能力 | 1. 新增 `StreamingCommandRunner` 和 `StreamingProcess`<br>2. 支持 stdout/stderr 逐行回调、超时和显式停止<br>3. 保持命令参数数组调用，不接受 shell 字符串<br>4. 增加进程停止测试 | 架构决策 / 接口定义 | 1 |
| 【日志流】(后端) 实现 Android logcat SSE | 1. 新增 `LogEvent`、`LogSessionInfo`、`LogStreamQuery`<br>2. 新增实时日志 SSE 接口和停止接口<br>3. 单设备限制 1 个日志会话<br>4. SSE 完成、超时和异常时停止进程 | 接口定义 / 风险与权衡 | 1.5 |
| 【日志导出】(后端) 实现 Android 日志导出 | 1. 新增日志导出接口<br>2. 返回浏览器可下载的文本响应<br>3. 处理命令超时和设备断开<br>4. 增加导出失败测试 | 接口定义 / 错误处理策略 | 1 |
| 【降级发现】(后端) 补强 HarmonyOS 和 iOS 降级能力 | 1. 保持 hdc 和 idevice 发现逻辑<br>2. 补充 hdc、idevice 输出解析样本测试<br>3. 工具缺失时提供前端禁用依据<br>4. 不实现 iOS 文件树和 HarmonyOS 文件下载 | 架构决策 / 数据模型变更 | 1 |
| 【前端数据】(前端) 接入设备详情和文件接口 | 1. 设备详情页按真实接口刷新<br>2. 文件树按当前目录懒加载真实数据<br>3. 文件下载按钮调用后端下载接口<br>4. 后端不可达时保留演示数据 | 接口定义 / 变更文件清单 | 1.5 |
| 【前端日志】(前端) 接入实时日志和导出 | 1. 使用 SSE 接入日志流<br>2. 实现开始、停止、清除、导出动作<br>3. 单视图最多保留 1000 行日志<br>4. 展示日志错误事件 | 接口定义 / 风险与权衡 | 1 |
| 【前端状态】(前端) 完善错误、授权和工具缺失提示 | 1. 展示统一错误码对应文案<br>2. unauthorized、offline、工具缺失时禁用不可执行入口<br>3. 移动端和桌面端检查文本不溢出<br>4. 保持当前视觉风格 | 错误处理策略 / 变更文件清单 | 1 |
| 【同源打包】(全栈) 集成前端静态资源到后端 | 1. 前端构建产物复制到后端静态资源目录<br>2. 后端 jar 启动后可访问 H5 页面和 API<br>3. 保留 Vite 开发模式 CORS 配置<br>4. 更新启动文档 | 发布策略 / 变更文件清单 | 1 |
| 【联调验收】联调测试与文档更新 | 1. macOS + Android 真机验证设备、详情、文件、日志主链路<br>2. Windows x64 验证内置 adb、设备发现和启动脚本<br>3. 执行 `mvn test`、`pnpm build` 和关键 API 验证<br>4. 更新 README、工具说明和验收记录 | Verify / Impact | 1.5 |
| **合计** | | | **15** |

**任务排序原则**：先错误模型和安全边界，再后端设备/文件/日志接口，再前端真实接入，最后同源打包和联调验收。

**任务依赖**：

```text
- 【错误模型】
- 【设备详情】 ← depends: 【错误模型】
- 【路径安全】 ← depends: 【错误模型】
- 【文件列表】 ← depends: 【路径安全】
- 【文件下载】 ← depends: 【路径安全】
- 【流式命令】 ← depends: 【错误模型】
- 【日志流】 ← depends: 【流式命令】
- 【日志导出】 ← depends: 【错误模型】
- 【降级发现】 ← depends: 【错误模型】
- 【前端数据】 ← depends: 【设备详情】、【文件列表】、【文件下载】
- 【前端日志】 ← depends: 【日志流】、【日志导出】
- 【前端状态】 ← depends: 【前端数据】、【前端日志】
- 【同源打包】 ← depends: 【前端数据】、【前端日志】
- 【联调验收】 ← depends: all
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
- [x] 已按安全、进程生命周期、文件路径和打包风险检查 Risks/Rollout

### Impact

- 后端 API：新增 `FileController`、`LogController`、`ApiExceptionHandler`，保留现有 `DeviceController`。
- 后端服务：新增 Android 设备详情、文件、日志流、日志导出、路径校验和长进程执行能力。
- 后端模型：新增设备详情、远端文件、日志事件、日志会话和统一错误响应模型。
- 前端页面：`App.tsx` 接入真实设备详情、文件列表、下载、SSE 日志和错误状态。
- 测试：新增解析、路径安全、错误映射、日志进程生命周期和前端构建验证。
- 数据库变更：否。
- 外部依赖变更：不新增运行时外部工具；继续使用内置 adb，hdc/idevice 仍通过配置和 PATH。
