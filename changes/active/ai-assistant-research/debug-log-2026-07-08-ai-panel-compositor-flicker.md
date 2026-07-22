# AI 助手面板合成闪烁排查记录

## 排查步骤

- [x] 复核用户反馈：上次修复后，`正在思考` 和流式回答阶段仍出现 AI 助手窗口闪烁。
- [x] 搜索 AI 面板内的持续动画、`backdrop-blur`、自动滚动和强制布局读取点。
- [x] 阅读 `AiChatPanel.tsx`，确认实时变化路径包含流式消息刷新、自动滚动、思考状态和过程时间线。
- [x] 阅读 `AiToolCallCard.tsx`，确认 MCP 工具运行态仍有旋转动画。
- [x] 修复后执行前端构建、后端相关测试、后端打包并重启 8080。

## 假设与验证

| 假设 | 置信度 | 验证结果 |
| --- | --- | --- |
| `animate-pulse` 和工具卡片 `animate-spin` 仍触发半透明面板重绘 | 高 | AI 面板可见路径仍有持续 CSS 动画，Electron 对半透明/阴影区域的合成更敏感。 |
| 整窗 `backdrop-blur-2xl` 是主要闪烁放大器 | 高 | 背景层覆盖整个 AI 面板，流式内容每次变化都会叠加半透明绘制和 backdrop-filter 合成。 |
| 自动滚动每个 chunk 后再读取滚动状态造成强制布局 | 中 | 粘底模式下每次内容刷新都会 `scrollTo` 后读取 `scrollHeight/scrollTop/clientHeight`，容易放大视觉抖动。 |
| Markdown 解析本身导致整窗闪烁 | 低 | 解析会增加渲染压力，但不会单独造成整窗级闪烁；根因仍是动画/毛玻璃/滚动布局叠加。 |

## 根因链路

前一次修复只移除了最明显的扩散动画，但 AI 面板里仍存在 `animate-pulse`、工具运行态 `animate-spin`，并且整窗背景仍使用 `backdrop-blur-2xl`。流式回答期间消息内容持续增长，React 会频繁更新聊天 DOM；这些动态内容绘制在大面积 backdrop-filter 背景之上时，Chromium/Electron 会反复重建合成层，视觉上表现为整个 AI 助手窗口闪烁。自动滚动再叠加强制布局读取，会进一步放大这个现象。

## 修复方案

- 移除 AI 面板可见路径中的持续 CSS 动画，思考状态和工具运行态改为静态状态点。
- 将整窗真实 `backdrop-filter` 改为稳定的半透明渐变材质层，保留轻量玻璃质感但不再触发 backdrop 合成闪烁。
- 将聊天滚动区提升为独立绘制层，并使用 `contain: paint` 限制流式内容重绘范围。
- 粘底自动滚动改为只写 `scrollTop`，不在每个流式刷新后立刻读取滚动状态；用户手动滚动时再计算是否在底部。

## 验证

- `npm run build` 通过，前端构建版本：`Ai DevBridge V2026.7.0105`。
- `mvn test -Dtest=AiConversationServiceTest` 通过。
- `mvn package -DskipTests` 通过。
- 新服务已启动在 `http://127.0.0.1:8080/`，当前 PID：`57207`。
- 首页已引用新资源：`/assets/index-Cm4h_86v.js` 和 `/assets/index-DkAWKBc4.css`。
