# 实时日志切换设备仍显示旧设备日志排查记录

## 排查步骤

- [x] 搜索 `App.tsx` 中设备选择、实时日志启动、停止和日志缓存逻辑。
- [x] 阅读 `setSel`、`startLogStream`、`stopLogStream`、设备断开 effect 的调用关系。
- [x] 分析设备切换时 EventSource 和日志缓存是否绑定当前设备。
- [x] 修复后执行前端构建、后端静态资源打包和服务重启。

## 假设与验证

| 假设 | 验证结果 | 结论 |
| --- | --- | --- |
| 旧日志显示是因为日志缓存没有按设备隔离。 | 前端只有日志级别分桶，没有记录日志流所属设备。 | 成立。 |
| 旧设备仍在线时切换设备不会停止旧 EventSource。 | 现有 effect 只在选中设备断开或设备不存在时停止；旧设备在线时不触发。 | 成立。 |
| 后端会话串流错误。 | 后端 SSE 路径包含设备 serial；问题出现在前端切换设备后仍保留旧 EventSource 和缓存。 | 不成立。 |

## 根因链

1. 用户在设备 A 上启动实时日志。
2. 前端只记录 `streaming=true`，没有保存“当前日志流属于设备 A”。
3. 用户切换到设备 B，`sel` 更新，但旧 EventSource 仍连接设备 A。
4. 旧 EventSource 继续向全局日志缓存写入设备 A 的日志。
5. 进入设备 B 的实时日志页时，页面读取同一份日志缓存，所以显示设备 A 的实时日志。

## 修复方案

- 新增 `logDeviceKey(device)`，用 `platform:serial` 标识日志流所属设备。
- 新增 `streamingDeviceKeyRef` 保存当前 EventSource 绑定的设备。
- 启动采集时写入当前设备 key。
- 选中设备变化时，如果当前日志流 key 与新选中设备不一致：
  - 关闭旧 EventSource；
  - 清空日志缓存；
  - 停止采集状态；
  - 提示“已切换设备，实时日志采集已停止”。
- 导出成功和连接错误时同步清理 `streamingDeviceKeyRef`。

## 验证结果

- `npx -y yarn@1.22.22 build` 通过。
- `mvn package -DskipTests` 通过。
- 已重启 `devbridge-server`。
- 首页返回 200，已加载新前端资源 `index-C6ZWkQpI.js`。
- `/api/runtime/environment` 返回 200。

## 限制

当前本机接口只返回 1 台在线 Android 设备，无法在真实浏览器中完成“两台在线设备切换”的端到端点击验证；本次修复点位于前端设备切换和日志流绑定逻辑，代码路径已按根因覆盖。
