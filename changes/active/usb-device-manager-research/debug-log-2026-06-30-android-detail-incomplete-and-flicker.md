# Android 设备详情不全与刷新闪烁排查记录

> 日期：2026-06-30  
> 类型：bugfix / delta  
> by AI.Coding

## 排查步骤

- [x] 复查 `/api/devices` 返回：Android 列表快照只包含 `Android Device` 和空系统版本，属于轻量占位数据。
- [x] 复查 `/api/devices/android/{serial}/detail` 返回：旧实现只填充品牌、型号、系统版本、API、电量、分辨率和存储。
- [x] 对照前端 Android 信息页：页面已有安全补丁、内核、基带、Build 指纹、CPU、GPU、内存、像素密度展示位，但后端未提供。
- [x] 对照前端轮询合并逻辑：型号和系统版本已有占位保护，但品牌仍会被 `normalizeDevice` 补齐的 `Android` 覆盖真实品牌。
- [x] 用真实 Android 设备验证 adb 字段来源，确认安全补丁、密度、内核、内存等字段可读取。

## 假设与验证

| 假设 | 验证结果 |
|------|----------|
| Android 详情不全是前端没有展示字段 | 不成立。前端已有大部分展示位，主要是后端未返回。 |
| Android 详情不全是 adb 无法读取 | 不成立。真实设备可读取 `ro.build.version.security_patch`、`wm density`、`/proc/version`、`/proc/meminfo` 等。 |
| 刷新闪烁来自轮询占位数据覆盖详情数据 | 成立。列表接口返回占位品牌/型号/版本，详情接口返回真实值，合并策略不完整会导致 UI 来回变化。 |

## 根因定位链

1. 设备连接后，列表轮询 `/api/devices` 只返回连接快照，不包含完整详情。
2. Android 详情接口旧实现采集字段过少，导致信息页多个字段只能显示 `—`。
3. 前端为了降级展示会给 Android 补齐 `brand: "Android"`，该占位值在列表轮询时可能覆盖详情接口的真实 `HUAWEI`。
4. 同一设备在列表快照和详情快照之间字段值不同，React 状态更新后表现为设备列表和信息页局部闪烁。

## 修复方案

- 后端扩展 `DeviceDetail` 的 Android 字段使用面，补充 GPU、像素密度、Build 指纹、安全补丁、Bootloader、内核版本、基带、CPU 和内存摘要。
- Android 服务继续使用参数化 `adb` 调用，不使用 shell 字符串拼接，避免命令注入风险。
- 对 `/proc/version` 和 `/proc/meminfo` 做结构化解析，避免把冗长构建信息直接推到页面。
- 前端新增品牌占位保护，`Android` / `HarmonyOS` 不再覆盖详情接口读取到的真实品牌。
- Android 信息页移除 IMEI 展示行；真实设备不采集 IMEI，避免敏感标识泄露。

## 验证结果

- `mvn test`：通过，16 个测试全部成功。
- `./node_modules/.bin/vite build`：通过。
- `mvn package -DskipTests`：通过。
- 重启 8080 服务后验证：
  - `/api/devices` 返回 Android 设备 `EMH0223511000196`。
  - `/api/devices/android/EMH0223511000196/detail` 返回 `brand=HUAWEI`、`model=NAM-AL00`、`securityPatch=2022-08-01`、`kernelVersion=Linux version 5.4.86-qgki-gc7d7ad5aea6b-dirty`、`gpu=adreno`、`density=480 dpi`、`cpu=SM7325 / lahaina / qcom`、`ram=7.2 GB`。
