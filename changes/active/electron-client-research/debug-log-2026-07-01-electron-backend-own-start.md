# Electron 后端独立启动排查记录

## 排查步骤

- [x] 检查 `DevBridge-Electron/src/main.js` 的启动链路，确认后端启动逻辑。
- [x] 请求 `http://127.0.0.1:8080/api/runtime/environment` 验证后端健康状态。
- [x] 请求 `http://127.0.0.1:8080/api/devices` 验证设备接口是否有真实数据。
- [x] 带 `Origin: http://127.0.0.1:5173` 请求 `/api/devices`，验证前端页面访问路径。
- [x] 查看本机进程，确认 8080 对应进程来源。
- [x] 修改 Electron 默认策略，避免静默复用外部后端。

## 假设与验证

### 假设 1：客户端没有启动后端，所以设备无法显示

验证结果：部分成立。当前 8080 确实存在 DevBridge 后端，但进程来源是单独启动的 `DevBridge-Server`，不是 Electron 子进程。

### 假设 2：设备接口没有数据

验证结果：不成立。不带浏览器 Origin 直接请求 `/api/devices` 返回了 iOS 设备数据。

### 假设 3：前端页面跨域访问触发后端异常

验证结果：成立。带 `Origin: http://127.0.0.1:5173` 请求 `/api/devices` 返回 500，后端日志显示 `NoClassDefFoundError: org/apache/tomcat/util/http/NamesEnumerator`，异常发生在 Spring CORS 处理链路。

## 根因链

1. `DevBridge-Front` 当前构建产物中 API 地址硬编码为 `http://127.0.0.1:8080`。
2. Electron PoC 使用 `127.0.0.1:5173` 承载前端静态资源，因此浏览器访问 `8080/api/*` 会产生跨域请求。
3. 后端在处理带 Origin 的 CORS 请求时触发 Tomcat 类缺失异常，导致页面判断“未连接本机后端”。
4. 同时 Electron 原逻辑会在发现 8080 已有 DevBridge 后端时静默复用，导致本地测试无法判断后端是否由客户端实际启动。

## 修复方案

1. Electron 主进程继续对 `127.0.0.1:8080/api/*` 做桌面端 CORS 桥接：去掉请求 Origin，并补充响应 CORS 头。这样不修改 Front/Server 源码也能让 Electron 内页面访问设备接口。
2. 修改 `ensureBackend` 默认策略：如果 8080 已有 DevBridge 后端且未显式设置 `DEVBRIDGE_ELECTRON_REUSE_BACKEND=1`，则启动失败并提示关闭独立 `DevBridge-Server`。这样 PoC 测试默认验证 Electron 自己拉起内置后端。

## 验证方式

- 关闭当前独立运行的 `DevBridge-Server`。
- 重新运行 `DevBridge-Electron` 的 `npm start`。
- 启动页应进入“启动内置后端服务 / 等待后端就绪”阶段。
- 进入主页后设备列表应通过 Electron CORS 桥接访问后端真实接口。
