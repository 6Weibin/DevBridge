# AI 对话流式与 Markdown 渲染排查记录

> 日期：2026-07-06
> 类型：体验缺陷修复

## 排查步骤

- [x] 检查前端 AI 对话调用链：`AiChatPanel.tsx` 通过 `chat()` 调用一次性 JSON 接口。
- [x] 检查后端 AI 对话接口：`AiController` 仅暴露 `/api/ai/chat`，`AiConversationService` 只调用 `providerGateway.chat()`。
- [x] 检查 Spring AI 本地依赖：`ChatClient.StreamResponseSpec` 支持 `content()` 返回 `Flux<String>`。
- [x] 检查前端渲染：AI 消息统一使用 `<pre>`，因此 Markdown 会按源码展示。
- [x] 验证修复：执行后端 `mvn -q test` 和前端 `npm run build`。

## 假设与验证

| 假设 | 置信度 | 验证结果 |
|------|--------|----------|
| AI 无流式体验是因为前后端只实现一次性 JSON 对话接口 | 高 | 已确认，普通对话调用 `/api/ai/chat`，没有流式端点 |
| Markdown 源码展示是因为前端没有 Markdown 渲染层 | 高 | 已确认，消息内容直接放入 `<pre>` |
| Spring AI 版本支持流式输出 | 高 | 已用本地 `javap` 确认 `stream().content()` 存在 |

## 根因定位链

用户症状：AI 回复等待时间长，且 Markdown 显示为源码。

实现链路：`AiChatPanel.sendMessage()` → `aiApi.chat()` → `/api/ai/chat` → `AiConversationService.chat()` → `AiProviderGateway.chat()` → 一次性返回完整文本。

渲染链路：AI 回复内容 → `messages` → `<pre>{message.content}</pre>`。

根因：第一版只满足“请求处理中展示状态和最终回复”的 P0 验收，没有实现流式传输；同时为了保留换行直接使用 `<pre>`，没有 Markdown 解析。

## 修复方案

1. 后端新增 `/api/ai/chat/stream`，使用 `SseEmitter` 输出 `chunk/done/error` 事件。
2. Provider 层新增 `AiProviderStreamListener` 和 `AiProviderStreamHandle`，隔离 Spring AI `Flux` 细节。
3. 前端新增 `chatStream()`，通过 `fetch` 读取 POST SSE 并逐段追加到同一条 assistant 消息。
4. 前端新增 `MarkdownContent`，用 React 节点解析标题、列表、引用、代码块、链接、粗体和行内代码，避免 HTML 注入。
5. 保留原 `/api/ai/chat` JSON 接口，避免破坏已有调用契约。

## 验证结果

- `DevBridge-Server`: `mvn -q test` 通过
- `DevBridge-Front`: `npm run build` 通过
- 前端构建版本：`DevBridge V2026.7.0016`
