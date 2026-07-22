# AI 助手流式闪烁与中断排查记录

## 排查步骤

- [x] 检查 8080 当前服务状态和 AI 配置状态，确认问题发生在已配置 GLM Provider 的运行环境。
- [x] 阅读 `AiChatPanel.tsx` 中加载态、流式刷新、自动滚动和过程时间线渲染逻辑。
- [x] 阅读 `aiApi.ts` 中 fetch SSE 解析逻辑，重点检查事件分隔、尾部 buffer 和 done 事件处理。
- [x] 阅读 `AiConversationService.java` 和 `SpringAiProviderGateway.java` 中 Provider 流转 SSE 的终止路径。
- [x] 前端构建、后端测试、后端打包，并用真实流式接口验证 `event:done` 返回。

## 假设与验证

| 假设 | 置信度 | 验证结果 |
| --- | --- | --- |
| `animate-ping` 扩散动画叠加毛玻璃背景造成整窗重绘闪烁 | 高 | 当前加载点使用扩散动画，且 AI 面板有全尺寸 `backdrop-blur` 静态层，流式期间动画持续触发合成重绘。 |
| 流式内容刷新过于频繁导致 Markdown 和消息树重复渲染 | 中 | 每 120ms 合并一次 chunk，长回答时会频繁触发当前消息 Markdown 解析和滚动状态检查。 |
| SSE 结尾 `done` 事件被客户端漏消费，导致误判“流式响应中断” | 高 | `TextDecoder` 使用 stream 模式但循环结束后未 flush，且 SSE 分隔只支持 `\n\n`，CRLF 或尾部字节缓存会让 `done` 滞留。 |
| 后端工具事件和模型 chunk 并发写同一 `SseEmitter` 导致事件交错 | 中 | 工具事件发布器和 Provider 流监听器可能来自不同线程，原实现没有按单连接串行化写入。 |
| Provider 最大输出 token 过低造成内容逻辑上未回复完整 | 中 | `CHAT_MAX_TOKENS` 原值 1200，复杂分析和表格输出容易被模型截断，即使 SSE 正常结束也会表现为回答不完整。 |

## 根因链路

闪烁问题来自 UI 合成和渲染压力叠加：AI 面板使用毛玻璃背景，`正在思考` 状态点近期改成了 `animate-ping` 扩散动画；同时流式阶段每 120ms 刷新消息内容，触发 Markdown 解析和滚动状态检查，导致 Electron/浏览器持续重绘 AI 面板区域。

中断问题来自 SSE 终止语义不够稳健：前端 fetch reader 在流结束后没有 flush `TextDecoder` 的尾部缓存，且事件分隔符只按 LF 处理。只要 `done` 事件落在尾部缓存或 CRLF 分隔场景中，前端就会认为连接关闭但未收到完成事件。后端侧工具事件和模型 chunk 也可能并发写入同一个 `SseEmitter`，存在事件顺序和写入稳定性风险。

## 修复方案

- 前端 SSE 读取结束后执行 `decoder.decode()` flush，并将 SSE 分隔解析改为兼容 LF/CRLF。
- 后端对同一个 `SseEmitter` 的模型 chunk、done/error 和工具事件发送加单连接同步，保证事件顺序和写入完整性。
- 将 AI 面板流式刷新间隔从 120ms 调整为 240ms，降低长回答期间的 Markdown 重解析频率。
- 将 `animate-ping` 扩散动画替换为只改变小圆点透明度的轻量动画，避免大面积毛玻璃重绘。
- 给普通消息气泡增加 `React.memo`，避免未变化的历史消息跟随当前流式回复重复渲染。
- 将普通对话最大输出 token 从 1200 提升到 3000，降低复杂分析回答被模型提前截断的概率。

## 验证

- `npm run build` 通过，前端构建版本：`Ai DevBridge V2026.7.0103`。
- `mvn test` 通过，97 个测试全部通过。
- `mvn package -DskipTests` 通过。
- 新服务已启动在 `http://127.0.0.1:8080/`。
- 使用 `/api/ai/chat/stream` 冒烟请求验证收到 `event:chunk` 和 `event:done`，终止事件正常返回。
