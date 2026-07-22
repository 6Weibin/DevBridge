# Debug Log：Electron 页面无法读取后端设备接口

## 1. 问题现象

用户反馈 Electron 客户端已经进入页面，但无法显示连接设备，怀疑客户端没有启动后端服务。

## 2. 排查步骤

| 步骤 | 假设 | 验证结果 |
|------|------|----------|
| 检查后端健康接口 | 8080 后端可能没启动 | 不成立，`/api/runtime/environment` 返回 200 |
| 检查设备接口 | 后端可能没有设备数据 | 不成立，不带 `Origin` 请求 `/api/devices` 返回 iOS 设备 |
| 检查 Electron 页面同源条件 | 页面跨域请求可能失败 | 成立，带 `Origin: http://127.0.0.1:5173` 请求 API 返回 500 |
| 查看后端日志 | 后端 CORS 链路可能异常 | 成立，日志显示 `NoClassDefFoundError: org/apache/tomcat/util/http/NamesEnumerator` |
| 检查进程 | Electron 是否启动了自己的后端 | 当前没有，因 8080 已有 DevBridge 后端，Electron 复用了已有服务 |

## 3. 根因定位链

1. `DevBridge-Front` 构建产物由 Electron 静态服务加载在 `http://127.0.0.1:5173`。
2. 前端代码请求固定 API 地址 `http://127.0.0.1:8080`，浏览器会发送 `Origin`。
3. 后端进入 Spring MVC CORS 处理链路。
4. 当前运行的 Spring Boot/Tomcat fat jar 在 CORS 读取 Header 时缺失 `NamesEnumerator` 类，导致 500。
5. 前端因此无法拿到工具状态和设备列表，表现为页面无法显示连接设备。

## 4. 修复方案

在 `DevBridge-Electron/src/main.js` 中新增 `registerBackendApiCorsBridge()`：

- 仅匹配 `http://127.0.0.1:8080/api/*`。
- 请求发送前删除 `Origin`，避免触发后端 CORS 异常路径。
- 响应返回时补充 `Access-Control-Allow-Origin: http://127.0.0.1:5173`，让 Electron 页面可以正常读取响应。
- 启动页增加明确提示：检测到已有后端时显示“复用已有后端服务”；自行启动时显示“启动内置后端服务”。

该方案只修改 `DevBridge-Electron`，不修改 `DevBridge-Front` 和 `DevBridge-Server`。

## 5. 验证结果

| 命令 | 结果 | 说明 |
|------|------|------|
| `node --check src/main.js` | 通过 | 主进程语法正确 |
| `node --check src/preload.js && node --check src/splash/splash.js` | 通过 | 启动页相关脚本语法正确 |
| `npm run package` | 通过 | 客户端 zip 重新生成 |
| `asar.listPackage(...)` | 通过 | 新 `src/main.js` 与启动页资源进入 app.asar |

## 6. 注意事项

当前 8080 已有从 `DevBridge-Server` 目录启动的 Java 进程，所以 Electron 会显示复用已有后端。若要验证“客户端自行启动后端”，需要先关闭现有 8080 后端，再重新执行 `npm start`。
