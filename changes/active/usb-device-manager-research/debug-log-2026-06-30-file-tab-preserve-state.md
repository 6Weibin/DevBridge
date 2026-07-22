# 文件管理页签切换重置排查记录

> 日期：2026-06-30  
> 类型：bugfix  
> by AI.Coding

## 排查步骤

- [x] 检查前端页签切换逻辑，定位到文件页 `useEffect`。
- [x] 验证该 effect 在每次切回 `files` 页签时都会调用 `loadFiles("/")`。
- [x] 检查 `loadFiles` 行为，确认它会重置 `remoteFiles`、`filePath` 和当前选中文件。
- [x] 调整为同一设备内只首次自动加载，后续页签切换保留当前文件浏览现场。

## 根因定位链

1. 用户在文件管理中进入某个目录或选中文件。
2. 切换到实时日志后，`tab` 状态变化。
3. 再切回文件管理时，旧 effect 因 `tab === "files"` 再次执行。
4. effect 调用 `loadFiles("/")`，覆盖当前文件树并清空选中文件。
5. 用户必须重新从根目录逐级查找文件。

## 修复方案

- 新增 `loadedFileDeviceIdRef` 记录当前设备是否已完成文件树初始化。
- 文件页只在“当前 Android 设备首次进入文件管理”时自动加载根目录。
- 同一设备内切换日志/文件页签不再重新请求和覆盖文件树。
- 切换到另一台设备时仍会重新初始化文件树，避免展示错误设备的文件。

## 验证结果

- `./node_modules/.bin/vite build`：通过。
- `mvn package -DskipTests`：通过。
- `mvn test`：通过，17 个测试全部成功。
- 8080 服务已重启，首页引用新资源 `/assets/index-B2mYervz.js`。
