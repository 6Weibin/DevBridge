# AI 助手不透明面板 UI 调整记录

## 调整背景

用户反馈移除毛玻璃后页面视觉效果不理想，同时半透明效果本身会造成底层页面干扰，因此本次改为完全不透明的 AI 助手面板风格。

## 调整范围

- AI 助手外层面板从半透明渐变改为不透明 `bg-background`。
- 顶部状态栏、历史聊天栏、输入区、设备标签、提示条、回到底部按钮全部改为不透明 `bg-card`、`bg-muted`、`bg-background` 或明确的告警色。
- 用户消息气泡、错误消息、思考与执行过程块、工具调用卡片、确认卡片和 Markdown 代码块改为不透明背景。
- 保留边框、间距和阴影层次，避免界面变成完全平铺。

## 验证

- `npm run build` 通过，前端构建版本：`Ai DevBridge V2026.7.0106`。
- `mvn package -DskipTests` 通过。
- 新服务已启动在 `http://127.0.0.1:8080/`，当前 PID：`62831`。
- 首页已引用新资源：`/assets/index-CbGrhLxS.js` 和 `/assets/index-EzXWIsgm.css`。
