# npm start 启动后工具状态为空排查记录

## 排查步骤

- [x] 检查当前是否存在 `npm start`、Electron、Java 后端进程。
- [x] 检查 `15173`、`18180`、`8080` 端口监听状态。
- [x] 检查 Electron 主进程端口配置。
- [x] 检查 Electron 复制后的前端资源中 API 地址。
- [x] 按用户方式执行 `npm start` 复现启动链路。
- [x] 验证 `18180` 后端接口：`/api/runtime/environment`、`/api/tools/status`、`/api/devices`。
- [x] 重新打包并检查包内前端资源。

## 假设与验证

### 假设 1：后端没有启动

验证结果：不成立。`npm start` 后 Electron 监听 `127.0.0.1:15173`，Java 后端监听 `127.0.0.1:18180`。

### 假设 2：工具包缺失导致工具状态为空

验证结果：不成立。`resources/tools/darwin-arm64/platform-tools/adb` 存在且可执行。后端接口返回：

- `adb available=true`
- `idevice_id available=true`
- `hdc available=false`

`hdc` 为 false 是因为当前 `DevBridge-Server/tools` 本身没有携带 Harmony hdc 工具。

### 假设 3：USB 手机无法被 adb 识别

验证结果：不成立。沙箱外执行 Electron 资源中的 adb 能识别设备：

```text
66J5T19411001963 device usb:0-1 product:HMA-AL00 model:HMA_AL00 device:HWHMA
```

`npm start` 后请求 `/api/devices` 也返回：

```text
android:66J5T19411001963
```

### 假设 4：前端仍请求 8080，导致初始化接口失败

验证结果：成立。`DevBridge-Electron/resources/frontend/assets/index-*.js` 中仍包含 `http://127.0.0.1:8080`。Electron 原先依赖 `webRequest` 在运行时把 8080 重定向到 18180，但 CORS fetch 跨源重定向不稳定，可能导致前端初始化接口全部失败，进而工具状态和设备列表为空。

## 根因链

1. `DevBridge-Front` 源码固定 API 地址为 `http://127.0.0.1:8080`。
2. Electron 不修改 Front 源码，只复制 Front 构建产物到 `resources/frontend`。
3. 复制后的 Electron 前端资源仍指向 8080。
4. Electron 运行时重定向没有从根上改变前端资源中的 API base，导致前端初始化接口可能失败。
5. 前端初始化失败后，工具状态栏没有 `adb/hdc/idevice_id` 行，设备轮询也无法进入真实后端在线状态。

## 修复方案

- 在 `DevBridge-Electron/scripts/prepare-resources.js` 中新增 `patchFrontendApiBase`。
- 每次复制 Front dist 后，只改写 Electron 资源内的 `http://127.0.0.1:8080` 为 `http://127.0.0.1:18180`。
- 不修改 `DevBridge-Front` 和 `DevBridge-Server` 源码。
- 保留 Electron CORS 桥接，支持 `15173 -> 18180`。

## 验证结果

- `node --check scripts/prepare-resources.js` 通过。
- `node --check src/main.js` 通过。
- `npm run build:resources` 通过，并输出：

```text
[DevBridge-Electron] frontend API base patched: assets/index-DpKxFX2M.js
```

- 按用户方式执行 `npm start` 后：
  - `Electron` 监听 `127.0.0.1:15173`
  - `java` 监听 `127.0.0.1:18180`
  - `/api/tools/status` 返回 `adb available=true`
  - `/api/devices` 返回 Android 设备 `66J5T19411001963`
- `npm run package` 通过。
- 新包内前端资源已包含 `127.0.0.1:18180`，不再包含旧的 `127.0.0.1:8080` API base。

## 后续限制

当前资源包只内置 Android adb。Harmony hdc 未内置，iOS 工具依赖本机 `/opt/homebrew/bin`。正式产品化前需要补齐多平台工具分发策略。
