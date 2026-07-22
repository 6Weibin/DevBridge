> 来源：`changes/active/ai-mcp-adb-tools/proposal.md`
> 生成时间：2026-07-06
> 阶段：spec

### Why

DevBridge 当前已经具备 Android 设备识别、设备详情、截图、文件管理、应用管理、实时日志和日志导出能力。现有 AI 日志分析通过单独业务接口完成，后续扩展到安装、卸载、端口转发、网络连接、bugreport、任意 shell 诊断时，会不断增加 AI 专用接口，导致 AI 编排与设备业务强耦合。

本需求要求把项目内置 ADB 的完整顶层能力暴露为 AI 可调用的 MCP 工具接口。AI 对话、日志分析和后续 Agent 编排应通过 MCP 工具获取设备上下文、执行 ADB 操作和返回操作结果；敏感操作不禁止能力，但必须在执行前通过对话向用户确认。

目标效果：AI 可以调用覆盖 ADB `1.0.41 / 37.0.0-14910828` 顶层命令的 MCP 工具，包括任意 `adb shell COMMAND...`；用户无需等待新增单业务接口即可让 AI 执行 ADB 能力范围内的设备管理任务；现有设备、文件、应用、日志页面接口保持兼容。

### What

| 功能 | 验收标准 | 优先级 |
|------|---------|--------|
| ADB MCP 工具目录 | 系统提供 ADB MCP 工具目录；目录包含工具名称、描述、参数 Schema、风险级别、是否需要确认、超时上限、输出限制；目录覆盖项目内置 `adb help` 中的全部顶层命令域 | P0 |
| ADB 全能力覆盖 | 工具目录覆盖 general、networking、file transfer、shell、app installation、debugging、security、scripting、device control、internal debugging、usb、global options、environment context；每个 `adb help` 顶层命令都有独立工具或等价通用工具覆盖 | P0 |
| 任意 adb shell | AI 可通过 MCP 工具提交任意 `adb shell COMMAND...`；系统按设备序列号执行并返回 stdout、stderr、exitCode、timedOut、durationMillis；shell 命令默认不经过产品语义化白名单裁剪 | P0 |
| 敏感操作识别 | 系统识别删除、卸载、清数据、重启、关机、root、unroot、remount、verity、sideload、安装、push 覆盖写、sync 写入、forward/reverse 删除、kill-server、tcpip/usb 切换、写系统路径、写应用私有路径这 18 类敏感操作 | P0 |
| 对话确认机制 | 敏感操作首次调用时不执行真实 ADB 命令；工具返回待确认结果，包含拟执行命令、设备、风险说明、影响范围、确认令牌；用户在 AI 对话中确认后，AI 携带确认令牌再次调用才能执行 | P0 |
| 确认令牌安全 | 确认令牌只能使用一次；令牌绑定用户本次对话、设备序列号、完整 ADB 参数、风险级别和过期时间；命令参数变化、设备变化、令牌过期或重复使用时必须拒绝执行 | P0 |
| 非敏感操作直执 | devices、version、help、get-state、get-serialno、get-devpath、jdwp、mdns check/services、forward --list、reverse --list、logcat 只读快照、bugreport 生成前检查这 12 类非敏感操作可直接执行 | P0 |
| 输出限制与脱敏 | 所有工具输出必须限制 stdout/stderr 行数和字符数；输出中 Authorization、token、api_key、password、邮箱、手机号、设备序列号、常见密钥字段必须脱敏；被截断时返回 truncated=true 和原始输出摘要 | P0 |
| AI 对话接入 MCP | 普通 AI 对话可根据用户意图调用 ADB MCP 工具；工具调用过程在侧边栏展示工具名、状态、确认请求、执行结果摘要；工具输出不得直接污染现有设备页面状态 | P0 |
| 日志分析接入 MCP | AI 日志分析不再依赖单一日志分析业务接口获取日志上下文；AI 通过 MCP 工具读取 logcat 或当前采集日志后生成分析结论；旧日志分析接口可保留为兼容入口，但不得作为唯一实现路径 | P0 |
| 工具执行审计 | 每次 MCP 工具调用记录工具名、设备、ADB 参数摘要、风险级别、确认状态、耗时、exitCode、成功失败、脱敏错误摘要；审计不得记录 API Key、完整 token、完整日志正文 | P0 |
| 并发与取消 | 用户取消 AI 回复或关闭侧边栏时，正在执行的 MCP 工具调用必须终止或标记取消；长时间运行的 logcat、bugreport、sideload、install、pull、push 必须有超时和可取消状态 | P0 |
| 设备状态校验 | 需要设备的 MCP 工具必须校验目标设备存在且状态为 connected；设备 offline、unauthorized、disconnect 时返回稳定错误并提示用户处理方式 | P0 |
| ADB server 管理 | start-server、kill-server、reconnect、connect、disconnect、pair、tcpip、usb 这 8 类影响 ADB 连接状态的工具必须有风险级别；kill-server、tcpip、usb 需要确认 | P0 |
| 现有功能兼容 | 新 MCP 工具不得改变现有 `/api/devices/**`、`/api/devices/{platform}/{serial}/logs/stream`、文件、应用、截图接口响应结构；移除 AI/MCP 模块后现有页面仍可构建运行 | P0 |
| 前端确认交互 | AI 侧边栏展示敏感操作确认卡片；卡片包含命令、设备、风险、影响、有效期、确认和取消动作；用户取消后令牌失效，AI 可继续普通对话 | P0 |
| 工具结果结构 | 每个工具返回统一结构：status、stdout、stderr、exitCode、timedOut、durationMillis、truncated、riskLevel、confirmationRequired、confirmationToken、message；失败时包含稳定错误码 | P0 |
| 工具文档导出 | 系统可导出或展示 ADB MCP 工具清单，便于后续 Agent、RAG 和模型观测复用；工具清单与内置 ADB version 绑定 | P1 |
| 标准 MCP 兼容 | MCP 工具名称、描述、参数 Schema、返回字段与标准 MCP Tool schema 对齐；若第一阶段通过 Spring AI Tool Calling 调用，仍需保留切换标准 MCP Server 的工具契约 | P1 |

**不做的事：**
- 不实现 iOS/HarmonyOS 全量工具集。
- 不让前端直接执行 adb 或直接拼接本机命令。
- 不把 MCP 工具执行逻辑写入 `App.tsx`、文件页、应用页、日志页主体逻辑。
- 不取消现有设备、文件、应用、日志 REST 接口。
- 不绕过敏感操作确认机制执行高风险 ADB 命令。

### How

#### 1. 正常流程

1. 用户打开 DevBridge 并配置 AI。
2. 用户在 AI 侧边栏输入“查看当前设备最近崩溃日志”“执行 adb shell dumpsys battery”或“给设备安装 APK”。
3. AI 根据工具目录选择对应 ADB MCP 工具。
4. 系统校验设备、参数、风险级别、超时和输出限制。
5. 非敏感操作直接执行，敏感操作返回待确认结果。
6. 侧边栏展示工具调用过程和结果摘要。
7. AI 基于工具结果继续分析或回复用户。
8. 主界面当前设备、页签、日志过滤、文件选择、应用列表不被重置。

#### 2. 关键场景

#### 场景：AI 调用非敏感 shell 命令
- **Given** 当前选中 Android 设备状态为 connected
- **When** 用户要求 AI 执行 `adb shell getprop ro.build.version.release`
- **Then** AI 调用 `adb_shell` MCP 工具
- **Then** 系统执行命令并返回 stdout、stderr、exitCode、timedOut、durationMillis
- **Then** 侧边栏展示命令结果摘要
- **Then** 不弹出确认卡片

#### 场景：AI 调用删除文件 shell 命令
- **Given** 当前选中 Android 设备状态为 connected
- **When** 用户要求 AI 执行 `adb shell rm -rf /sdcard/test`
- **Then** 系统识别删除操作为敏感操作
- **Then** 系统不执行真实 ADB 命令
- **Then** 侧边栏展示确认卡片，包含完整命令、目标设备、风险说明、确认和取消动作
- **Then** 用户确认前工具结果为 `confirmationRequired=true`

#### 场景：用户确认敏感操作
- **Given** AI 侧边栏存在未过期的删除操作确认卡片
- **When** 用户点击确认
- **Then** 前端提交确认令牌
- **Then** 系统校验令牌绑定的设备、参数、会话和有效期
- **Then** 系统执行令牌绑定的 ADB 命令
- **Then** 令牌立即失效

#### 场景：AI 修改确认后的命令
- **Given** 用户已确认 `adb shell rm /sdcard/a.log`
- **When** AI 携带同一确认令牌请求执行 `adb shell rm /sdcard/b.log`
- **Then** 系统拒绝执行
- **Then** 侧边栏展示“确认令牌与命令不匹配”

#### 场景：日志分析通过 MCP 工具取数
- **Given** AI 已配置，当前设备为 connected
- **When** 用户要求“分析当前设备日志”
- **Then** AI 调用日志相关 MCP 工具读取 logcat 或当前采集日志
- **Then** 工具返回经过脱敏和限制的日志上下文
- **Then** AI 基于工具结果返回问题摘要、关键证据、原因判断、建议操作和置信度
- **Then** 不依赖单一日志分析业务接口作为唯一取数路径

#### 场景：设备断开
- **Given** AI 准备调用需要设备的 MCP 工具
- **When** 当前设备状态为 offline、unauthorized 或 disconnected
- **Then** 工具不执行 ADB 命令
- **Then** 返回稳定错误码和可读处理建议
- **Then** 用户可继续普通 AI 对话

#### 场景：工具输出超限
- **Given** AI 调用 `adb logcat -d`
- **When** stdout 超过工具输出限制
- **Then** 系统截断输出
- **Then** 返回 `truncated=true`
- **Then** 返回内容不包含未脱敏 token、Authorization、邮箱、手机号和完整设备序列号

#### 3. ADB 顶层能力覆盖清单

| ADB 命令域 | 必须覆盖的顶层命令或能力 |
|------------|--------------------------|
| global options | `-a`、`-d`、`-e`、`-s SERIAL`、`-t ID`、`-H`、`-P`、`-L`、`--one-device`、`--exit-on-write-error` |
| general | `devices [-l]`、`help`、`version` |
| networking | `connect`、`disconnect`、`pair`、`forward --list`、`forward`、`forward --remove`、`forward --remove-all`、`reverse --list`、`reverse`、`reverse --remove`、`reverse --remove-all`、`mdns check`、`mdns services` |
| file transfer | `push`、`pull`、`sync` 及其 `--sync`、`-z`、`-Z`、`-a`、`-l` 参数 |
| shell | `shell [-e ESCAPE] [-n] [-Tt] [-x] [COMMAND...]`、`emu COMMAND` |
| app installation | `install`、`install-multiple`、`install-multi-package`、`uninstall` 及 ADB help 中列出的安装参数 |
| debugging | `bugreport`、`jdwp`、`logcat` |
| security | `disable-verity`、`enable-verity`、`keygen` |
| scripting | `wait-for[-TRANSPORT]-STATE`、`get-state`、`get-serialno`、`get-devpath` |
| device control | `remount`、`reboot`、`sideload`、`root`、`unroot`、`usb`、`tcpip` |
| internal debugging | `start-server`、`kill-server`、`reconnect`、`reconnect device`、`reconnect offline` |
| usb | `attach`、`detach` |
| environment context | `ADB_TRACE`、`ADB_VENDOR_KEYS`、`ANDROID_SERIAL`、`ANDROID_LOG_TAGS`、`ADB_LOCAL_TRANSPORT_MAX_PORT`、`ADB_MDNS_AUTO_CONNECT` 的受控执行上下文 |

#### 4. 敏感操作范围

| 类型 | 命中条件 | 处理方式 |
|------|----------|----------|
| 删除 | `rm`、`unlink`、`rmdir`、`find ... -delete`、`dd of=` 覆盖写 | 返回确认卡片，用户确认后执行 |
| 卸载和清数据 | `uninstall`、`pm uninstall`、`cmd package uninstall`、`pm clear` | 返回确认卡片，用户确认后执行 |
| 安装和覆盖写 | `install`、`install-multiple`、`install-multi-package`、`push` 写入设备、`sync` 写入设备 | 返回确认卡片，用户确认后执行 |
| 重启和模式切换 | `reboot`、`sideload`、`tcpip`、`usb` | 返回确认卡片，用户确认后执行 |
| root 和系统分区 | `root`、`unroot`、`remount`、`disable-verity`、`enable-verity`、shell 中的 `su`、`mount`、`setenforce` | 返回确认卡片，用户确认后执行 |
| ADB 服务影响 | `kill-server`、`reconnect`、`disconnect` 全部设备 | 返回确认卡片，用户确认后执行 |
| 端口规则删除 | `forward --remove`、`forward --remove-all`、`reverse --remove`、`reverse --remove-all` | 返回确认卡片，用户确认后执行 |
| 私有或系统路径写操作 | 对 `/system`、`/vendor`、`/product`、`/data/data`、`/data/user`、`/sdcard/Android/data` 执行写、删、改权限 | 返回确认卡片，用户确认后执行 |

#### 5. 失败处理

| 失败场景 | 处理方式 |
|---------|---------|
| adb 工具不存在 | 返回 `ADB_TOOL_NOT_FOUND`，提示检查工具包，不调用 AI Provider 重试 |
| 设备不存在 | 返回 `ADB_DEVICE_NOT_FOUND`，包含当前可用设备摘要 |
| 设备 unauthorized | 返回 `ADB_DEVICE_UNAUTHORIZED`，提示用户在设备侧授权 USB 调试 |
| 设备 offline | 返回 `ADB_DEVICE_OFFLINE`，提示重插设备或执行 reconnect |
| 命令参数为空 | 返回 `ADB_COMMAND_EMPTY`，不执行 ADB |
| 顶层命令不在覆盖清单 | 返回 `ADB_COMMAND_UNSUPPORTED`，并列出支持命令域 |
| shell 命令为空 | 返回 `ADB_SHELL_COMMAND_EMPTY` |
| 敏感命令未确认 | 返回 `confirmationRequired=true`，不执行真实 ADB 命令 |
| 确认令牌过期 | 返回 `ADB_CONFIRMATION_EXPIRED`，要求重新确认 |
| 确认令牌不匹配 | 返回 `ADB_CONFIRMATION_MISMATCH`，拒绝执行 |
| 确认令牌重复使用 | 返回 `ADB_CONFIRMATION_USED`，拒绝执行 |
| 命令超时 | 终止进程，返回 `timedOut=true`、exitCode=124 和已读取输出摘要 |
| 输出超限 | 截断输出，返回 `truncated=true` 和截断后的脱敏内容 |
| ADB 返回非零退出码 | 返回 exitCode、stderr 摘要和稳定错误码 `ADB_COMMAND_FAILED` |
| 用户取消 AI 回复 | 取消当前工具调用；已启动外部进程必须终止或标记为 canceled |
| 后端不可用 | 前端展示后端未连接提示，不展示空白工具结果 |

#### 6. 边界与约束

| 边界 | 说明 |
|------|------|
| 完整性基准 | 覆盖项目内置 `adb help` 输出的顶层命令，版本为 `1.0.41 / 37.0.0-14910828` |
| 任意 shell | `adb shell COMMAND...` 不做产品语义白名单裁剪，但执行前必须做敏感操作识别 |
| 参数执行 | 后端执行 ADB 时必须以参数数组形式执行；不得通过宿主机 shell 拼接整条命令 |
| 确认粒度 | 确认令牌绑定完整 ADB 参数数组，不能只绑定命令类型 |
| 输出安全 | 工具输出脱敏后才能进入 AI Prompt、前端展示和审计日志 |
| 长任务 | bugreport、logcat、install、push、pull、sideload 需要超时、进度或可取消状态 |
| AI 解耦 | AI 只依赖 MCP 工具契约，不直接调用现有设备页面状态和页面函数 |
| 主界面隔离 | MCP 工具调用不得清空日志页、不得切换页签、不得改变文件选择、不得关闭用户手动日志采集 |
| 标准 MCP | 工具 Schema 和返回结构与标准 MCP Tool schema 对齐；具体传输协议可在设计阶段落地 |
| 可移除性 | 删除 AI/MCP 模块后，现有设备管理、文件管理、应用管理、实时日志和日志导出仍可构建运行 |

#### 7. 任务拆分

| 任务名称 | 详细描述 | 计划工作量(人天) |
|----------|---------|--------------|
| 【工具目录】(后端) 定义 ADB MCP 工具目录 | 1. 建立 ADB version/help 基准<br>2. 定义覆盖顶层命令域的工具清单<br>3. 定义工具参数、风险级别、确认要求和输出限制<br>4. 覆盖 tools catalog 单测 | 1.5 |
| 【执行模型】(后端) 实现统一 ADB MCP 执行请求与结果模型 | 1. 定义统一请求、结果、错误码和确认状态<br>2. 支持 stdout、stderr、exitCode、timedOut、durationMillis、truncated<br>3. 支持设备和全局 ADB 选项<br>4. 覆盖模型序列化测试 | 1 |
| 【命令覆盖】(后端) 实现 ADB 顶层命令执行能力 | 1. 覆盖 general、networking、file transfer、shell、app installation、debugging、security、scripting、device control、internal debugging、usb<br>2. 支持任意 shell command<br>3. 保证未知顶层命令返回稳定错误<br>4. 覆盖命令组装测试 | 2 |
| 【敏感识别】(后端) 实现敏感命令识别 | 1. 覆盖删除、卸载、清数据、安装、push/sync 写入、重启、root、remount、verity、sideload、ADB server 影响、端口规则删除、敏感路径写操作<br>2. 返回风险说明和影响范围<br>3. 覆盖 shell 命令和顶层命令识别测试 | 2 |
| 【确认机制】(全栈) 实现对话确认令牌 | 1. 生成绑定会话、设备、完整参数、风险和有效期的确认令牌<br>2. 前端展示确认卡片<br>3. 支持确认、取消、过期、重复使用和参数不匹配<br>4. 覆盖确认流程测试 | 2 |
| 【输出安全】(后端) 实现输出限制、脱敏和审计 | 1. 限制 stdout/stderr 行数和字符数<br>2. 脱敏 token、Authorization、密钥、邮箱、手机号、设备序列号<br>3. 记录工具调用审计摘要<br>4. 覆盖脱敏、截断和审计测试 | 1.5 |
| 【AI接入】(后端) 将 AI 对话接入 MCP 工具调用 | 1. 普通对话可调用 ADB MCP 工具<br>2. 日志分析通过 MCP 工具获取上下文<br>3. 工具调用错误返回可读回复<br>4. 保留旧接口兼容入口 | 2 |
| 【前端交互】(前端) 展示工具调用过程和确认卡片 | 1. 侧边栏展示工具名、状态、参数摘要、结果摘要<br>2. 展示敏感操作确认卡片<br>3. 支持取消当前工具调用<br>4. 不影响主界面状态 | 1.5 |
| 【兼容回归】(全栈) 完成联调与回归验证 | 1. 验证全部 ADB 顶层命令域有覆盖<br>2. 验证任意 shell 和敏感确认<br>3. 验证日志分析不依赖单一业务接口<br>4. 验证现有设备、文件、应用、日志功能无回归 | 2 |
| **合计** | | **15.5** |

### Verify

- [x] 所有功能需求都有对应任务
- [x] 所有失败路径都覆盖了
- [x] 没有模糊描述
- [x] 没有未确认的假设
- [x] 任务总量与需求规模匹配

### Impact

- `DevBridge-Server/src/main/java/com/devbridge/server/ai/`：新增或扩展 MCP/Tool 工具目录、调用、确认、安全、观测能力。
- `DevBridge-Server/src/main/java/com/devbridge/server/command/`：复用参数数组执行与超时能力，支撑 ADB MCP 调用。
- `DevBridge-Server/src/main/java/com/devbridge/server/service/AndroidDeviceService.java`：现有语义化设备能力可被 MCP 工具复用，不改变已有接口契约。
- `DevBridge-Server/src/main/java/com/devbridge/server/api/AiController.java`：AI 对话和工具确认入口需要支持工具调用过程。
- `DevBridge-Front/src/app/ai/`：AI 侧边栏新增工具调用状态和敏感确认交互。
- `DevBridge-Front/src/app/App.tsx`：只允许保持 AI Shell 挂载和只读上下文传递，不承载 MCP 工具逻辑。
