# Android 文件管理根目录误判不可访问

## 排查步骤

- 查询当前设备接口，确认 Android 设备 `66J5T19411001963` 处于 connected。
- 请求文件接口：
  - `/api/devices/android/66J5T19411001963/files?path=/` 返回 404。
  - `/api/devices/android/66J5T19411001963/files?path=/sdcard` 返回 200。
  - `/api/devices/android/66J5T19411001963/files?path=/storage/emulated/0` 返回 200。
- 直接执行 adb：
  - `adb shell ls -la /` 输出了大量根目录条目，但退出码为 1。
  - stderr 中包含多个 `Permission denied`，例如 `//init.rc`、`//metadata`、`//sec_storage`。

## 根因

后端 `AndroidDeviceService.listFiles` 原逻辑只看 `adb shell ls -la /` 的退出码：

- 根目录 `/` 下存在普通 shell 无权读取的系统文件。
- `ls` 一边输出可访问目录项，一边对无权限文件输出 `Permission denied`。
- 这种情况下 adb 退出码是 1。
- 后端看到非 0 退出码后直接抛出 `REMOTE_PATH_NOT_FOUND`，导致前端提示“远端目录不存在或不可访问”。

实际情况不是设备文件管理整体不可用，而是根目录存在部分不可访问系统文件；`/sdcard` 等用户可见目录一直是可访问的。

## 修复

- `listFiles` 先解析 stdout。
- 如果命令非 0 但 stdout 中有可解析目录项，则返回可访问部分。
- 只有命令非 0 且没有任何可解析节点时，才返回“远端目录不存在或不可访问”。
- 补充回归测试，覆盖根目录部分 `Permission denied` 但仍有可访问条目的场景。

## 验证

- `mvn test`：通过，27 个测试全部成功。
- `mvn clean package -DskipTests`：通过。
- 已重启服务，当前 PID：`90696`。
- `/api/devices/android/66J5T19411001963/files?path=/`：返回 200，并包含 `sdcard`、`system` 等根目录节点。
- `/api/devices/android/66J5T19411001963/files?path=/sdcard`：返回 200。
- 服务日志未发现 `ERROR`、`Exception`、`INTERNAL_ERROR`。
