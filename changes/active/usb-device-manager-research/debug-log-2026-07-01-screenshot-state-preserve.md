# 截图切换设备和页签后消失排查记录

## 排查步骤

- [x] 复查用户现象：切换设备，或在设备信息、文件管理、实时日志页签之间切换后，设备信息页截图预览消失。
- [x] 检查 `DevBridge-Front/src/app/App.tsx` 中 `ScreenshotPanel` 的状态归属。
- [x] 检查设备页签记忆、截图自动触发和截图能力判断逻辑，确认不支持平台仍不会展示截图区域。
- [x] 将截图预览快照上移为 App 级设备维度缓存。
- [x] 执行前端构建、后端打包、服务重启和接口验证。

## 假设和验证结果

### 假设 1：后端截图接口失效

验证：修复过程中 Android 连接时截图接口返回过 `200 image/png`；最终复验时 Android 已不在设备列表中，接口返回 `409 DEVICE_NOT_CONNECTED`。iOS 截图接口仍返回 `400 PLATFORM_UNSUPPORTED`。接口行为符合设备连接状态和平台能力边界。

结论：不是后端截图能力问题。

### 假设 2：页签切换导致截图组件卸载并丢失本地状态

验证：`ScreenshotPanel` 原先把 `shotUrl`、`shotTime`、`error` 保存在组件内部。切到文件管理或实时日志后，截图组件会卸载；切回设备信息后组件重新挂载，本地状态为空。

结论：该假设成立。

### 假设 3：自动截图没有再次触发导致空白持续存在

验证：自动截图使用 `autoScreenshotDeviceKeysRef` 标记设备首次进入信息页已执行。首次截图后切走再切回不会重新触发自动截图，符合“首次进入自动截图”的需求，但会放大组件本地状态丢失的问题。

结论：自动触发策略不是根因，根因是截图状态生命周期放错层级。

## 根因定位链

症状：截图在切换设备或切换页签后消失。

组件行为：切换到非设备信息页时，`ScreenshotPanel` 被条件渲染移除。

状态行为：截图 Blob URL 保存在 `ScreenshotPanel` 内部状态中，组件卸载后状态释放。

需求冲突：截图是“当前设备的信息快照”，生命周期应跟随设备上下文，而不是跟随截图面板组件实例。

根因：截图状态归属错误，未按设备维度缓存。

## 修复方案

- 在 App 层新增 `screenshotsByDevice`，按 `logDeviceKey(device)` 保存截图快照。
- `ScreenshotPanel` 改为受控组件，通过 `snapshot` 展示当前设备截图，通过 `onSnapshot` 更新快照。
- 替换同设备截图时释放旧 Blob URL，避免重复截图导致内存泄漏。
- 应用卸载时统一释放所有已缓存 Blob URL。
- 保持 `supportsDeviceScreenshot(sel)` 判断不变，当前不支持截图的平台仍不展示截图功能和相关区域。

## 验证结果

- `DevBridge-Front` 执行 `npx -y yarn@1.22.22 build` 通过。
- `DevBridge-Server` 执行 `mvn package -DskipTests` 通过。
- 后端 jar 已重启到 `http://127.0.0.1:8080/`。
- 首页返回 `200`，加载新资源 `/assets/index-CXdjck1Y.js`。
- `/api/runtime/environment` 返回 `200`。
- 最终 `/api/devices` 返回 iOS 连接设备；Android 在最终复验时已不在设备列表中。
- Android 截图接口最终返回 `409 DEVICE_NOT_CONNECTED`，符合当前设备连接状态；修复过程中 Android 连接时曾返回 `200 image/png`，下载大小 `1525066` 字节。
- iOS 截图接口返回 `400 PLATFORM_UNSUPPORTED`，符合当前平台能力边界。
