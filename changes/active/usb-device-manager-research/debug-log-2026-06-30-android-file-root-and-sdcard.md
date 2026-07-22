# Android 文件管理根目录与 sdcard 显示排查记录

> 日期：2026-06-30  
> 类型：bugfix / delta  
> by AI.Coding

## 排查步骤

- [x] 检查后端路径校验器，确认旧实现只允许 `/sdcard` 与 `/storage/emulated/0`。
- [x] 检查前端文件页，确认默认文件路径写死为 `/sdcard`，重新进入文件页也会加载 `/sdcard`。
- [x] 请求真实接口 `/files?path=/sdcard`，复现只返回 `/sdcard -> /storage/self/primary` 符号链接的问题。
- [x] 请求真实接口 `/files?path=/storage/emulated/0`，确认真实公共存储内容可正常读取。
- [x] 检查文件解析器，确认绝对路径形式的符号链接名会拼出异常路径，并且 `l` 类型会被当成文件，导致前端不能进入。

## 假设与验证

| 假设 | 验证结果 |
|------|----------|
| 只能读取 sdcard 是 adb 能力限制 | 不成立。adb 可以读取 `/` 下 shell 可见目录。 |
| 不能显示 sdcard 是手机无数据 | 不成立。`/storage/emulated/0` 能返回真实文件列表。 |
| sdcard 显示异常是符号链接未跟随 | 成立。`ls -la /sdcard` 返回符号链接自身，追加尾斜杠后能进入目录。 |
| 根目录不能读取是后端白名单拒绝 | 成立。旧路径校验明确拒绝 `/`。 |

## 根因定位链

1. MVP 旧需求把 Android 文件管理限制为公共存储目录。
2. 后端 `AndroidPathGuard` 按旧安全边界拒绝 `/`，所以无法读取整机可见文件树。
3. 前端默认路径写死 `/sdcard`，无法展示根目录入口。
4. Android 上 `/sdcard` 常见为符号链接；旧后端执行 `ls -la /sdcard` 时拿到链接自身，不是目标目录内容。
5. 解析器把 `l` 权限位识别为文件，导致根目录中的 `sdcard` 在前端不可导航。

## 修复方案

- 后端路径校验改为允许安全的绝对路径，继续拒绝空路径、相对路径、`..` 和控制字符。
- 后端列目录时对非根目录追加尾斜杠，用于跟随 `/sdcard` 这类符号链接目录。
- 解析器清理绝对路径形式的符号链接名称，避免生成 `//sdcard`。
- 解析器将符号链接作为可导航节点处理；如果目标不可进入，adb 会返回权限或路径错误。
- 前端文件管理默认路径从 `/sdcard` 改为 `/`，返回上级逻辑以 `/` 为边界。

## 验证结果

- `mvn test`：通过，17 个测试全部成功。
- `./node_modules/.bin/vite build`：通过。
- `mvn package -DskipTests`：通过。
- 重启 8080 服务后验证：
  - `/api/devices/android/EMH0223511000196/files?path=/` 返回根目录，包含 `sdcard` 且 `type=dir`。
  - `/api/devices/android/EMH0223511000196/files?path=/sdcard` 返回真实公共存储文件列表，包含 `DCIM`、`Download`、`Android` 等目录。

## 说明

非 root Android 设备仍然不能保证读取所有系统目录和应用私有目录。现在的实现会展示 adb shell 可见的整机目录；进入无权限目录时，接口返回“远端目录不存在或不可访问”。
