# Electron 开发启动被 macOS 拦截排查记录

## 排查步骤

- [x] 复现 `npm start` 启动后 `Electron.app` 被 XProtect 删除的问题。
- [x] 检查 Electron 版本、下载镜像、SHA-256 校验和 macOS 签名状态。
- [x] 升级 Electron 与 electron-builder，并统一打包版本。
- [x] 增加开发运行时下载与 macOS 本地签名准备脚本。
- [x] 验证 `npm start`、后端端口和正式目录打包。

## 根因

项目仍使用已停止维护的 Electron 31.7.7。macOS 对解压后的通用开发 `Electron.app` 判定为签名结构无效，启动时由 XProtect 阻断并删除。正式 `Ai DevBridge.app` 经过 electron-builder 重新签名，因此此前只有开发启动受影响。

Electron 43 的安装器还不再直接使用旧 `.npmrc` 镜像字段，GitHub 下载不可达时会留下缺失二进制，需要由项目启动脚本显式传递镜像。

## 修复

- Electron 升级到 43.2.0，electron-builder 升级到 26.15.3。
- `build.electronVersion` 与开发依赖保持一致。
- `postinstall` 和 `prestart` 统一调用 `prepare-electron-runtime.js`。
- 二进制缺失时调用 Electron 官方安装器并保留内置 SHA-256 校验。
- macOS 签名结构无效时，只对已校验的本地开发依赖执行 ad-hoc 重签名并再次验证。

## 验证结果

- `npm start` 正常运行，开发 Electron 进程持续存活。
- 后端 `http://127.0.0.1:18180/` 返回 200。
- `electron-builder --dir` 构建成功，正式 App 代码签名校验通过。
- `npm audit --omit=dev` 返回 0 个漏洞。
