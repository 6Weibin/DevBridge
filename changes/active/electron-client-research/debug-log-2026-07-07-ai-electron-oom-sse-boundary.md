# AI 助手 Electron OOM 二次排查记录

## 排查步骤
- [x] 复核用户新日志：仍为 `V8 javascript OOM`，说明问题继续发生在 Electron/Chromium JavaScript 堆。
- [x] 验证 Electron 最新产物：`index-Cs7t2o13.js` 已包含上一轮消息状态和 Markdown 截断保护，问题不是旧资源。
- [x] 检查前端 SSE 读取器：`aiApi.ts` 原实现对未完成 buffer、单个 SSE 事件、总流式文本都没有硬上限。
- [x] 检查后端 AI 流式输出：`AiConversationService` 原实现会直接下发 Provider 增量文本，并在工具后无最终回复时把工具输出拼成兜底 Markdown。
- [x] 检查 ADB 流式工具输出：`AdbOutputSanitizer.sanitizeLine` 原实现只脱敏，不限制异常长单行。
- [x] 完成前后端双层修复：传输层、后端下发层、工具事件层都加入内存边界。
- [x] 构建并验证 Electron 实际启动链路：`15173` 前端和 `18180` 后端均加载 `V2026.7.0075`。

## 假设与验证
- 假设 1：上一轮修复没有进入 Electron 包。验证结果：Electron bundle 包含截断文案且 API 指向 `18180`，排除。
- 假设 2：React 消息状态仍无限增长。验证结果：状态层已有上限，但 SSE JSON 解析和原始 buffer 发生在状态写入前，仍可产生内存峰值。
- 假设 3：单个 SSE 事件或后端兜底回复过大导致 V8 解析/复制峰值。验证结果：前端 `readEventStream` 和后端 `send` 均无事件级边界，置信度高。
- 假设 4：非 Electron 浏览器完全不受影响。验证结果：浏览器模式也使用同一 SSE 解析逻辑，只是 Chrome 独立进程更不容易复现，风险仍存在。

## 根因定位链
上一轮修复限制的是“进入 React 状态后的消息”和“Markdown DOM 渲染”。但流式响应在进入 React 之前，还会先经过 fetch reader、字符串 buffer、SSE block 切分、JSON.parse 和 chunk 回调。如果 Provider 或后端兜底路径产生异常大的单个事件，Electron 渲染进程会在 JSON.parse 或字符串复制阶段先达到 V8 堆上限，状态层截断还没机会生效。

## 修复方案
- `aiApi.ts`：增加未完成 SSE buffer 上限、单个 SSE 事件上限、总流式文本上限；超限时中断并返回明确错误，或追加截断提示。
- `AiConversationService.java`：限制服务端下发的总文本量，按 8K 分片发送 SSE chunk；工具兜底输出和工具事件 stdout/stderr 只下发摘要级内容。
- `AdbOutputSanitizer.java`：限制流式工具单行输出，避免异常长单行形成超大 SSE 事件。

## 验证结果
- `npx -y yarn@1.22.22 build` 通过，版本 `V2026.7.0075`。
- `mvn -DskipTests package` 通过，新的后端 jar 已生成。
- `node scripts/prepare-resources.js` 通过，Electron 前端资源已改写 API 到 `18180`。
- Electron 启动验证通过：`15173` 返回 `index-B7LzQbBl.js`，`18180/api/runtime/environment` 返回 `V2026.7.0075`。
- 临时 Electron 进程已关闭，`15173/18180` 不再响应。
