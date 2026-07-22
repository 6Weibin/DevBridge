# AI 助手 Electron OOM 崩溃排查记录

## 排查步骤
- [x] 分析 Electron 崩溃日志：确认 `V8 javascript OOM` 发生在 Chromium/Electron 渲染进程，不是 Spring Boot 后端内存溢出。
- [x] 检查 Electron 主进程：`DevBridge-Electron/src/main.js` 只做前端静态服务、API 端口桥接和后端进程管理，没有把 AI 大文本通过 IPC 复制。
- [x] 检查实时日志链路：`DevBridge-Front/src/app/App.tsx` 已对日志桶做 `LOG_RENDER_LIMIT` 限制，实时日志不是主要风险点。
- [x] 检查 AI 对话链路：定位到 `AiChatPanel.tsx` 的流式消息、历史会话同步、消息签名和 localStorage 持久化会反复复制完整长文本。
- [x] 检查 Markdown 渲染：`MarkdownContent.tsx` 超过解析上限后仍完整渲染 `<pre>`，超大文本节点仍会占用大量 V8/DOM 内存。
- [x] 完成修复并构建：前端构建通过，浏览器服务静态资源和 Electron 前端资源均包含 `V2026.7.0073`。
- [x] Electron 启动验证：启动 Electron 后确认主窗口实际加载 `15173` 前端，bundle API 指向 `18180`，且包含本次截断保护。

## 假设与验证
- 假设 1：Electron 后端或 ADB 命令导致 OOM。验证结果：崩溃日志是 V8 OOM，后端无对应 JVM OOM 信息，置信度低。
- 假设 2：实时日志无限增长导致 OOM。验证结果：日志桶已有固定上限，且用户问题出现在 AI 大回复场景，置信度中低。
- 假设 3：AI 长回复和工具输出在前端状态、历史会话和 localStorage 中被反复复制导致 OOM。验证结果：代码存在完整内容签名、restore、JSON.stringify 和流式阶段高频同步，置信度高。
- 假设 4：非 Electron 浏览器不会出现。验证结果：同一套前端逻辑在浏览器模式也存在，只是 Chrome 独立进程和内存策略下不一定同样快触发，置信度高。

## 根因定位链
用户通过 AI 触发长回复或 ADB MCP 大输出后，前端将内容持续追加到 React 消息状态。消息变化会触发历史会话同步，旧实现会恢复完整消息、用完整内容生成签名，并把所有会话完整 JSON 序列化到 localStorage。流式阶段这个过程会重复发生，导致大量大字符串副本、JSON 副本和 DOM 文本节点同时驻留。Electron 渲染进程 V8 堆到达约 4GB 后触发 `Ineffective mark-compacts near heap limit` 并崩溃。

## 修复方案
- 当前消息状态增加内容上限：AI 文本、工具 stdout/stderr、过程详情和过程条目都有明确边界。
- 流式阶段暂停历史会话同步：请求完成后再保存最终的有界消息，避免每 250ms 复制和序列化完整长文本。
- 历史持久化使用压缩副本：只保存最近消息和摘要级内容，并限制 localStorage payload 总体大小。
- 会话签名改为轻量采样：使用角色、类型、长度、头尾采样和状态，不再拼接完整内容。
- Markdown 轻量模式也限制 DOM 文本体积：超出解析上限时继续显示安全长度内容，避免 `<pre>` 完整大文本导致渲染进程 OOM。

## 验证结果
- `npx -y yarn@1.22.22 build` 通过。
- `mvn -DskipTests resources:resources` 通过，并同步到服务端静态目录。
- `node scripts/prepare-resources.js` 通过，并生成 Electron 前端资源。
- 8080 浏览器模式返回 `V2026.7.0073` bundle，并包含 OOM 截断保护。
- Electron 启动后 `15173` 返回 `V2026.7.0073` bundle，且 API 地址已改写为 `18180`。
