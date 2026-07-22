# Electron 文件删除 Failed to fetch 排查记录

## 排查步骤

- [x] 复核前端文件删除调用，确认删除使用 `DELETE /api/devices/{platform}/{serial}/files?path=...`。
- [x] 阅读 `FileController`，确认后端文件删除接口已存在，且 8080 浏览器场景可正常访问同源接口。
- [x] 阅读 `DevBridge-Electron/src/main.js`，确认 Electron 客户端通过 15173 前端服务访问 18180 后端服务。
- [x] 检查 Electron CORS 桥接，发现 `Access-Control-Allow-Methods` 只包含 `GET,POST,OPTIONS`。
- [x] 执行 `node --check DevBridge-Electron/src/main.js`，确认修复后主进程脚本语法正确。

## 假设与验证

| 假设 | 验证结果 |
| --- | --- |
| Android 删除命令失败导致前端报错 | 不成立。设备命令失败应返回后端 JSON 错误，不会在前端表现为 `Failed to fetch`。 |
| 后端文件删除接口不存在 | 不成立。`FileController` 已存在 `@DeleteMapping` 文件删除接口。 |
| Electron 跨域预检未允许 DELETE | 成立。Electron 前端跨端口访问 18180，`DELETE` 会触发预检，但桥接响应头未包含 `DELETE`。 |

## 根因链路

Electron 客户端页面运行在 `http://127.0.0.1:15173`，后端运行在 `http://127.0.0.1:18180`。文件删除使用 `DELETE` 方法，浏览器会先发起 CORS 预检。Electron 主进程虽然对本机 API 做了 CORS 桥接，但 `Access-Control-Allow-Methods` 只返回 `GET,POST,OPTIONS`，缺少 `DELETE`。因此预检失败，前端 fetch 被浏览器拦截，最终只显示 `Failed to fetch`。

## 修复方案

在 `DevBridge-Electron/src/main.js` 中把 Electron API 桥接允许的方法改为 `GET,POST,DELETE,OPTIONS`，并增加中文注释说明文件删除和应用卸载都会依赖 `DELETE` 预检。该修复只影响 Electron 本机 API 桥接，不改变后端文件删除业务逻辑，也不扩大对外网络暴露范围。
