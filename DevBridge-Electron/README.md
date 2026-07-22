# Ai DevBridge Electron

Ai DevBridge 桌面客户端 PoC 工程。

本工程只负责客户端化编排，不修改 `DevBridge-Front` 和 `DevBridge-Server` 源码。

## 构建流程

```bash
npm install
npm run package
```

构建脚本会执行：

1. `DevBridge-Front` 的 Vite 构建。
2. `DevBridge-Server` 的 Spring Boot jar 打包。
3. 收集前端 dist、后端 jar、tools，并将 Electron 资源内的 API 地址从 `127.0.0.1:8080` 改写为 `127.0.0.1:18180`。
4. 尝试用 `jlink` 生成内置 Java runtime。
5. 使用 `electron-builder` 输出 macOS zip 客户端包。

## 本地运行

```bash
npm start
```

启动时会先展示 Electron 工程自带的启动页，页面会显示当前启动阶段和进度。前端静态服务和后端服务全部就绪后，客户端会自动进入 `DevBridge-Front` 主页。

如果本机已经有服务占用 `18180`，请先关闭该进程再运行客户端。PoC 默认要求 Electron 自己启动内置后端，避免测试结果依赖外部服务。

如确实需要临时复用已有后端，可以执行：

```bash
DEVBRIDGE_ELECTRON_REUSE_BACKEND=1 npm start
```

## 运行约束

- 前端静态服务固定使用 `127.0.0.1:15173`，避免占用 Vite 常用的 `5173`。
- 后端服务固定使用 `127.0.0.1:18180`，避免占用 Spring Boot 常用的 `8080`。
- 若 `18180` 被其他服务占用，客户端会提示释放端口。
- Electron 会在资源准备阶段改写 `DevBridge-Front` 构建产物中的 API 地址，并保留桌面端 CORS 桥接，避免修改 `DevBridge-Front` 和 `DevBridge-Server` 源码。
- 若构建环境没有 `jlink`，客户端会回退使用系统 `java` 命令。
