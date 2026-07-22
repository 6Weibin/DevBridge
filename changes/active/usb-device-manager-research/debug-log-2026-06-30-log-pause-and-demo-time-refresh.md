# 实时日志暂停后被清空与未采集时间跳动排查记录

## 问题现象

- 实时日志点击“暂停”后，日志列表会被清空或被演示日志覆盖。
- 未开始采集日志时，日志面板最后三条演示数据的时间一直变化。

## 排查步骤

- 搜索 `App.tsx` 中所有 `setLogs` 调用点。
- 阅读日志启动、暂停、设备轮询、完整刷新相关 effect。
- 检查后端 `/api/logs/demo`，确认演示日志时间来自后端当前时间。
- 执行前端构建、后端测试和打包验证。

## 假设与验证

| 假设 | 验证结果 | 结论 |
| --- | --- | --- |
| 暂停按钮直接清空日志 | `stopLogStream` 只关闭 EventSource，不调用 `setLogs([])` | 排除 |
| 后端日志流暂停时返回空列表 | 暂停只是前端关闭 SSE，后端不返回日志列表 | 排除 |
| 完整刷新被设备轮询反复触发 | `refresh` 依赖 `applyDeviceSnapshot`，而后者依赖 `devices`；设备轮询更新 `devices` 后会触发完整刷新 | 确认 |
| 演示日志时间一直变 | `/api/logs/demo` 每次返回 `LocalTime.now()`，完整刷新反复触发后时间持续变化 | 确认 |

## 根因链路

1. 定时轮询 `/api/devices` 更新设备列表。
2. `applyDeviceSnapshot` 依赖 `devices`，每次设备列表更新都会创建新的函数引用。
3. `refresh` 依赖 `applyDeviceSnapshot` 和 `streaming`，导致 `useEffect(()=>refresh(), [refresh])` 被反复触发。
4. 完整刷新会请求 `/api/logs/demo`，并在 `streaming=false` 时执行 `setLogs(...)`。
5. 点击暂停后 `streaming` 变为 `false`，完整刷新更容易覆盖当前日志列表。
6. 未采集时，反复完整刷新导致三条演示日志使用新的当前时间，表现为时间一直在跑。

## 修复方案

- 用 `devicesRef` 和 `backendOnlineRef` 读取最新设备状态，避免设备轮询改变 `applyDeviceSnapshot` 函数引用。
- 首次进入页面时只执行一次完整刷新。
- 后续设备热插拔只走轻量 `/api/devices` 轮询，不触碰日志列表和文件管理。
- 保留手动刷新能力；日志采集中手动刷新也不会覆盖实时日志。

## 验证结果

- `DevBridge-Front` 执行 `vite build` 通过。
- `DevBridge-Server` 执行 `mvn test` 通过，9 个测试全部通过。
- `DevBridge-Server` 执行 `mvn package -DskipTests` 通过。
- 新 jar 已启动，首页引用新资源 `/assets/index-Cp5nc9H_.js`。
