# M2 领域工具、平台与存储治理验收报告

## 1. 验收依据

- `spec.md`
- `architecture-assessment.md`
- `development-task-plan.md`
- `contracts/tool-contract.md`
- `security-data-policy.md`

## 2. 任务验收

### M2-01 统一 Tool Gateway

| 检查项 | 结论 |
|--------|------|
| 生产代码 | `ToolContract`、`ToolAdapter`、`ToolRegistry`、`ToolSchemaValidator`、`ToolPolicyPipeline`、`ToolExecutionPipeline`、`ToolGateway` |
| 测试代码 | `ToolGatewayTest` 新增 4 个统一流水线测试 |
| 覆盖的架构目标 | 单一工具入口；中立契约；固定策略顺序；本地风险决策；持久确认；幂等；资源锁；统一脱敏；有序事件 |
| 对应评估章节 | 5.4、7.3、12、14、15.4、15.6、15.7、15.15、16 |
| 验收证据 | 未知 Schema 字段在 Adapter 前拒绝；平台不匹配不执行；动态风险不能低于静态基线；Adapter 敏感输出由 Gateway 脱敏；后端全量 198 个测试通过 |
| 性能与并发 | 复用 M1 有界执行器、持久幂等和多资源原子锁；Gateway 不保留工具大输出副本 |
| 安全 | 模型无法降低风险；确认绑定工具和参数摘要；平台错配和未知字段默认拒绝 |
| 解耦检查 | 契约和 Gateway 不依赖 ADB、Local Shell、Spring AI、MCP SDK 和前端类型 |
| 未解决缺口 | ADB 和 Local Shell 尚未接入新 Gateway，分别属于 M2-02 和 M2-03；持久审计属于 M2-14 |
| 结果 | 通过 |

### M2-02 ADB 中立工具适配

| 检查项 | 结论 |
|--------|------|
| 生产代码 | `AdbToolGatewayAdapter`，并为 `AdbMcpToolService` 增加包内批准执行入口 |
| 测试代码 | `AdbToolGatewayAdapterTest` 新增 3 个定义、风险和结果转换测试 |
| 覆盖的架构目标 | ADB 是底层 Adapter；Agent Runtime 不依赖 ADB 模型；风险语义不变；设备资源锁；中立工具来源和显示名 |
| 对应评估章节 | 5.4、7.3、12、14、15.4、15.6、16 |
| 验收证据 | `android.adb.*` 稳定 ID；Android 平台限定；删除类 shell 命令仍返回 `CONFIRM`；中立结果不含旧确认令牌和 `toolTitle`；全量 201 个测试通过 |
| 兼容性 | 旧 ADB REST 和 Spring AI ToolCallback 继续使用旧服务入口；新 Gateway 入口不消费旧确认令牌 |
| 解耦检查 | Gateway 契约不引入 ADB 类型；ADB 转换全部位于 Adapter 内 |
| 范围边界 | 本任务不删除旧 ADB API，完整切换等待 M4 灰度迁移 |
| 结果 | 通过 |

### M2-03 Local Shell 中立工具适配

| 检查项 | 结论 |
|--------|------|
| 生产代码 | `LocalShellToolGatewayAdapter`，并为 `LocalShellMcpToolService` 增加包内批准执行入口 |
| 测试代码 | `LocalShellToolGatewayAdapterTest` 新增 3 个来源、风险和结果转换测试 |
| 覆盖的架构目标 | Local Shell 是独立底层 Adapter；中立契约不复用 ADB 类型；用户命令授权继续由本地策略决定；本机路径资源锁；独立工具来源和显示名 |
| 对应评估章节 | 5.4、7.3、12、14、15.4、15.6、16 |
| 验收证据 | `desktop.shell.*` 稳定 ID；`source.provider=local-shell`；LOW/MEDIUM/HIGH 授权分别映射允许、确认、阻断；既有 CRITICAL 映射为 HIGH/BLOCK；中立结果不含旧确认令牌和 `toolTitle`；全量 204 个测试通过 |
| 兼容性 | 旧 Local Shell REST 和 Spring AI ToolCallback 继续使用旧服务入口；新 Gateway 入口不消费旧确认令牌 |
| 解耦检查 | Gateway 契约不引入 ADB 或 Local Shell 专属类型；转换和重新校验全部位于 Adapter 内 |
| 范围边界 | 本任务不删除旧 Local Shell API，完整切换等待 M4 灰度迁移 |
| 结果 | 通过 |

### M2-04 工具 Schema 版本和能力元数据

| 检查项 | 结论 |
|--------|------|
| 生产代码 | 在 `ToolContract`、`ToolAdapter`、`ToolRegistry` 和 `ToolGateway` 内扩展版本迁移和能力查询，没有新增生产文件 |
| 测试代码 | 在既有 `ToolGatewayTest` 增加旧版迁移、未知主版本和能力查询 3 个测试 |
| 覆盖的架构目标 | 工具 Schema 版本治理；旧任务可迁移或明确拒绝；可查询的平台、能力、读写、幂等、风险和执行元数据；Router/MCP 复用同一注册表 |
| 对应评估章节 | 5.4、7.3、7.4、15.15、15.20、16 |
| 验收证据 | 严格解析 `major.minor.patch`；未知主版本和未来版本执行前拒绝；旧版同主版本必须显式迁移；迁移后重新计算参数摘要；能力查询可组合过滤；全量 207 个测试通过 |
| 性能与并发 | 注册表构造后只读；查询基于不可变定义集合；迁移仅发生在版本不一致的调用上 |
| 安全 | 禁止静默接受未知版本；确认和幂等绑定迁移后的真实参数；禁用和弃用工具默认不发现 |
| 解耦检查 | 版本与能力模型位于中立契约；未引入 MCP SDK、Spring AI、ADB 或 Local Shell 类型 |
| 结构检查 | 复用现有 4 个生产文件，未增加独立版本工具类；新增方法均低于 80 行，构造参数不超过 8 个 |
| 结果 | 通过 |

### M2-05 标准 MCP Server Adapter

| 检查项 | 结论 |
|--------|------|
| 生产代码 | `StandardMcpToolAdapter`；Spring AI 官方 `spring-ai-starter-mcp-server-webmvc`；标准 MCP Server 配置 |
| 测试代码 | `StandardMcpToolAdapterTest` 新增 2 个发现和调用测试；Local Shell LOW 授权增加 1 个回归测试 |
| 覆盖的架构目标 | 标准 MCP transport；`tools/list`；`tools/call`；统一 Gateway；协议与领域解耦；本地控制面认证 |
| 对应评估章节 | 7.3、7.4、12、14、15.4、15.6、15.15、16 |
| 验收证据 | JAR 在 18081 端口启动并注册 24 个工具；无令牌 SSE 返回 401；授权客户端完成 MCP 2024-11-05 `initialize`、`tools/list`；`tools/call android.adb.version` 返回成功中立结果；全量 210 个测试通过 |
| 安全 | MCP 路径统一位于 `/api/ai/mcp/standard/**`；复用 Host、Origin 和 `X-Ai-DevBridge-Token`；协议上下文不混入工具业务参数；敏感操作仍由 Gateway 确认或阻断 |
| 解耦检查 | MCP Adapter 只依赖中立 `ToolGateway`；ADB、Local Shell、Agent Runtime 和领域服务不依赖 MCP SDK；原自定义 REST 与 Spring AI 回调继续保留用于兼容回退 |
| 启动回归 | 实际 JAR 启动发现并修复 `AgentEventSubscriptionService` 多构造器装配缺陷，避免仅单元测试通过但服务不可启动 |
| 结构检查 | 新增 1 个必要协议 Adapter 生产文件和 1 个测试文件；未为 MCP DTO 再建重复模型；方法和构造参数符合限制 |
| 结果 | 通过 |

### M2-06 Capability Registry 和结构化 Router

| 检查项 | 结论 |
|--------|------|
| 生产代码 | `ToolCapabilityRouter`，模型、Agent、目标和结果类型收敛为类内聚合模型；工具能力继续复用 `ToolRegistry` |
| 测试代码 | `ToolCapabilityRouterTest` 新增跨端最小工具集、模型能力和未知字段 3 个测试 |
| 覆盖的架构目标 | 结构化 Router；平台能力；模型 Tool Calling 能力；候选 Agent 能力；最小工具暴露；风险与访问模式过滤 |
| 对应评估章节 | 5.4、5.5、7.2、7.3、12、15.15、16 |
| 验收证据 | Android `device.read` 与 macOS `desktop.file.read` 可在同一路由结果中独立选择；`android.app.install` 不会被暴露；无 Tool Calling 模型返回澄清；未知结构化字段在路由前拒绝；全量 213 个测试通过 |
| 安全 | Router 只接受 Schema 允许字段；目标必须已连接；工具受平台、访问模式和最大静态风险约束；模型输出不能直接指定工具执行结果或绕过 Gateway |
| 解耦检查 | Router 不依赖前端消息、ADB、Local Shell、MCP SDK 或 Spring AI ToolCallback；模型和 Agent 能力作为中立输入，后续注册表可直接供给 |
| 兼容边界 | 旧 `AiConversationService` 关键词范围仅为现有单轮链路回退保留；新 Agent Runtime 不应使用该分支，完整删除等待 M4 灰度迁移 |
| 结构检查 | 新增 1 个生产文件和 1 个测试文件；相关值对象聚合在 Router 内，未拆分成大量小文件；方法和构造参数符合限制 |
| 结果 | 通过 |

### M2-12 Tool Artifact Store

| 检查项 | 结论 |
|--------|------|
| 生产代码 | `ToolArtifactStore`、`ToolArtifactController` 和 `tool-artifact-root` 配置 |
| 测试代码 | `ToolArtifactStoreTest` 新增 100 MiB 流式分段与范围上限 2 个测试 |
| 覆盖的架构目标 | 大输出不进入堆；分段文件；原始内容摘要；逐段压缩；范围读取；Artifact API；路径隔离 |
| 对应评估章节 | 7.3、7.5、12、15.1、15.5、15.11、15.18、16 |
| 验收证据 | 100 MiB 虚拟流写为 25 个 4 MiB 原始分段；gzip 分段可跨边界读取原始 32 字节；单次范围上限 1 MiB；全量 215 个测试通过 |
| 性能与并发 | 写入缓冲 64 KiB；不调用 `readAllBytes`；范围读取只解压命中分段；最终目录原子发布，读者不会看到半成品 |
| 安全 | Artifact ID 仅接受 UUID；API 不返回真实路径；元数据记录敏感级别、脱敏状态和保留时间；范围长度严格有界 |
| 解耦检查 | Store 不依赖 ADB、Local Shell、前端或 MCP SDK；通过中立 `ArtifactReference` 接入工具结果 |
| 结构检查 | Artifact 身份、存储和策略聚合为 3 个内嵌值对象，避免 13 参数构造器和额外模型文件 |
| 结果 | 通过 |

### M2-07 Device 领域工具

| 检查项 | 结论 |
|--------|------|
| 生产代码 | `DeviceDomainToolAdapter`，复用 `DeviceService`、`AndroidDeviceService`、`IosDeviceService` 和 `ToolArtifactStore` |
| 测试代码 | `DeviceDomainToolAdapterTest` 新增领域定义、iOS 路由和截图 Artifact 3 个测试 |
| 覆盖的架构目标 | Device 领域服务优先；平台中立工具；健康指标；截图 Artifact；连接诊断；设备资源锁 |
| 对应评估章节 | 5.5、5.6、7.2、7.3、12、14、15.6、15.11、16 |
| 验收证据 | 定义包含 `device.list`、`device.detail.read`、`device.health.read`、`device.screenshot.capture`、`device.connection.diagnose`；iOS 详情不调用 Android；截图结果无路径且包含 `image/png` Artifact；全量 218 个测试通过 |
| 安全 | 截图静态 MEDIUM/CONFIRM；序列号与执行上下文绑定；截图临时文件在 Artifact 写入后清理；结果由 Gateway 再统一脱敏 |
| 性能与并发 | 设备读操作使用共享锁；截图二进制通过文件流进入 Artifact，不进入 JSON 输出或模型上下文 |
| 解耦检查 | Device Adapter 不依赖 MCP SDK、Spring AI ToolCallback 或前端类型；底层命令只存在于现有领域服务内部 |
| 结构检查 | 新增 1 个生产 Adapter 和 1 个测试文件，未复制设备 DTO 或拆分额外服务层 |
| 结果 | 通过 |

### M2-08 Log 领域工具

| 检查项 | 结论 |
|--------|------|
| 生产代码 | `LogDomainToolAdapter`；在既有 `LogStreamService` 增加 Agent 会话入口 |
| 测试代码 | `LogDomainToolAdapterTest` 新增生命周期、过滤读取和 Artifact 导出 3 个测试；`LogStreamServiceTest` 增加 Agent 进程停止测试 |
| 覆盖的架构目标 | 日志单一路径；启动、读取、过滤、状态、停止、导出；进程生命周期；Artifact；有界内存；资源锁 |
| 对应评估章节 | 5.6、7.2、7.3、12、15.4、15.5、15.6、15.11、16 |
| 验收证据 | Agent 与 SSE 共用 `LogStreamService`；读取按文件尾部范围限制 64 KiB/2000 行；导出返回 `application/zip` Artifact 且无路径；停止测试确认 `StreamingProcess.stop()` 被调用；全量 222 个测试通过 |
| 安全 | 平台只允许 Android/iOS；会话 ID 必填；完整日志标记 SENSITIVE；结果由 Gateway 脱敏；临时 zip 在 Artifact 持久化后删除 |
| 性能与并发 | 不使用 `readAllLines` 读取完整日志；启动、停止和导出持有独占设备锁；单设备新会话接管旧会话并停止旧进程 |
| 解耦检查 | Log Adapter 不依赖 ADB MCP、Spring AI ToolCallback 或前端 SSE 类型；页面和 AI 只在入口层不同，核心会话相同 |
| 结构检查 | 新增 1 个生产 Adapter 和 1 个测试文件，生命周期能力直接并入既有日志服务，没有复制第二套进程管理器 |
| 结果 | 通过 |

### M2-09 App 领域工具

| 检查项 | 结论 |
|--------|------|
| 生产代码 | `AppDomainToolAdapter`；补全 `AndroidDeviceService` 安装、启动、停止和后置验证；Artifact 增加流式物化入口 |
| 测试代码 | `AppDomainToolAdapterTest` 新增风险、Artifact 安装和权限 3 个测试；`AndroidDeviceServiceTest` 增加安装及启动/停止 2 个测试 |
| 覆盖的架构目标 | App 领域服务；结构化参数；敏感确认；前后置校验；设备锁；幂等；Artifact APK |
| 对应评估章节 | 5.6、7.2、7.3、12、14、15.6、15.7、15.11、16 |
| 验收证据 | 工具覆盖 7 类应用操作；安装 APK 仅来自 Artifact；安装后包存在、卸载后包消失、启动/停止后进程状态均校验；权限输出不含 dataDir；全量 227 个测试通过 |
| 安全 | 包名使用严格正则；APK 必须为普通 `.apk` 文件且不超过大小上限；修改操作 MEDIUM/CONFIRM；设备参数与上下文绑定；临时 APK 最终清理 |
| 性能与并发 | APK 从压缩分段流式物化，不在堆中组装完整文件；修改操作持有设备独占锁；查询可共享并发 |
| 解耦检查 | App Adapter 不依赖 ADB MCP、Spring AI ToolCallback、MCP SDK 或前端 Controller；命令构造保留在既有 Android 领域服务中 |
| 结构检查 | 新增 1 个生产 Adapter 和 1 个测试文件；复用现有 `AppDetail`/`InstalledApp`，没有重复 DTO 层 |
| 结果 | 通过 |

### M2-10 File 领域工具

| 检查项 | 结论 |
|--------|------|
| 生产代码 | `FileDomainToolAdapter`；补全 `AndroidDeviceService` 搜索、预览和 Artifact 上传所需方法 |
| 测试代码 | `FileDomainToolAdapterTest` 新增定义、拉取和上传 3 个测试；`AndroidDeviceServiceTest` 增加搜索注入和上传验证 2 个测试 |
| 覆盖的架构目标 | File 领域服务；路径归一化；搜索；预览；Artifact 传输；删除/重命名/复制；资源锁和确认 |
| 对应评估章节 | 5.6、7.2、7.3、12、14、15.5、15.6、15.7、15.11、16 |
| 验收证据 | 9 个结构化文件工具；搜索拒绝 `../*.db`；上传使用参数化 `adb push` 并读取目标详情；拉取/上传结果不暴露本机路径；全量 232 个测试通过 |
| 安全 | 路径必须为绝对路径且无 `..`/控制字符；搜索无通配符注入；上传拒绝覆盖；删除不递归；写操作 MEDIUM/CONFIRM；临时文件最终清理 |
| 性能与并发 | 预览上限 64 KiB；大文件经 Artifact 流式传输；读操作共享锁，写操作独占锁 |
| 解耦检查 | File Adapter 不依赖 ADB MCP、MCP SDK、Spring AI ToolCallback 或前端 Controller；底层命令集中在既有 Android 文件服务 |
| 结构检查 | 新增 1 个生产 Adapter 和 1 个测试文件，复用现有路径守卫、文件模型和 Artifact Store |
| 结果 | 通过 |

### M2-11 iOS 与 HarmonyOS 平台适配器

| 检查项 | 结论 |
|--------|------|
| 生产代码 | `HarmonyDeviceService`、`MobilePlatformToolAdapter`；Device 领域工具增加 HarmonyOS 路由；Router 增加来源优先级 |
| 测试代码 | `MobilePlatformToolAdapterTest` 新增来源、Harmony 执行、HDC 参数和平台隔离 4 个测试；Router 增加领域优先测试 |
| 覆盖的架构目标 | iOS/libimobiledevice Adapter；HarmonyOS/HDC Adapter；平台能力隔离；领域服务优先；不支持能力明确返回 |
| 对应评估章节 | 5.4、5.5、7.2、7.3、12、14、15.15、16 |
| 验收证据 | Harmony 详情返回 `platform=harmony`；HDC 命令包含 `-t HARMONY-1 shell param get`；iOS 工具来源为 `libimobiledevice`，Harmony 来源为 `hdc`；iOS 查询无 `android.adb.*`；全量 237 个测试通过 |
| 安全 | 平台 Adapter 仅暴露白名单只读能力；设备序列号与执行上下文绑定；未知平台能力不回退到其他平台；HDC 不接受任意 Shell 文本 |
| 性能与并发 | 平台详情使用设备共享锁；连接诊断使用宿主共享锁；Router 每项能力只选择一个最优工具，减少模型工具数量 |
| 解耦检查 | iOS/Harmony 服务不依赖 ADB、MCP SDK、Spring AI ToolCallback 或前端类型；领域 Router 只读取中立来源元数据 |
| 结构检查 | 新增 2 个必要生产文件和 1 个测试文件，iOS/Harmony 平台能力聚合在单一 Adapter，未按工具拆文件 |
| 结果 | 通过 |

### M2-13 统一 Storage Manager

| 检查项 | 结论 |
|--------|------|
| 生产代码 | `StorageManager`、`StorageController`、统一配额配置；`ToolArtifactStore` 接入流式写入许可 |
| 测试代码 | `StorageManagerTest` 新增 80/95/100% 阈值、配额拒绝和受保护清理 3 个测试 |
| 覆盖的架构目标 | 分类占用；统一配额；阈值预警；保留与清理；并发写入控制；活跃任务保护 |
| 对应评估章节 | 7.5、12、15.1、15.5、15.10、15.18、16 |
| 验收证据 | 80/95/100 字节对 100 字节配额分别返回 WARNING/CRITICAL/FULL；已有 90 字节时预留 11 字节被拒绝；过期 Artifact 删除但 Agent Task 保留；全量 240 个测试通过 |
| 性能与并发 | 分类扫描只在快照、清理和写入许可建立时执行；流式每块预留不重复扫描磁盘；并发许可共享 `reservedBytes` 防止共同越限 |
| 安全与可靠性 | Agent 分类不自动清理；保护路径双向父子判断；100% 直接拒绝新写入；Artifact 半成品仍由临时目录和原子发布隔离 |
| 解耦检查 | Storage Manager 不依赖 ADB、Agent Runtime 实现、前端或 MCP SDK；分类根目录来自集中配置 |
| 结构检查 | 分类、快照、清理结果和写入许可聚合在单一 Manager，未拆成大量策略小类 |
| 结果 | 通过 |

### M2-14 结构化持久审计

| 检查项 | 结论 |
|--------|------|
| 生产代码 | `ToolAuditStore`、`ToolAuditController`；`ToolGateway` 接入请求、结果和拒绝审计；Storage Manager 增加 AUDIT 分类 |
| 测试代码 | `ToolAuditStoreTest` 新增重启查询、组合筛选、脱敏落盘、损坏行隔离和保留清理 4 个测试 |
| 覆盖的架构目标 | 工具调用唯一关联；结构化持久审计；完整性保护；查询；脱敏；保留策略；统一磁盘治理 |
| 对应评估章节 | 7.5、12、15.1、15.10、15.11、16 |
| 验收证据 | Store 重建后仍可查询；任务/工具/风险/时间组合过滤生效；文件不含完整参数、密钥和邮箱；损坏 JSONL 行不影响有效记录；91 天文件可清理；全量 244 个测试通过 |
| 安全与可靠性 | 请求审计在执行前失败关闭；只保存参数摘要和最长 2048 字符的脱敏摘要；每行 SHA-256 校验；终态审计失败不诱导上层重放已产生副作用的工具 |
| 性能与并发 | 按日文件顺序追加；单次查询最多 1000 条；逐行读取不加载完整文件；写入串行化并强制刷新，避免进程崩溃丢失关键审计 |
| 解耦检查 | 审计只依赖中立 Tool Contract，不依赖 ADB、Local Shell、MCP SDK、Spring AI 或前端模型；查询 Controller 不进入执行链路 |
| 结构检查 | 审计事件、查询和存储格式聚合在单一 Store，避免为小型 DTO 增加多个文件；方法和构造参数符合限制 |
| 结果 | 通过 |

### M2-15 操作系统密钥环

| 检查项 | 结论 |
|--------|------|
| 生产代码 | `AiSystemKeyring`；`AiConfigCrypto` 接入系统密钥环、旧文件迁移、最小权限回退和启动校验 |
| 测试代码 | `AiConfigCryptoTest` 新增三平台参数、跨实例解密、旧密钥迁移、权限和密钥缺失保护 5 个测试 |
| 覆盖的架构目标 | 系统密钥环优先；安全文件回退；启动权限验证；现有密文兼容；密钥不可观测；故障不破坏配置 |
| 对应评估章节 | 12、15.11、16；`security-data-policy.md` 第 10 节 |
| 验收证据 | macOS/Windows/Linux 固定命令均不包含密钥；系统存储跨 Crypto 实例解密成功；旧密文迁移后继续解密；回退目录/文件权限为 700/600；已有配置密钥缺失时拒绝轮换；全量 249 个测试通过 |
| 安全与可靠性 | 随机 AES 密钥只经标准输入传输；不记录命令、输入和系统错误正文；系统密钥环失败才回退；密钥文件原子发布；Windows ACL 仅保留所有者；密钥 ID 为配置根目录摘要，不暴露真实路径 |
| 兼容性 | `base64(iv):base64(cipherText)` 格式未变化；旧 `ai-config.key` 优先读取并尽力迁移；迁移失败继续使用已加固文件，不影响现有 Provider 配置 |
| 解耦检查 | `AiConfigCrypto` 不包含各平台命令细节；聚合 Keyring 不依赖 Provider、Agent、MCP、ADB、Local Shell 或前端；未按平台增加多个生产类 |
| 结构检查 | M2-15 仅新增 1 个生产文件和 1 个测试文件；平台枚举、结果和执行边界内聚在 Keyring 内；方法和构造参数符合限制 |
| 运行验收 | 后端打包、前端 Vite 构建、Electron `main.js` 语法通过；JAR 在 18082 隔离启动，Spring 上下文成功且注册 54 个 MCP 工具；隔离配置目录实际权限为 700；验收进程已停止 |
| 结果 | 通过 |

## 3. 当前结论

M2-01 至 M2-15 已全部完成。M2 领域工具、平台适配、Artifact、统一存储、持久审计和密钥治理达到当前架构评估目标，可进入下一里程碑。

## 4. M2.5 架构收敛

### M2.5-01 当前 Chat API 纳入 Agent Task 生命周期

| 检查项 | 结论 |
|--------|------|
| 生产改动 | 修改 `AiConversationService`、`AgentTaskApplicationService` 和 `AiChatRequest`；前端接收并回传 taskId；未新增生产类 |
| 业务结果 | 现有 `/api/ai/chat` 和 `/api/ai/chat/stream` 已进入持久 Agent Task 生命周期；确认兼容续跑保持同一任务 |
| 状态与身份 | `CREATED -> PLANNING -> RUNNING -> COMPLETED/FAILED`；Provider 上下文包含任务、会话、轮次、步骤和模型调用标识 |
| 安全 | 恢复任务校验 conversationId；Provider 异常不会留下永久 RUNNING 任务；数据外发决策关联真实 taskId |
| 解耦检查 | 前端无需切换到新的 Agent Task API；Provider 和工具接口未引入前端消息类型；任务状态仍由后端决定 |
| 结构检查 | 没有增加 Facade、Workflow Engine 或新事件层；复用既有应用服务，符合 M2.5 避免继续横向铺架构的目标 |
| 验收证据 | 新增 4 个任务/Chat 主链路测试；全量 253 个后端测试、前端 Vite 构建和隔离 JAR 启动通过；标准 MCP 注册 54 个工具；验收进程已停止 |
| 尚未解决 | 工具仍通过旧 Callback；Chat SSE 仍是兼容事件源；确认后仍由前端构造续跑 Prompt，分别属于 M2.5-02 至 M2.5-04 |
| 结果 | 通过 |

### M2.5-02 当前模型工具调用迁入 Tool Gateway

| 检查项 | 结论 |
|--------|------|
| 生产改动 | 重写既有 `AiToolRegistry` 的内部调用路径；扩展 Chat 确认身份传递和前端正式 Agent Confirmation API；未新增生产文件 |
| 业务结果 | 当前 Chat 的模型工具列表和执行入口来自统一 Tool Gateway；现有工具卡片、过程时间线和 Markdown 回复协议保持兼容 |
| 统一治理 | ADB、Local Shell 和领域工具统一经过 Schema、平台、动态风险、Agent Confirmation、幂等、资源锁、脱敏、审计和 Agent Event |
| 确认兼容 | 确认令牌绑定任务、确认、步骤和工具调用；批准后重试复用原 stepId/toolCallId，参数变化会由 Gateway 重新确认 |
| 安全 | 工具参数使用 JSON 解析；模型不能通过前端令牌绕过服务端 Confirmation Store、工具 ID、参数摘要和风险校验；输出继续使用不可信内容封装 |
| 解耦检查 | Chat 不再依赖 ADB/Local Shell 专属 Callback；旧结果类型只存在于前端兼容映射，不进入 Tool Gateway 核心契约 |
| 结构检查 | 未新建第二个 Spring AI Adapter 文件；协议转换、范围过滤和兼容映射集中在既有 Chat 工具入口，后续 M2.5-05 删除旧未使用 Adapter |
| 验收证据 | 新增 2 个 Chat Gateway 调用和确认身份测试；全量 257 个后端测试、前端 Vite 构建、后端打包和隔离 JAR 启动通过；验收进程已停止 |
| 尚未解决 | Chat SSE 仍直接发送兼容事件；确认批准后仍由前端发起续跑请求，分别属于 M2.5-03 和 M2.5-04 |
| 结果 | 通过 |

### M2.5 二次验收返修

| 检查项 | 结论 |
|--------|------|
| 确认续跑 | 原始中立 `CallRequest` 在风险策略前加密写入 Checkpoint；批准后 Runtime 直接调用 Tool Gateway，再把真实工具证据交给模型总结，不再要求模型重新选择原工具 |
| 重复批准 | 确认增加 `CONSUMED` 状态，同一进程使用任务与确认组合键阻断并发续跑；重启后允许从 Checkpoint 恢复，同一 `toolCallId` 由持久幂等结果阻止副作用重放 |
| 工具结果恢复 | Tool Gateway 将脱敏后的 `CallResult` 加密写入工具 Checkpoint；重复或重启恢复调用直接返回原结果，不再返回 `output=null` 的占位结果 |
| 最终回复 | 流式正文使用单个有界任务缓冲聚合，任务进入 `COMPLETED` 时在同一 Task 快照保存加密最终回复；新增结果查询接口供 SSE 断线恢复 |
| 取消传播 | 前端保存 `taskId` 并调用任务取消 API；Provider 句柄、ADB 和 Local Shell 命令接入现有取消作用域；底层运行注册表处理“取消早于进程注册”的竞态 |
| 事件一致性 | 工具兼容 SSE 订阅键由 `conversationId` 收敛为 `taskId + turnId`；任务查询和事件订阅前补偿缺失终态事件，Task 快照仍是状态与结果真相源 |
| 同步 Chat | 同步接口不再暴露需要确认的工具，避免无法展示确认卡片时进入非法确认状态；流式接口继续承担 Tool Calling 业务 |
| 数据安全 | 任务目标和设备摘要落盘前脱敏；原始工具请求、工具结果和最终回复使用现有 AES-GCM 与系统密钥环能力加密保存 |
| 兼容性 | Agent Task 文件升级到 1.1；旧 1.0 文件先按旧对象算法校验摘要再迁移；CheckPoint 新字段保持可选，旧文件继续读取 |
| 测试策略 | 删除 1 个验证错误续跑 Prompt 的旧测试，改为确定性“恢复原请求 -> 执行工具 -> 模型总结 -> 持久最终回复”闭环测试；未新增大量细碎测试 |
| 验收证据 | 全量 264 个后端测试通过，0 失败、0 错误、0 跳过；后端打包、前端 Vite 构建、Electron 语法检查通过；隔离 JAR 在 18081 启动，HTTP 200，注册 54 个 MCP 工具；验收进程已停止 |
| 文件与架构 | 未新增生产 Java/TypeScript 文件，修改集中在现有 Task、Checkpoint、Confirmation、Tool Gateway、Chat 和前端 API 边界，没有增加 Facade、工作流引擎或新事件总线 |
| 结果 | 通过 |

## 5. M2.5 最终结论

二次验收发现的确定性确认续跑、重复批准、取消传播、工具结果恢复、最终回复恢复、并发工具事件、同步确认和敏感数据持久化问题已经闭环。M2.5 当前满足 `architecture-assessment.md` 中后端控制权、原步骤恢复、幂等副作用、可取消任务、有序事件、结果持久化和最小侵入目标。
