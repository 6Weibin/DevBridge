# Demo 计划：DevBridge Electron PC 客户端化

## 1. Demo 目标

- 验证假设：独立 `DevBridge-Electron` 工程可以在不修改 `DevBridge-Front` 和 `DevBridge-Server` 源码的前提下，编排前端 dist、后端 jar、设备工具和运行时资源，生成可启动的桌面客户端包。
- 成功标准：本机完成 Electron 客户端编译打包，输出可测试安装包或应用包；启动后能自动启动/复用后端，并加载前端页面。

## 2. Demo 范围

- 包含：新增 `DevBridge-Electron` 工程、Electron 主进程、资源准备脚本、打包配置、README、构建验证。
- 不包含：不修改 `DevBridge-Front` 和 `DevBridge-Server` 源码；不做自动更新；不做正式签名、公证和多平台发布矩阵。

## 3. 实现方案

| 文件/模块 | 操作 | 说明 |
|-----------|------|------|
| `DevBridge-Electron/package.json` | 新增 | Electron 依赖、构建脚本和打包配置 |
| `DevBridge-Electron/src/main.js` | 新增 | 启动静态前端服务、启动/复用后端 jar、创建窗口 |
| `DevBridge-Electron/src/preload.js` | 新增 | 预留安全隔离 preload |
| `DevBridge-Electron/scripts/prepare-resources.js` | 新增 | 收集前端 dist、后端 jar、tools，并尝试生成内置 JRE |
| `DevBridge-Electron/README.md` | 新增 | Demo 构建和运行说明 |

## 4. 验证命令

| 命令 | 预期结果 |
|------|----------|
| `npm --prefix DevBridge-Front run build` | 前端 dist 构建成功 |
| `mvn -f DevBridge-Server/pom.xml -DskipTests package` | 后端 jar 构建成功 |
| `npm install` | `DevBridge-Electron` 依赖安装成功 |
| `npm run package` | 输出可测试的桌面客户端包 |

## 5. 风险控制

- 隔离方式：所有新增源码和配置只放入 `DevBridge-Electron`。
- 清理方式：删除 `DevBridge-Electron` 即可移除本次 PoC；前后端源码无改动。
- 安全措施：后端仍只监听 `127.0.0.1`；Electron 关闭 `nodeIntegration`，启用 `contextIsolation`。
