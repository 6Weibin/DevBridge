# 日志类型切换后页面像刷新排查记录

## 问题现象

- 实时日志采集中切换日志级别后，界面显示不到 1000 行时就出现类似整页刷新的体验。
- 工具错误输出可能触发前端 `EventSource.onerror`，导致采集状态被关闭。

## 排查步骤

- 搜索前端日志状态、级别过滤、SSE 连接和刷新逻辑。
- 阅读 `DevBridge-Front/src/app/App.tsx` 中日志缓存、过滤、渲染和 `EventSource` 处理。
- 阅读 `DevBridge-Server/src/main/java/com/devbridge/server/service/LogStreamService.java` 中 SSE 事件发送逻辑。
- 对比运行产物 `dist` 和后端 `target/classes/static`，确认当前包内也存在相同日志裁剪逻辑。

## 假设与验证

| 假设 | 验证结果 | 结论 |
| --- | --- | --- |
| 浏览器显式执行整页 reload | 未发现 `location.reload` 或同类调用 | 排除 |
| 原始日志达到 1000 行后裁剪导致过滤结果不足 | 前端原逻辑使用 `current.slice(-999)` 裁剪原始日志，级别过滤在裁剪后执行 | 确认 |
| 表格一次性渲染过多日志导致卡顿 | 前端对全部过滤结果 `map` 渲染，无单独渲染窗口 | 确认 |
| 工具 stderr 被误判为 SSE 连接错误 | 后端发送 `event: error`，前端同时绑定业务 `error` 和 `source.onerror` | 确认 |

## 根因链路

1. SSE 收到每条日志后写入 `logs`。
2. 原逻辑只保留原始日志最近 1000 行。
3. 用户切换日志级别时，前端再基于这 1000 条原始日志过滤。
4. 如果目标级别在原始日志中占比低，界面会在远少于 1000 行时丢掉旧的目标级别日志。
5. 同时表格按过滤结果全量渲染，日志高频进入时容易造成明显重绘。
6. 后端工具错误事件使用 `error` 名称，与浏览器 SSE 连接错误语义冲突，可能进一步导致前端关闭日志流。

## 修复方案

- 前端新增原始日志缓存上限和渲染上限：
  - 原始缓存保留 5000 行，提升切换低频级别时的可筛选历史。
  - 界面只渲染过滤后的最后 1000 行，降低表格重绘压力。
- 前端启动 SSE 时不把当前级别和文本过滤固化到后端查询中，保证切换级别不需要重建连接或清空日志。
- 后端将工具 stderr 事件从 `error` 改为 `tool-error`，前端监听该业务事件，避免误触发 `EventSource.onerror`。
- 新增后端测试锁定 SSE 事件名约定。

## 验证结果

- `DevBridge-Front` 执行 `npx -y yarn@1.22.22 build` 通过。
- `DevBridge-Server` 执行 `mvn test -Dtest=LogStreamServiceTest` 通过，3 个测试全部通过。
- `DevBridge-Server` 执行 `mvn test` 通过，19 个测试全部通过。
