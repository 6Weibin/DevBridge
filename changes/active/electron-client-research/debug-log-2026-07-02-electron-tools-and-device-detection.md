# Electron 工具状态和 USB 设备识别排查记录

## 排查步骤

- [x] 检查 Electron 资源目录是否包含后端 jar、前端 dist 和 tools。
- [x] 检查 `DevBridge-Server` 的工具定位逻辑和设备枚举逻辑。
- [x] 直接执行 Electron 资源中的 `adb version` 和 `adb devices -l`。
- [x] 使用 Electron 等价参数启动后端，验证 `/api/tools/status` 和 `/api/devices`。
- [x] 检查 Electron 主进程端口配置、前端硬编码 `8080` 的重定向逻辑。
- [x] 重新打包客户端并验证 app.asar 内端口配置。

## 假设与验证

### 假设 1：Electron 包没有带上 adb

验证结果：不成立。`DevBridge-Electron/resources/tools/darwin-arm64/platform-tools/adb` 存在，打包后的 `DevBridge.app/Contents/Resources/devbridge/tools/.../adb` 也存在且保留可执行权限。

### 假设 2：adb 无法识别 USB 设备

验证结果：不成立。沙箱外直接执行 Electron 资源中的 adb，可以识别到 Android 设备：

```text
66J5T19411001963 device usb:0-1 product:HMA-AL00 model:HMA_AL00 device:HWHMA
```

### 假设 3：后端以 Electron 参数启动后无法找到工具

验证结果：不成立。以 Electron 等价参数启动 `18180` 后端后：

- `/api/tools/status` 返回 `adb available=true`
- `/api/devices` 返回 `android:66J5T19411001963`

### 假设 4：Electron 端口配置与预期不一致导致前端没有打到真实后端

验证结果：成立。当前 `DevBridge-Electron/src/main.js` 中后端端口一度为 `18080`，与需求、README 和启动页展示的 `18180` 不一致。该不一致会造成排查和客户端包行为混乱，前端也可能无法请求到预期后端。

## 根因链

1. `DevBridge-Front` 源码中 API 地址仍硬编码为 `127.0.0.1:8080`，Electron 需要在主进程中把请求重定向到客户端后端端口。
2. Electron PoC 的目标端口应为前端 `15173`、后端 `18180`。
3. 主进程实际端口出现偏差，后端常量变成 `18080`，导致客户端启动行为与文档和启动页不一致。
4. 在端口不一致或使用旧包时，前端会表现为后端未连接，进而工具状态缺失、设备列表无法显示。

## 修复方案

- 将 `DevBridge-Electron/src/main.js` 的 `BACKEND_PORT` 修正为 `18180`。
- 保持 Electron 内部 `8080/api/* -> 18180/api/*` 请求重定向，继续满足不修改 `DevBridge-Front` 源码的约束。
- 重新执行 `npm run package`，确保新包内 `app.asar` 已包含 `18180`，不再包含 `18080`。

## 验证结果

- `node --check src/main.js` 通过。
- `node --check src/splash/splash.js` 通过。
- `npm run package` 通过。
- 启动 Electron 后：
  - `Electron` 监听 `127.0.0.1:15173`
  - `java` 后端监听 `127.0.0.1:18180`
  - `/api/tools/status` 中 `adb available=true`
  - `/api/devices` 返回 Android 设备 `66J5T19411001963`
- 新包路径：`DevBridge-Electron/release/DevBridge-0.1.0-arm64.zip`

## 备注

当前 Electron 资源包内置了 Android `adb`。Harmony `hdc` 未打入资源包；iOS 相关工具本次验证来自本机 `/opt/homebrew/bin`。因此正式产品化前仍需补齐不同平台工具的分发策略。
