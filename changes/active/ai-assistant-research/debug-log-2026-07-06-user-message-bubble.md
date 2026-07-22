# AI 用户消息气泡样式回归排查记录

## 排查步骤

- 确认症状：用户消息预期为浅灰色右侧气泡，实际显示为无背景、无气泡。
- 阅读文件：`DevBridge-Front/src/app/ai/AiChatPanel.tsx`。
- 验证假设：原实现把消息行布局和气泡样式混在同一个 `div`，AI 回复“无背景”调整后，普通消息的对齐和气泡职责不清晰。
- 修复位置：仅调整 `AiChatPanel.tsx` 的普通文本消息渲染，不改消息结构、接口、ADB MCP 或 AI 请求逻辑。

## 根因定位

用户消息、系统消息和 AI 回复共用同一层容器，通过条件 class 同时承担“左右布局”和“气泡样式”。当 AI 回复改成无背景直接输出后，这层结构无法明确区分行容器和气泡本体，导致用户消息样式表现不稳定。

## 修复方案

- 新增 `AiMessageBubble` 局部组件。
- 外层 `div` 只负责布局：用户消息 `justify-end`，AI/系统消息 `justify-start`。
- 内层 `div` 才负责视觉样式：用户消息使用浅灰色背景、圆角、细边线和阴影；AI 回复不加背景，直接渲染 Markdown。

## 验证结果

- `npm run build` 通过。
- `mvn process-resources` 通过，前端构建产物已同步到后端 `target/classes/static`。
- 浏览器实际验证用户消息“样式测试”：
  - 背景色：`rgb(242, 243, 245)`
  - 行布局：`justify-content: flex-end`
  - 右侧间距：`0`
  - 气泡类：`max-w-[84%] rounded-xl bg-[#f2f3f5] ...`

## 影响范围

本次只影响 AI 侧边栏普通文本消息展示。工具卡片、过程卡片、Markdown 解析、AI 请求和 ADB MCP 调用链未改动。
