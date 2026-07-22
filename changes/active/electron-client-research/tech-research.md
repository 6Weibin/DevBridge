# 技术预研：DevBridge Electron PC 客户端化

## 1. 预研结论

- 结论：有条件可行
- 推荐方案：新增独立 `DevBridge-Electron` 工程作为桌面外壳，编排 `DevBridge-Front`、`DevBridge-Server` 构建产物，启动本地 Spring Boot fat jar，再加载 `http://127.0.0.1:{port}/`
- 是否需要 Demo：是
- 下一步：先修复/验证后端静态资源访问，再做 Electron 最小 PoC

## 2. 预研目标

### 2.1 新需求目标

在保留 `DevBridge-Front` 和 `DevBridge-Server` 两个工程边界的前提下，新增独立 `DevBridge-Electron` 工程作为桌面客户端工程。最终通过构建编排把 3 个工程的产物组装为一个 PC 客户端，客户端启动后自动启动后端服务，并自动打开设备管理页面。

### 2.2 关键决策问题

| 问题 | 结论 | 证据 |
|------|------|------|
| 前端是否可以继续复用 `DevBridge-Front` | 可以 | `DevBridge-Front/package.json` 使用 Vite 构建；`npm run build` 通过 |
| 后端是否可以继续复用 `DevBridge-Server` | 可以 | `DevBridge-Server/pom.xml` 使用 Spring Boot fat jar；`mvn test` 通过 24 个测试；`mvn -DskipTests package` 通过 |
| 是否已有单服务承载页面和 API 的基础 | 已有，但需修复运行时问题 | `DevBridge-Server/pom.xml` 将 `../DevBridge-Front/dist` 复制到 `static`；jar 内存在 `BOOT-INF/classes/static/index.html` |
| Electron 是否应作为独立工程存在 | 应该 | 前端、后端已有独立工程边界；客户端打包、JRE、安装包、进程编排不应混入业务前后端 |
| Electron 是否能自动启动服务并加载页面 | 可行 | 后端监听 `127.0.0.1:8080`；前端 `API_BASE` 固定为 `http://127.0.0.1:8080` |
| 当前是否可直接产品化 | 不建议直接产品化 | 本机 8080 已被占用；当前运行服务访问 `/` 和 `/index.html` 返回 500，日志含 `WarResourceSet` 类缺失 |

### 2.3 范围与非范围

- 范围：新增 `DevBridge-Electron` 独立工程、前端构建产物、后端 jar 启动、端口管理、工具目录打包、客户端生命周期、安全边界。
- 非范围：本次不实现完整客户端、不改业务功能、不处理安装包签名和自动更新细节。

## 3. 现状扫描

### 3.1 已读取材料

| 类型 | 路径/对象 | 用途 |
|------|-----------|------|
| 配置 | `DevBridge-Front/package.json` | 确认前端构建脚本和依赖 |
| 配置 | `DevBridge-Front/vite.config.ts` | 确认 Vite 静态构建方式 |
| 代码 | `DevBridge-Front/src/app/App.tsx` | 确认 API 地址、SSE 和下载调用 |
| 配置 | `DevBridge-Server/pom.xml` | 确认 Spring Boot 版本、fat jar 和静态资源合入 |
| 配置 | `DevBridge-Server/src/main/resources/application.yml` | 确认监听地址、端口和工具/临时目录 |
| 代码 | `DevBridge-Server/src/main/java/com/devbridge/server/service/ExecutableLocator.java` | 确认内置工具目录定位规则 |
| 脚本 | `DevBridge-Server/scripts/start-server.cmd` | 确认 Windows jar 启动方式 |
| 文档 | `DevBridge-Server/README.md` | 确认现有本地服务运行约定 |

### 3.2 当前技术栈

| 层次 | 技术/版本 | 证据 | 备注 |
|------|-----------|------|------|
| 前端 | React + Vite 6.3.5 | `DevBridge-Front/package.json` | 构建产物可被后端静态托管 |
| 后端 | Spring Boot 3.3.7 + Java 17 | `DevBridge-Server/pom.xml` | 可打包为可执行 jar |
| 设备工具 | ADB 内置，iOS/Harmony 外部查找 | `DevBridge-Server/tools/TOOLS.md`、`ExecutableLocator.java` | Windows/macOS 工具打包策略不同 |
| 运行端口 | `127.0.0.1:8080` | `application.yml`、`App.tsx` | 当前硬编码，需要客户端化增强 |

### 3.3 现有能力

| 能力 | 支持程度 | 证据 | 限制 |
|------|----------|------|------|
| 前端生产构建 | 已支持 | `npm run build` 成功 | API 地址硬编码 8080 |
| 后端测试和打包 | 已支持 | `mvn test` 成功；`mvn -DskipTests package` 成功 | jar 静态资源访问运行时异常需修复 |
| 后端本机监听 | 已支持 | `server.address=127.0.0.1` | 端口占用时启动失败 |
| REST/SSE/下载接口 | 已支持 | `DeviceController`、`FileController`、`LogController` | 若改为 `file://` 加载前端会扩大 CORS 复杂度 |
| 外部工具定位 | 部分支持 | `ExecutableLocator` 支持 `tools/{os-arch}`、配置路径和 PATH | Electron 打包后工作目录变化，需要传入明确工具根目录 |

## 4. 能力匹配与差距

| 需求能力 | 现有支持 | 差距 | 复用/改造建议 |
|----------|----------|------|---------------|
| 使用 `DevBridge-Front` | Vite 构建已可用 | API 地址不可动态切换 | 短期继续加载后端 HTTP 页面；中期把 API base 改成运行时配置 |
| 使用 `DevBridge-Server` | Spring Boot jar 已可打包 | 静态资源访问异常、端口固定 | 修复静态资源访问；支持动态端口或端口探测 |
| 客户端启动自动拉起服务 | jar 可通过 `java -jar` 启动 | 需 Electron 主进程管理子进程 | Electron main 使用 `child_process.spawn` 启动后端，健康检查通过后加载页面 |
| 客户端自动加载页面 | 后端已有页面托管基础 | 根路径当前返回 500 | PoC 中验证 `/` 或 `/index.html` 返回 200 后再加载 |
| 打包为 PC 客户端 | 需新增独立 `DevBridge-Electron` 工程配置 | JRE、jar、tools、平台安装包配置未定义 | 采用 `electron-builder` 或 Electron Forge，并将 jar/JRE/tools 作为 extraResources |

## 5. 技术路线候选

### 5.1 方案 A：独立 `DevBridge-Electron` 启动 Spring Boot，本地 HTTP 加载页面

- 路线：新增独立 `DevBridge-Electron` 工程；构建 `DevBridge-Front/dist`；打包 `DevBridge-Server` fat jar；`DevBridge-Electron` 在打包阶段收集 jar、JRE、tools 等资源，并在运行时启动 jar，等待 `/api/runtime/environment` 健康检查，再加载 `http://127.0.0.1:{port}/`。
- 核心要点：后端仅监听 `127.0.0.1`；端口冲突时动态选择端口；Electron 退出时关闭后端进程；工具目录和临时目录通过启动参数传入。
- 改动范围：新增 `DevBridge-Electron` 包配置和 main/preload；后端配置增加端口/工具目录参数支持；前端 API base 改为同源或运行时配置。
- 优点：复用现有前后端架构，REST/SSE/下载链路变化最小。
- 缺点：客户端内会有 Electron 与 Java 两个进程；需要处理 JRE 分发、端口和进程生命周期。
- 适用条件：接受客户端内置或依赖 Java 17 运行时。

### 5.2 方案 B：Electron 直接加载前端静态文件，后端只提供 API

- 路线：Electron `loadFile` 加载前端 `dist/index.html`，同时启动后端 API。
- 核心要点：前端继续请求 `http://127.0.0.1:{port}`；需要配置 CORS、CSP、下载、媒体预览和 SSE。
- 改动范围：前端运行时 API base、后端 CORS、安全策略、Electron 文件协议配置。
- 优点：页面不依赖 Spring Boot 静态资源。
- 缺点：跨域面扩大，安全策略更复杂，和当前代码事实不匹配。
- 适用条件：后端静态资源问题短期无法修复时作为备选。

## 6. 推荐方案

- 推荐：方案 A。
- 推荐理由：当前后端已设计为本机单服务承载 H5 页面和 API，前端也固定请求 `127.0.0.1:8080`。把 Electron 独立为 `DevBridge-Electron` 可以让客户端打包、安装包、JRE 分发、进程编排和业务前后端解耦，整体改动最小且边界清晰。
- 不选方案 B 的理由：`file://` 形态会引入新的 CORS、CSP、SSE 和下载处理问题，不符合最小改动原则。
- 前置条件：修复 jar 静态资源访问 500；后端端口可配置；Electron 能定位 JRE、jar 和 `tools/{os-arch}`；确定首批交付平台为 Windows/macOS 中的哪些架构。

## 7. 风险矩阵

| 风险 | 等级 | 触发条件 | 影响范围 | 发现方式 | 缓解措施 | 回退方案 | 是否需 Demo |
|------|------|----------|----------|----------|----------|----------|-------------|
| 后端静态资源访问 500 | 高 | Electron 加载 `/` 或 `/index.html` | 客户端白屏 | `curl /`、Electron 启动检查 | 修复静态资源映射或改为加载明确健康页面 | 临时使用方案 B | 是 |
| 端口 8080 被占用 | 高 | 用户本机已有服务占用端口 | 后端无法启动，客户端打不开 | 启动日志、端口探测 | 启动前找可用端口，并把端口传给后端和前端 | 提示用户关闭占用进程 | 是 |
| JRE 分发与版本 | 高 | 用户电脑无 Java 17 或版本不兼容 | 客户端无法启动后端 | 安装包 smoke test | 打包内置 JRE，Electron 启动内置 java | 提示安装 JDK/JRE 17 | 是 |
| 工具目录定位失效 | 中 | Electron 安装目录和工作目录变化 | adb/idevice/hdc 不可用 | `/api/tools/status` | 使用绝对 `--devbridge.bundled-tool-root` 参数 | 回退 PATH 查找 | 是 |
| 外部设备权限/驱动 | 中 | Windows ADB 驱动缺失、macOS 工具未安装 | 设备无法识别 | 工具状态和设备接口 | 内置 ADB；iOS/Harmony 提供安装/诊断提示 | 保持降级展示 | 否 |
| 子进程泄漏 | 中 | Electron 异常退出或多开 | 后端残留占用端口 | 进程列表、端口检查 | 记录 PID，退出时 kill；启动时识别自有进程 | 用户手动结束进程 | 是 |
| 本地 API 无鉴权 | 中 | 恶意网页访问本机端口 | 设备数据或文件被读取 | 安全测试 | 仅监听 127.0.0.1；启动随机 token；前端请求带 token | 限制敏感接口 | 是 |

## 8. 安全与质量评估

- 性能：Electron 会增加桌面壳层内存；Java 后端仍处理设备命令、SSE 和文件流，现有架构可承受本机单用户场景。
- 可扩展性：Electron 只做进程编排和窗口，不侵入业务代码，后续可扩展托盘、自动更新、日志导出。
- 可维护性：保留前后端工程边界，打包脚本集中管理，避免把业务 API 搬进 Electron main。
- 高可用：客户端需有启动超时、健康检查、失败页和后端崩溃重启策略。
- 数据一致性：当前主要是设备读取、临时文件和日志 zip；需保证退出时清理临时目录。
- 安全：本地服务必须继续监听 `127.0.0.1`；建议增加随机会话 token，避免本机其他网页直接调用敏感 API。
- 可观测性：Electron 主进程需要记录后端 stdout/stderr、启动耗时、端口、退出码和安装路径。

## 9. 成本评估

| 工作项 | 工作量 | 依赖 | 说明 |
|--------|--------|------|------|
| 修复后端静态资源访问和 SPA 兜底 | 0.5~1 人天 | Spring Boot | 解决 `/`、`/index.html` 500 |
| 新建 `DevBridge-Electron` 最小工程 | 1~1.5 人天 | Electron 包管理 | 启动 jar、健康检查、窗口加载、退出清理 |
| 动态端口和运行时 API base | 0.5~1 人天 | 前后端配置 | 避免 8080 冲突 |
| 打包 JRE、jar、tools | 1~2 人天 | 安装包工具 | Windows/macOS 分平台验证 |
| 安全 token 和本地访问控制 | 0.5~1 人天 | 后端拦截器/前端请求 | 防止本机跨站滥用 |
| 跨平台 smoke test | 1~2 人天 | Windows/macOS 设备 | 验证安装、启动、设备识别、日志和文件下载 |

## 10. Demo/PoC 建议

- 是否需要 Demo：是。
- 验证目标：Electron 能打包并启动内置后端；后端健康检查通过；窗口加载页面；REST/SSE/下载接口不因客户端化失效；退出时后端进程关闭。
- Demo 范围：新增独立 `DevBridge-Electron` 工程、启动脚本、最小健康检查，不改业务功能。
- 验收标准：
  - `npm run build` 成功。
  - `mvn test` 和 `mvn -DskipTests package` 成功。
  - Electron dev 模式能启动后端并加载页面。
  - 端口冲突时能换端口或明确失败提示。
  - `/api/runtime/environment`、`/api/tools/status` 返回 200。
- 不做内容：不做自动更新、不做完整签名发布、不做业务 UI 重构。

## 11. 后续建议

1. 先修复当前 jar 静态资源访问 500；这是客户端窗口能否打开首页的直接阻断点。
2. 再做 `DevBridge-Electron` PoC，优先验证三工程构建编排、进程编排、动态端口、JRE 路径和工具目录路径。
3. PoC 通过后进入正式 design，明确安装包结构、平台矩阵、安全 token、日志位置和退出清理策略。
