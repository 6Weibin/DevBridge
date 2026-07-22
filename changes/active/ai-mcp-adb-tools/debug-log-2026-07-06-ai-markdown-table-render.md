# AI Markdown 表格未解析排查记录

## 排查步骤

- [x] 检查 AI 消息渲染链路，确认 assistant 回复已进入 `MarkdownContent`。
- [x] 检查 `MarkdownContent` 支持的块级语法，确认只覆盖标题、段落、列表、代码块和引用。
- [x] 对照 ADB MCP 兜底回复格式，确认最终回复使用 Markdown 表格。
- [x] 修复表格解析，并同步调整对话气泡视觉样式。

## 根因

AI 回复没有完全按 Markdown 渲染的根因不是渲染入口错误，而是 Markdown 解析器缺少表格语法支持。ADB MCP 应用列表回复输出 `| 包名 | 应用判断 | 依据 |` 这类表格时，被当成普通段落显示，所以用户看到 Markdown 源码。

## 修复

- `MarkdownContent.tsx` 新增 Markdown 表格识别、解析和渲染。
- 表格容器支持横向滚动，避免大量列撑破 AI 侧边栏。
- `AiChatPanel.tsx` 调整消息样式：
  - 用户消息改为浅灰色气泡。
  - AI 回复取消背景，直接显示正文内容。

## 验证

- `npm run build` 通过。
- 构建版本：`V2026.7.0028`。
