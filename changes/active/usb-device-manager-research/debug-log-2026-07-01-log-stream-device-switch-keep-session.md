# 实时日志切换设备后采集状态丢失排查记录

## 排查步骤

- [x] 复核用户复现路径：设备 A 开启实时日志采集，切换到设备 B，再切回设备 A。
- [x] 阅读 `DevBridge-Front/src/app/App.tsx` 中实时日志状态、SSE 连接、日志缓存和设备切换 effect。
- [x] 阅读 `DevBridge-Server/src/main/java/com/devbridge/server/service/LogStreamService.java`，确认后端以 `platform:serial` 维护单设备会话，不同设备可以并行采集。
- [x] 修改前端日志状态模型，并执行前端构建、后端打包和服务重启验证。

## 假设与验证

### 假设 1：后端只允许全局一个日志采集会话

验证结果：不成立。后端 `serialSessions` 使用 `platform:serial` 作为 key，只会替换同一设备的旧会话，不会阻止不同设备并行采集。

### 假设 2：前端把“当前页面选中的设备变化”误处理成“停止旧设备采集”

验证结果：成立。之前的修复中，当 `streamingDeviceKeyRef.current !== logDeviceKey(sel)` 时直接调用 `stopLogStream()`，导致切换到设备 B 时关闭了设备 A 的 EventSource 和后端日志进程。

### 假设 3：只移除停止逻辑即可

验证结果：不成立。如果只保留旧 EventSource 而不拆分日志缓存，设备 A 的日志仍会写入全局 `logBucketsRef`，在设备 B 页面继续显示，回到原始问题。

## 根因定位链

症状：切换设备后再切回，之前已经开启的实时日志采集变成停止状态。

直接原因：设备切换 effect 在发现当前日志流设备 key 和选中设备 key 不一致时，主动关闭了唯一的 EventSource。

根因：前端日志采集模型仍是“全局单状态”，只有一个 `streaming`、一个 EventSource、一个日志分桶缓存。这个模型无法同时满足“切换设备不能显示旧设备日志”和“切回设备后原采集仍保持开启”。

## 修复方案

- 将 `streaming` 改为按设备 key 派生：`streamingDeviceKeys.includes(selectedLogDeviceKey)`。
- 将 EventSource 从单引用改为 `eventSourcesRef[deviceKey]`。
- 将日志分桶缓存改为 `logBucketsByDeviceRef[deviceKey]`。
- 将待刷新日志队列改为 `pendingLogLinesRef[deviceKey]`。
- SSE 回调写入启动时绑定的设备 key，不再依赖当前页面选中设备。
- 切换设备时只刷新当前设备日志视图，不停止其它设备采集。
- 设备断开时只停止对应设备的采集。
- 导出日志时只停止当前选中设备的采集。

## 验证结果

- `DevBridge-Front` 执行 `npx -y yarn@1.22.22 build` 通过。
- `DevBridge-Server` 执行 `mvn package -DskipTests` 通过。
- 后端 jar 已重新启动到 `http://127.0.0.1:8080/`。
- 首页返回 200，并加载新资源 `/assets/index-N2mH0HKn.js`。
- `/api/runtime/environment` 返回 200。

## 限制

当前本机环境仍无法确认存在两台真实 Android 设备同时在线，因此浏览器端双设备实测需要在用户设备环境中完成。代码路径已经按设备 key 完成隔离。
