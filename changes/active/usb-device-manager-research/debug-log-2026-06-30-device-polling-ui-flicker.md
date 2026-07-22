# 设备轮询导致页面闪烁排查记录

## 排查步骤

- [x] 阅读 `DevBridge-Front/src/app/App.tsx` 中设备轮询、详情刷新和 state 合并逻辑。
- [x] 定位 `applyDeviceSnapshot` 每轮都会 `setDevices(normalized)`，即使设备列表内容未变化也会创建新数组。
- [x] 定位 `loadDeviceDetail` 每轮都会创建 `merged` 并写回 `setSel/setDevices`，即使详情字段未变化也会创建新对象。
- [x] 修复后执行前端构建、后端测试、后端打包，并重启 8080 服务验证新静态资源已生效。

## 假设与验证

| 假设 | 验证结果 |
| --- | --- |
| 后端接口数据频繁变化导致闪烁 | 部分成立；电量、存储等真实变化时应更新 |
| 前端在数据未变化时仍写入新 state 导致重渲染 | 成立；设备列表和详情轮询均存在无差别写 state |
| 定时刷新清空日志或文件导致闪烁 | 本次不成立；此前已拆分为轻量设备轮询，本次问题集中在对象引用变化 |

## 根因定位链

1. 页面每 3 秒执行设备快照轮询，并对当前设备执行详情轮询。
2. React state 判断依赖引用；即使字段值相同，只要写入新数组或新对象，也会触发组件重渲染。
3. 设备列表区域、头部信息和详情区都依赖 `devices/sel`，因此无变化轮询也会出现闪烁或局部视觉跳动。
4. 需要在写入 state 前做等价判断，数据未变化时返回原引用，让 React 跳过渲染。

## 修复方案

- 新增 `sameDevice`：比较两个设备对象的实际字段值。
- 新增 `sameDeviceList`：比较设备列表长度、顺序和每个设备字段。
- `applyDeviceSnapshot`：设备列表、选中设备、离线等待态均在无变化时返回原对象。
- `loadDeviceDetail`：详情字段无变化时不更新 `sel` 和 `devices`。
- 后端不可达降级分支也复用相同数据，减少异常轮询时的无意义刷新。

## 验证结果

- `./node_modules/.bin/vite build`：通过。
- `mvn test`：通过，11 个测试全部成功。
- `mvn package -DskipTests`：通过。
- 8080 首页已引用新资源 `/assets/index-oVFC0R2m.js`。
- `/api/devices` 和 iOS 详情接口验证正常。
