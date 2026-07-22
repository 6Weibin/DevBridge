# iOS 列表与详情字段来回闪烁排查记录

## 排查步骤

- [x] 复查前端 `applyDeviceSnapshot`、`mergeDeviceSnapshot`、`loadDeviceDetail` 的数据流。
- [x] 对比接口返回：`/api/devices` 返回轻量占位 `model=iOS Device, osVersion=""`，详情接口返回 `model=iPhone15,3, osVersion=iOS 26.5`。
- [x] 确认上一轮“相同数据不刷新”只能避免相同对象重复写入，不能阻止轻量快照覆盖完整详情。
- [x] 修复合并规则：设备列表轮询只能更新连接状态和快照字段，不能用占位型号/版本覆盖详情字段。

## 假设与验证

| 假设 | 验证结果 |
| --- | --- |
| 设备详情接口本身返回值不稳定 | 不成立；详情接口稳定返回真实型号和 iOS 版本 |
| 列表接口与详情接口字段粒度不同导致来回覆盖 | 成立；列表接口是轻量发现结果，详情接口是完整设备信息 |
| 状态点闪烁来自连接状态反复变化 | 未发现；当前 `/api/devices` 持续返回 connected |

## 根因定位链

1. `/api/devices` 为了保持轻量轮询，只返回设备发现信息，iOS 型号是 `iOS Device`，系统版本为空。
2. 前端 `normalizeDevice` 会把空系统版本补成平台占位 `iOS`。
3. `mergeDeviceSnapshot` 之前使用 `{...previous, ...normalized}`，导致轮询快照覆盖详情接口读取到的 `iPhone15,3 / iOS 26.5`。
4. 详情轮询随后又把真实值写回来，于是设备列表和设备信息页出现来回闪烁。

## 修复方案

- 增加 `isGenericModel` 判断平台占位型号。
- 增加 `isGenericOsVersion` 判断平台占位系统版本。
- 增加 `stableSnapshotText`，当轮询快照是占位值且旧值更完整时保留旧值。
- `mergeDeviceSnapshot` 对 `model` 和 `osVersion` 使用稳定合并，避免轻量快照覆盖详情字段。

## 验证结果

- `./node_modules/.bin/vite build`：通过。
- `mvn test`：通过，11 个测试全部成功。
- `mvn package -DskipTests`：通过。
- 8080 首页已引用新资源 `/assets/index-DscZ_0qw.js`。
- `/api/devices` 与 iOS 详情接口验证正常。
