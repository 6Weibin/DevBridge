# 设备信息页截图功能实现记录

## 实施步骤

- [x] 检查前端 `ScreenshotPanel`，确认原实现为演示图片轮换，没有调用后端截图能力。
- [x] 检查后端设备、文件下载和命令执行结构，确认可复用 `AndroidDeviceService`、`ExecutableLocator`、统一业务错误模型和临时文件清理模式。
- [x] 增加 Android 截图服务方法，通过 `adb exec-out screencap -p` 直接输出 PNG 到服务端临时文件。
- [x] 增加 `/api/devices/{platform}/{serial}/screenshot` 接口，流式返回 `image/png`，响应完成后删除临时文件。
- [x] 修改前端截图面板，点击截图后请求后端接口，使用 Blob URL 预览并支持保存到本地。
- [x] 执行前端构建、后端测试、后端打包、服务重启和真机接口验证。

## 关键判断

### 为什么不继续使用演示图片

演示图片不能反映当前设备真实屏幕，会误导用户认为截图已经完成。截图面板必须接后端真实能力，后端不可用或平台不支持时显示明确错误。

### 为什么 Android 先实现

当前工程内置工具目录只包含 `adb`，没有内置 `idevicescreenshot` 或 `hdc` 截图工具。Android 截图命令可通过现有 ADB 工具稳定实现；iOS/Harmony 暂返回 `PLATFORM_UNSUPPORTED`，避免伪实现。

### 为什么使用 `exec-out`

`adb exec-out screencap -p` 可以直接把 PNG 二进制输出到后端临时文件，不需要 shell 重定向，也不需要在手机端创建临时截图文件，安全边界更清晰。

## 修复方案

- 后端 `AndroidDeviceService.captureScreenshot(serial)`：
  - 校验设备连接状态。
  - 创建服务端临时 PNG 文件。
  - 使用参数数组执行 `adb -s serial exec-out screencap -p`。
  - 二进制输出直接落盘，避免文本读取破坏 PNG。
  - 命令失败、超时或空文件时返回统一业务错误。
- 后端 `DeviceController.screenshot(...)`：
  - Android 返回 `image/png`。
  - 非 Android 返回 `PLATFORM_UNSUPPORTED`。
  - 响应结束后删除临时文件。
- 前端 `ScreenshotPanel`：
  - 移除演示图片轮换。
  - 调用截图接口生成预览 Blob URL。
  - 切换设备或替换截图时释放旧 Blob URL。
  - 支持保存当前截图。

## 验证结果

- `DevBridge-Front` 执行 `npx -y yarn@1.22.22 build` 通过。
- `DevBridge-Server` 执行 `mvn test` 通过：24 个测试全部通过。
- `DevBridge-Server` 执行 `mvn package -DskipTests` 通过。
- 后端 jar 已重新启动到 `http://127.0.0.1:8080/`。
- 首页返回 200，并加载新资源 `/assets/index-C7jwySUZ.js`。
- Android 真机截图接口返回 `200 image/png`，文件为 `PNG image data, 1080 x 2340`。
- iOS 截图接口返回 `400 PLATFORM_UNSUPPORTED`，错误语义符合当前工具边界。

## 限制

当前只实现 Android 截图。iOS 需要引入并验证 `idevicescreenshot`，HarmonyOS 需要确认可用的 hdc 截图命令后再接入。
