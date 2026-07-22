# iOS 设备详情读取失败排查记录

## 排查步骤

- [x] 复现接口：`/api/devices` 能发现 iOS UDID，但 `/api/devices/ios/{udid}/detail` 返回 `COMMAND_FAILED / Stream closed`。
- [x] 直接验证工具：`ideviceinfo -k DeviceName`、`ProductType`、`HardwareModel`、`HardwarePlatform`、`ProductVersion`、`CPUArchitecture` 均可读取。
- [x] 验证存储域：`com.apple.disk_usage` 单 key 可读取容量；整域输出包含大块 `NANDInfo`，容易触发短命令输出读取问题。
- [x] 修复后验证：后端测试、前端构建、后端打包和真实详情接口均通过。

## 假设与验证

| 假设 | 验证结果 |
| --- | --- |
| iOS 设备未信任电脑或未连接 | 不成立；`/api/devices` 能枚举，`ideviceinfo` 单 key 能直接读取设备名 |
| libimobiledevice 不支持读取设备名和硬件字段 | 不成立；真机可读到设备名、机型标识、硬件型号、硬件平台和 CPU 架构 |
| 后端读取整域磁盘信息导致详情接口整体失败 | 成立；整域磁盘信息输出过大且包含非展示内容，单 key 查询稳定 |

## 根因定位链

1. 设备枚举正常，说明 USB、配对和 `idevice_id` 链路可用。
2. 详情接口失败在后端聚合阶段，而不是工具能力缺失。
3. 原实现依次读取默认域、电池整域、磁盘整域；任一命令失败都会抛出业务异常。
4. iOS 磁盘整域在当前设备上输出很大的 `NANDInfo`，短命令执行器等待进程结束后再读取 stdout，存在管道阻塞或流被关闭风险。
5. 因为磁盘是可选展示字段，却与设备名称、硬件信息共用同一失败路径，最终导致基础信息也无法返回。

## 修复方案

- 将 iOS 详情读取改为白名单单 key 查询，不再读取默认域和磁盘整域。
- 存储、电量等可选 key 查询失败时返回空字段，不影响设备名称、系统版本、硬件型号等基础字段。
- 补充 iOS 硬件字段：`hardwareModel`、`hardwarePlatform`、`deviceClass`、`modelNumber`，前端在硬件区展示。
- 增加单元测试，覆盖“磁盘可选 key 失败但基础字段仍返回”的场景，并断言不再调用磁盘整域查询。

## 验证结果

- `mvn test`：通过，11 个测试全部成功。
- `./node_modules/.bin/vite build`：通过。
- `mvn package -DskipTests`：通过。
- 真实接口 `/api/devices/ios/{udid}/detail`：已返回设备名称、机型标识、系统版本、Build、电量、存储摘要和硬件字段。
