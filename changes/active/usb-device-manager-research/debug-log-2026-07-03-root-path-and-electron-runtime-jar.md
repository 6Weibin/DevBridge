# 根路径访问超时与 Electron 运行副本排查

## 排查步骤

- [x] 验证 `http://127.0.0.1:8080/`、`/index.html` 和静态 JS 资源，确认均出现超时。
- [x] 验证 `/api/runtime/environment`，确认 API 可用，问题不在端口监听。
- [x] 阅读 Spring Boot 启动日志，发现静态资源请求后 Tomcat 线程出现 `NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy`。
- [x] 检查 jar 内容，确认 `logback-classic` 实际存在，排除打包缺依赖。
- [x] 对比运行进程和打包时间，确认服务运行中覆盖同一个 fat jar 会触发 Spring Boot 嵌套依赖按需读取异常。
- [x] 检查 Electron 主进程，确认客户端使用 `15173` 前端静态服务和 `18180` 后端服务，并且后端也直接运行资源目录中的 jar。
- [x] 修改后端本机启动脚本和 Electron 后端启动逻辑，统一运行 jar 副本，避免后续构建覆盖正在运行的 jar。

## 根因

本次问题不是 `/` 路由或 welcome page 配置被改坏。Spring Boot 已识别 `static/index.html`，但服务运行期间重新执行打包，覆盖了正在运行的 `target/devbridge-server-0.1.0-SNAPSHOT.jar`。

Spring Boot fat jar 的嵌套依赖和静态资源存在按需读取行为。运行中的 jar 被覆盖后，进程仍占用端口，部分已加载的 API 类还能工作，但新请求触发静态资源或日志异常链路时，会从已变化的 jar 读取类和资源，导致请求卡住或类加载失败。

## Electron 影响分析

Electron 客户端不直接访问 `8080`：

- 前端静态资源由 Electron 主进程在 `15173` 提供。
- 后端接口由 Electron 拉起内置 jar，在 `18180` 提供。
- 主窗口加载 `http://127.0.0.1:15173/`。

但 Electron 开发态原来直接运行 `resources/backend/devbridge-server.jar`，如果在客户端运行中再次执行 `prepare:resources`，同样可能覆盖正在运行的 jar。因此这次一并改为复制到用户运行目录后再启动。

## 修复方案

- 本机后端启动脚本不再直接运行 `target/devbridge-server-0.1.0-SNAPSHOT.jar`，而是复制到 `target/runtime/devbridge-server-runtime.jar` 后运行。
- 新增 macOS/Linux 启动脚本 `DevBridge-Server/scripts/start-server.sh`。
- 更新 Windows 启动脚本 `DevBridge-Server/scripts/start-server.cmd`。
- Electron 后端启动前复制 `resources/backend/devbridge-server.jar` 到用户运行目录 `runtime/backend/devbridge-server-runtime.jar`，并运行该副本。

## 验证结果

- `http://127.0.0.1:8080/` 返回 `200`。
- `http://127.0.0.1:8080/index.html` 返回 `200`。
- 静态 JS 资源返回 `200`。
- `/api/runtime/environment` 返回 `DevBridge` 版本信息。
- `node --check DevBridge-Electron/src/main.js` 通过。
- `bash -n DevBridge-Server/scripts/start-server.sh` 通过。
- `mvn test` 通过：40 个测试全部成功。
