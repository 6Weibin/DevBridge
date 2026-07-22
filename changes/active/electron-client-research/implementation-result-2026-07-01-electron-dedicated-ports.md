# Electron 专用端口调整实现记录

## 需求

Electron 启动的端口不再使用 `5173` 和 `8080`，改为：

- 前端静态服务：`127.0.0.1:15173`
- 后端 Spring Boot 服务：`127.0.0.1:18180`

## 实现

- 修改 `DevBridge-Electron/src/main.js` 中的 Electron 服务端口常量。
- 后端启动参数 `--server.port` 改为使用 `18180`。
- 健康检查改为访问 `18180`。
- 主页面加载地址改为 `http://127.0.0.1:15173/`。
- 由于 `DevBridge-Front` 源码不能修改，且当前构建产物 API 地址仍指向 `127.0.0.1:8080`，Electron 主进程新增请求重定向：
  - `http://127.0.0.1:8080/api/*`
  - 重定向到 `http://127.0.0.1:18180/api/*`
- CORS 桥接同步支持 `15173 -> 18180`。

## 验证

- `node --check src/main.js`
- `node --check src/preload.js`
- `node --check src/splash/splash.js`
- `npm run package`

## 说明

这次改动只修改 `DevBridge-Electron` 工程，不修改 `DevBridge-Front` 和 `DevBridge-Server` 源码。
