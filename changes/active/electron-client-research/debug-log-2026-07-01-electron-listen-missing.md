# Debug Log：Electron 启动缺少 listen 函数

## 1. 问题现象

用户执行 `npm start` 启动 Electron 客户端时，主进程报错：

```text
ReferenceError: listen is not defined
at startFrontendServer (.../DevBridge-Electron/src/main.js:77:3)
at async startApplication (.../DevBridge-Electron/src/main.js:29:5)
```

## 2. 排查步骤

| 步骤 | 假设 | 验证结果 |
|------|------|----------|
| 读取堆栈定位文件 | `startFrontendServer` 调用了未定义函数 | 成立，`src/main.js` 第 77 行存在 `await listen(...)` |
| 搜索同名实现 | 工程里可能有遗漏导入或函数定义 | 不成立，`rg "function listen\|listen\("` 只找到调用点 |
| 判断问题层级 | 可能是需求/方案错误，也可能是实现遗漏 | 属于实现遗漏；前端静态服务确实需要等待监听成功 |

## 3. 根因定位链

1. `startApplication` 调用 `startFrontendServer`。
2. `startFrontendServer` 创建 `http.createServer` 后调用 `listen(frontendServer, FRONTEND_PORT, '前端静态服务')`。
3. `src/main.js` 中没有定义 `listen`，也没有从其他模块导入。
4. Electron 主进程运行到该调用点时触发 `ReferenceError`，客户端启动中断。

## 4. 修复方案

在 `DevBridge-Electron/src/main.js` 中新增：

- `listen(server, port, serviceName)`：Promise 化 `http.Server.listen`，保证服务监听成功后再继续创建窗口。
- `formatListenError(error, port)`：将 `EADDRINUSE` 转换为明确的端口占用提示。

该修复保持 Electron PoC 的原设计，不修改 `DevBridge-Front` 和 `DevBridge-Server` 源码。

## 5. 验证结果

| 命令 | 结果 | 说明 |
|------|------|------|
| `node --check src/main.js` | 通过 | 主进程语法正确 |
| `rg "function listen\|await listen\|formatListenError" -n src/main.js` | 通过 | 调用点和实现已匹配 |
| `npm run package` | 通过 | 已重新生成客户端 zip |

## 6. 产物

- 修复文件：`DevBridge-Electron/src/main.js`
- 重新生成客户端包：`DevBridge-Electron/release/DevBridge-0.1.0-arm64.zip`
