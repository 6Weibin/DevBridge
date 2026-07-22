# AI 历史对话功能实现记录

## 需求范围

- AI 助手侧边栏左侧增加历史对话区域。
- 历史区域顶部提供“新对话”按钮。
- 历史列表展示自动生成的对话标题。
- 点击历史对话后加载该对话内容，并能继续基于历史上下文追问。
- 重开应用后保留之前的 AI 对话内容。

## 实现方案

- 在 `AiChatPanel.tsx` 内新增本地会话模型：
  - `id`
  - `title`
  - `messages`
  - `createdAt`
  - `updatedAt`
- 使用 `localStorage` 保存最近 30 个 AI 会话，存储 key 为 `devbridge.ai.conversations.v1`。
- 标题默认由第一条用户消息生成；没有用户消息时显示“新对话”。
- 历史会话加载后复用原有 `messages` 渲染逻辑，因此已实现的多轮上下文 `buildChatHistory(messages)` 会自然接续历史对话。
- 加载中禁止新建/切换会话，避免 SSE 流式输出或 ADB MCP 工具确认写入错误会话。
- 历史数据异常时自动降级创建新会话，避免 AI 面板无法打开。

## 验证结果

- `npx -y yarn@1.22.22 build`：通过，生成 `DevBridge V2026.7.0061`。
- `mvn -DskipTests resources:resources`：通过，前端静态资源已同步到后端。
- 重启后 `http://127.0.0.1:8080/` 返回 `200`。
- 页面加载新资源：`index-D7i9DBOt.js`、`index-DO2iQzss.css`。
- 浏览器验证：
  - AI 面板宽度为 `760px`。
  - 左侧历史栏存在。
  - “新对话”按钮存在。
  - “历史对话”列表标题存在。

