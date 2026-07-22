# 媒体文件预览排查与实现记录

> 日期：2026-06-30  
> 类型：feature  
> by AI.Coding

## 需求目标

- 文件管理选中图片文件时，右侧详情区域展示图片预览。
- 文件管理选中视频文件时，右侧详情区域展示播放器控件。
- 视频必须由用户点击播放后才开始播放，不允许选中文件后自动播放或自动预加载。

## 实现方案

- 后端新增 `/api/devices/{platform}/{serial}/files/preview` 接口。
- 预览接口复用现有 Android 受控拉取能力，仍经过路径校验和文件大小限制。
- 后端根据文件扩展名返回图片或视频 `Content-Type`，并使用 `Content-Disposition: inline`。
- 前端根据文件名和 MIME 判断图片、视频类型，只对 Android 在线设备渲染预览区域。
- 视频播放器使用 `controls` 和 `preload="none"`，不设置 `autoPlay`。

## 验证结果

- `mvn test`：通过，17 个测试全部成功。
- `./node_modules/.bin/vite build`：通过，生成 `/assets/index-CQ4NpTIg.js` 和 `/assets/index-KcCNF6ul.css`。
- `mvn package -DskipTests`：通过，后端 jar 已包含最新前端静态资源。
- 8080 服务已重启，首页引用新资源 `/assets/index-CQ4NpTIg.js`。
- 真实 Android 设备 `EMH0223511000196` 图片预览验证通过：
  - 路径：`/sdcard/Pictures/Image.1718176196665.gif`
  - 响应：`HTTP/1.1 200`
  - 响应头：`Content-Disposition: inline; filename="Image.1718176196665.gif"`
  - 响应头：`Content-Type: image/gif`
  - 文件大小：`67315` 字节
- 真实 Android 设备 `EMH0223511000196` 视频预览验证通过：
  - 路径：`/sdcard/DCIM/Camera/VID_20240823_093908.mp4`
  - 响应：`HTTP/1.1 200`
  - 响应头：`Content-Disposition: inline; filename="VID_20240823_093908.mp4"`
  - 响应头：`Content-Type: video/mp4`
  - 文件大小：`5714836` 字节

## 已知边界

- 当前预览接口采用先拉取到服务端临时文件再流式返回的方式，适合 MVP 验证和中小文件预览。
- 大视频的秒开、拖动进度条和分段加载后续可通过 HTTP Range 支持优化。
