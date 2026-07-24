# Electron Markdown 流式渲染 OOM 排查记录

## 排查步骤

- [x] 根据 Electron OOM 堆栈映射生产 Bundle，定位到 `MarkdownContent` 的 `useMemo -> parseBlocks -> parseParagraph`。
- [x] 检查 SSE、工具输出和历史会话大小，确认单次业务数据不足以自然占满 4GB 堆。
- [x] 检查流式状态与 Markdown 分段生命周期，确认每次尾段刷新都会重建全部分段节点，并通过 `useDeferredValue` 累积并发渲染任务。
- [x] 修改为稳定分段子树复用，仅重绘流式尾段，并移除不适合持续高频更新的 deferred 队列。
- [x] 扩展 Electron 压力检查，覆盖 1M Token 规模下的持续流式更新。
- [x] 完成生产构建与 Electron Renderer 压测，确认修复后内存保持有界。

## 假设与验证

| 假设 | 验证结果 |
| --- | --- |
| SSE 单事件或未完成缓冲区无限增长 | 已有 500K/1M 字符边界，本次任务最终正文仅 1177 字符，不成立 |
| 工具输出直接撑满前端状态 | 工具输出和过程详情已有长度与条数上限，不是主要原因 |
| Markdown 流式并发渲染队列滞留 | OOM 栈精确落在 deferred Markdown 解析；实现会在每次刷新重建全部分段元素，高置信度成立 |

## 根因置信度与质疑

- 根因置信度：高（95%）。生产堆栈、React 更新模型和修复后的受限堆压测形成完整证据链。
- 需求层面：完整保留长回复和实时 Markdown 展示是合理需求，不应通过截断正文规避问题。
- 方案层面：已有分段与视口懒挂载方向正确，缺陷位于流式尾段更新时未隔离稳定历史子树。
- 逻辑层面：`useDeferredValue` 在持续高频更新中会保留待提交工作，且全量重建历史元素放大了滞留成本，应直接修复该更新边界。

## 根因链

SSE 与工具事件持续触发消息刷新 -> `MarkdownContent` 每次为全部稳定分段重新创建 React 元素 -> 流式尾段同时使用 `useDeferredValue` 排队低优先级解析 -> Renderer 在持续高优先级更新下无法及时提交和释放旧工作树 -> 老生代持续增长 -> 下一次 `parseParagraph().join()` 分配触发 V8 OOM。

## 修复方案

- 稳定 Markdown 分段独立为 memo 子树，仅在新增分段时更新。
- 流式尾段继续实时解析，但不再使用 deferred 队列；上游 80ms 刷新和 12K 尾段上限已经提供足够背压。
- 保持完整回复接收、实时 Markdown 和历史持久化能力不变，不通过截断业务内容规避内存问题。

## 验证结果

- `npm run test:ai-stability`：通过；400 万字符形成 668 个分段，最大分段 5997 字符，128 MB Node 堆下正常完成。
- `npm run build`：通过；Vite 生产构建完成。
- `npm run test:ai-electron-stability`：通过；3847 次持续流式更新后保留 400 万字符，仅挂载 17 个分段，Renderer 私有内存 345520 KB，低于 768 MB 验收上限。
