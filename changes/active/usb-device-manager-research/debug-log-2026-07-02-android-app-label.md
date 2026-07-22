# Android 应用真实名称解析

## 排查步骤

- [x] 检查前端应用管理字段来源：前端展示 `InstalledApp.name`，不是 UI 写死包名。
- [x] 检查后端应用列表接口：`AndroidDeviceService.listInstalledApps` 使用 `cmd package list packages -f -U --show-versioncode` 读取基础包信息。
- [x] 检查解析器：`DeviceOutputParser.parseAndroidPackages` 当前把 `name` 初始化为 `packageName`。
- [x] 评估 APK 拉取方案：该方案需要把每个 APK 从设备传输到本机，应用多或 APK 大时性能不可接受。
- [x] 实施方案 A：复用 `dumpsys package packages` 输出，尽量解析 `application-label`、`nonLocalizedLabel`、`label`。
- [x] 补充单元测试并执行 `mvn test`、`mvn package -DskipTests`。

## 假设与验证

| 假设 | 验证结果 |
|------|----------|
| 应用名称显示为包名是前端问题 | 不成立。前端展示后端返回的 `name` 字段。 |
| 当前后端命令能直接返回真实应用名称 | 不成立。`cmd package list packages` 只提供包名、路径、uid、versionCode 等基础信息。 |
| 拉取 APK 后解析 label 可稳定解决 | 技术上可行，但批量应用列表性能风险高，不适合作为默认方案。 |
| 可以先从设备包详情输出中解析 label | 成立但有边界。不同 Android 版本和 ROM 对 label 输出不一致，解析不到时必须回退包名。 |

## 根因定位

应用管理页显示包名的根因是后端基础应用列表命令不包含真实 label，并且 `DeviceOutputParser.parseAndroidPackages` 按包名填充 `InstalledApp.name`。前端没有额外获取或转换应用名称，因此最终 UI 中“应用名称”等于包名。

## 修复方案

在不拉取 APK、不安装设备端 helper 的前提下，后端复用已有 `dumpsys package packages` 详情输出：

1. `AndroidDeviceService.listInstalledApps` 一次读取包详情，同时解析版本名称和应用 label。
2. `DeviceOutputParser.parseAndroidPackageLabels` 支持解析 `application-label`、`application-label-xx`、`nonLocalizedLabel`、`label`。
3. 过滤 `null`、`0`、`0x...` 这类不可展示值，避免把资源 ID 当成应用名称。
4. 解析失败或系统不暴露 label 时，继续回退包名，保证应用列表可用。

## 验证结果

- `mvn test`：通过，32 个测试全部成功。
- `mvn package -DskipTests`：通过，已生成最新后端 jar。

## 已知边界

方案 A 依赖 Android 设备命令输出是否暴露可读 label。若目标设备只输出 `labelRes` 或完全不输出 label，仍会显示包名。要做到稳定覆盖所有设备，需要后续引入设备侧 helper 调用 `PackageManager.getApplicationLabel()`。
