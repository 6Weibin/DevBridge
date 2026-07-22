# 技术预研：手机端网络请求抓包

## 1. 预研结论

- 结论：有条件可行
- 推荐方案：内置本机 HTTP(S) MITM 代理 + 手机代理配置引导 + 请求列表/详情展示
- 是否需要 Demo：是
- 下一步：先做 Demo 验证代理进程、证书安装引导、请求事件流和前端展示，再进入正式 spec/design

## 2. 预研目标

### 2.1 新需求目标

在 DevBridge 当前手机设备管理工具中新增手机端网络请求抓包能力，让用户可以查看通过手机代理到本机的 HTTP/HTTPS 请求、响应、Headers、Body、耗时、状态码，并可导出抓包记录。

### 2.2 关键决策问题

| 问题 | 结论 | 证据 |
|------|------|------|
| 当前工具是否已有抓包能力 | 没有 | `DevBridge-Server/pom.xml` 仅有 Spring Web/Validation/Test；`ToolCatalog.java` 仅声明 adb、hdc、idevice* |
| 是否能复用现有架构 | 可以 | `LogStreamService.java` 已有长进程管理、SSE 推送、断开清理模式；适合代理事件流 |
| 是否能对 HTTPS 解密 | 有条件可行 | 需要用户在手机端安装并信任调试 CA；Android 7+ App 默认不信任用户 CA，证书固定 App 不能透明解密 |
| 是否能通过 USB 自动完成全部设置 | Android 可部分自动化，iOS 不建议承诺 | Android 可用 adb 辅助设置 Wi-Fi 代理；iOS 通常需要用户手动配置代理和证书信任 |

### 2.3 范围与非范围

- 范围：Android/iOS 手机经 Wi-Fi 或 USB 网络可达本机代理后的 HTTP/HTTPS 请求采集、展示、搜索、导出、会话管理。
- 非范围：绕过 App 证书固定、抓取未经过代理的蜂窝数据、root/越狱级全流量解密、规避第三方 App 安全策略。

## 3. 现状扫描

### 3.1 已读取材料

| 类型 | 路径/对象 | 用途 |
|------|-----------|------|
| 文档 | `跨平台 USB 手机设备管理与日志采集工具需求（Java + H5 实现）.md` | 确认产品形态：Spring Boot + H5/Electron，本地单机工具 |
| 后端配置 | `DevBridge-Server/pom.xml` | 确认当前依赖和 Java 17/Spring Boot 3.3.7 |
| 后端代码 | `DevBridge-Server/src/main/java/com/devbridge/server/service/ToolCatalog.java` | 确认当前外部工具白名单 |
| 后端代码 | `DevBridge-Server/src/main/java/com/devbridge/server/service/LogStreamService.java` | 确认可复用的长进程/SSE 生命周期模式 |
| 前端配置 | `DevBridge-Front/package.json` | 确认 React/Vite 前端，可新增抓包页面 |

### 3.2 当前技术栈

| 层次 | 技术/版本 | 证据 | 备注 |
|------|-----------|------|------|
| 后端 | Java 17 + Spring Boot 3.3.7 | `DevBridge-Server/pom.xml` | 适合新增 REST/SSE API |
| 前端 | React + Vite | `DevBridge-Front/package.json` | 可新增网络会话列表和详情面板 |
| 桌面壳 | Electron | `DevBridge-Electron/src/main.js` | 可随应用分发代理工具和后端 jar |
| 外部工具 | adb、hdc、libimobiledevice | `ToolCatalog.java` | 当前未集成代理/抓包工具 |

### 3.3 现有能力

| 能力 | 支持程度 | 证据 | 限制 |
|------|----------|------|------|
| 设备发现 | 已支持 | `DeviceService.java`、`AndroidDeviceService.java`、`IosDeviceService.java` | 依赖本机工具和设备授权 |
| 实时日志流 | 已支持 | `LogStreamService.java` | 仅日志，不解析网络请求 |
| 长进程管理 | 已支持 | `StreamingCommandRunner.java` | 可复用到 mitmproxy/代理进程 |
| 工具探测 | 已支持 | `ToolStatusService.java`、`ToolCatalog.java` | 需要新增代理工具定义 |
| 抓包代理 | 不支持 | 全局搜索未发现 proxy/pcap/tcpdump/mitm 相关实现 | 需要新增模块 |

## 4. 能力匹配与差距

| 需求能力 | 现有支持 | 差距 | 复用/改造建议 |
|----------|----------|------|---------------|
| 启停抓包会话 | 部分支持 | 缺少代理进程和会话模型 | 参考 `LogStreamService` 建立 `NetworkCaptureService` |
| 请求实时推送 | 部分支持 | 缺少抓包事件格式和 SSE 接口 | 复用 SSE，事件包含 request、response、error、session-state |
| 手机代理配置 | 部分支持 | Android/iOS 配置方式未实现 | Android 通过 adb 辅助设置；iOS 提供手动配置引导 |
| HTTPS 解密 | 不支持 | 缺少 CA 生成、安装、信任状态提示 | 由代理工具生成 CA；前端展示证书安装步骤和风险提示 |
| 请求详情展示 | 不支持 | 前端无网络面板 | 新增请求表格、Headers/Body/Timing 详情、过滤和导出 |
| 安全合规 | 部分支持 | 需要敏感数据提示、脱敏、导出控制 | 默认本地存储、显式确认、导出前提示敏感数据 |

## 5. 技术路线候选

### 5.1 方案 A：内置 MITM 代理

- 路线：集成 mitmproxy/mitmdump 作为外部工具；后端启动本机代理端口，解析请求事件并通过 SSE 推给前端；手机配置代理到电脑 IP:端口。
- 核心要点：代理进程生命周期、CA 证书安装引导、请求/响应 Body 截断、二进制内容识别、会话导出、端口占用处理。
- 改动范围：后端新增 network capture controller/service/model；工具目录新增 mitmproxy 或配置外部路径；前端新增抓包工作台。
- 优点：开发成本低，协议处理成熟，HTTPS 能力相对完整，适合当前本地工具形态。
- 缺点：需要分发或安装第三方工具；HTTPS 受系统信任和 App 证书固定限制；高流量场景要做限流和截断。
- 适用条件：目标是调试自有 App、测试环境 App、浏览器/普通 HTTP(S) 流量。

### 5.2 方案 B：外部抓包工具集成

- 路线：不内置代理，只检测 Charles/Proxyman/Fiddler/mitmproxy 等外部工具，DevBridge 负责设备代理配置、证书引导和打开外部工具。
- 核心要点：工具探测、配置向导、跨平台差异、用户已安装工具的路径管理。
- 改动范围：后端工具探测和设备代理配置；前端引导页面。
- 优点：开发量小，产品风险低，用户可使用成熟抓包 UI。
- 缺点：DevBridge 自身不具备请求列表/导出能力，体验割裂，依赖用户安装商业或第三方工具。
- 适用条件：短期希望快速打通工作流，不要求在 DevBridge 内查看请求详情。

### 5.3 方案 C：设备侧 PCAP/系统抓包

- 路线：在设备侧运行 tcpdump/系统抓包能力，将 pcap 拉回本机，用 Wireshark 或后端解析。
- 核心要点：root/调试权限、VPN 抓包、pcap 解析、TLS 只能看到加密流量元数据。
- 改动范围：大；需要设备侧组件或 root/企业管控能力。
- 优点：可覆盖非代理流量，适合底层网络问题定位。
- 缺点：普通手机不可控；HTTPS 默认无法解密；实现和权限成本高。
- 适用条件：仅适合高级诊断、专用测试机、企业受控设备。

## 6. 推荐方案

- 推荐：方案 A，内置 MITM 代理。
- 推荐理由：与当前本地单机工具、外部命令执行、SSE 实时流、Electron 分发模式匹配，能在 DevBridge 内形成完整体验。
- 不选其他方案的理由：方案 B 快但不是产品内抓包；方案 C 权限和解密限制过高，不适合作为默认能力。
- 前置条件：用户确认仅用于合法调试自有设备/自有 App；明确 HTTPS 解密限制；接受新增第三方代理工具依赖或要求用户本机安装。

## 7. 风险矩阵

| 风险 | 等级 | 触发条件 | 影响范围 | 发现方式 | 缓解措施 | 回退方案 | 是否需 Demo |
|------|------|----------|----------|----------|----------|----------|-------------|
| HTTPS 无法解密 | 高 | 未安装/未信任 CA、Android App 不信任用户 CA、证书固定 | 用户看不到明文请求 | Demo 真机验证、前端状态提示 | 明确限制；提供自有 App debug network security config 指南 | 降级只展示 CONNECT/域名/状态 | 是 |
| 代理端口不可达 | 中 | 手机和电脑不在同一网络、系统防火墙、监听地址错误 | 无法抓到请求 | 连通性检测接口、手机访问测试 URL | 支持显示本机可用 IP；提示防火墙；端口可配置 | 用户手动配置外部代理工具 | 是 |
| 高流量导致内存增长 | 高 | 大文件下载、图片/视频流、长时间抓包 | 后端/前端卡顿或崩溃 | 压测、内存监控、请求数量统计 | Body 截断、二进制跳过、环形缓冲、会话上限 | 停止抓包并清空会话 | 是 |
| 敏感数据泄露 | 高 | 抓包包含 token、Cookie、个人信息并导出 | 隐私合规问题 | 导出前检查、日志审查 | 默认仅本地；导出确认；可选脱敏；不上传外网 | 删除会话数据和导出文件 | 否 |
| 第三方工具分发合规 | 中 | 随 Electron 打包 mitmproxy 二进制 | 分发许可和体积问题 | 依赖许可证审查、打包验证 | 优先支持外部路径；确认许可证后再内置 | 退回方案 B | 否 |
| App 兼容问题 | 中 | App 不走系统代理、使用自定义 TLS、HTTP/3/QUIC | 抓包不完整 | 真机测试矩阵 | 明确支持范围；必要时提示关闭 QUIC 或使用测试构建 | 只提供可见流量 | 是 |

## 8. 安全与质量评估

- 性能：必须限制单会话最大请求数、最大 Body 展示长度、最大导出文件大小；大响应默认只保留元数据。
- 可扩展性：按 `NetworkCaptureSession` 抽象会话，先只支持 MITM 代理，不预留复杂多协议框架。
- 可维护性：沿用现有 controller/service/model 分层和命令参数数组执行，避免 shell 字符串拼接。
- 高可用：代理进程异常退出时推送 session-state，端口冲突时返回明确错误，SSE 断开后按会话策略停止或保留短时缓冲。
- 数据一致性：抓包事件按会话 ID 和递增序号管理；导出时固定快照，避免边写边导出不一致。
- 安全：代理只监听本机或显式选择的局域网地址；默认禁止公网监听；导出前提示敏感数据；日志避免打印 Headers/Body 明文。
- 可观测性：记录会话开始/停止、端口、请求数、丢弃数、截断数、工具异常，不记录敏感内容。

## 9. 成本评估

| 工作项 | 工作量 | 依赖 | 说明 |
|--------|--------|------|------|
| MITM 代理 Demo | 1.5-2 人天 | mitmproxy/mitmdump、Android/iOS 真机 | 验证启动、证书、请求事件、SSE |
| 后端生产接口 | 2-3 人天 | Demo 结论 | 会话、事件流、导出、错误处理、单测 |
| 前端抓包工作台 | 2-3 人天 | 后端接口 | 请求列表、过滤、详情、证书向导、导出 |
| 打包与工具探测 | 1-2 人天 | Electron、目标系统 | 内置或外部工具路径、端口占用、权限提示 |
| 真机兼容测试 | 1-2 人天 | Android/iOS 设备 | HTTP、HTTPS、证书失败、App 证书固定场景 |

## 10. Demo/PoC 建议

- 是否需要 Demo：是。
- 验证目标：mitmdump 能否由 Java 后端启动并输出结构化请求事件；手机配置代理后是否能抓到 HTTP/HTTPS；SSE 能否稳定推送到前端。
- Demo 范围：独立 `changes/active/network-capture-research/demo/` 或后端测试型 service；不接入生产页面，不做完整导出。
- 验收标准：
  - 后端可启动/停止代理会话。
  - 手机访问测试 HTTP 页面时前端/接口能看到 URL、method、status、duration。
  - 安装并信任 CA 后，自有测试 HTTPS 请求能看到明文 Headers。
  - 未信任 CA 或证书固定时返回明确失败原因或限制说明。
- 不做内容：不绕过证书固定，不支持 root/越狱，不做全协议 pcap 解析。

## 11. 后续建议

1. 先确认产品定位：是“DevBridge 内置抓包工作台”，还是“辅助配置外部抓包工具”。
2. 若选择内置抓包，先做 mitmproxy Demo；Demo 通过后再补 spec/design。
3. 正式实现时把 HTTPS 限制、安全提示、敏感数据处理作为 P0 验收项，不要只做请求列表。
