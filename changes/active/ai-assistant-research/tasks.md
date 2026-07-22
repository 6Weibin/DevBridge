# 任务清单

> 来源: design.md
> 生成时间: 2026-07-06

## 实施任务

- [x] 【配置模型】(后端) 新增 AI 配置模型和配置目录
  - 目标: 扩展后端配置，使 AI 配置在开发态和 Electron 打包态都有独立目录。
  - 涉及文件: `DevBridge-Server/pom.xml`, `DevBridge-Server/src/main/java/com/devbridge/server/config/DevBridgeProperties.java`, `DevBridge-Server/src/main/resources/application.yml`, `DevBridge-Electron/src/main.js`
  - 预期结果: 后端可读取 `devbridge.ai-config-root`，Electron 启动时把 AI 配置目录指向 userData。
- [x] 【配置存储】(后端) 实现 AI 配置加密保存和状态脱敏 `← depends: 【配置模型】`
  - 目标: 保存 Provider、API URL、模型和加密 API Key，状态接口不回显密钥。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/config/*`, `DevBridge-Server/src/test/java/com/devbridge/server/ai/config/*`
  - 预期结果: 配置可保存、读取、更新；状态返回脱敏信息；单测覆盖密钥不明文落盘。
- [x] 【AI框架】(后端) 引入 Spring AI 并建立 Gateway 边界 `← depends: 【配置模型】`
  - 目标: 引入 Spring AI 依赖并通过 `AiProviderGateway` 隔离框架细节，预留 tool/rag/observation 边界。
  - 涉及文件: `DevBridge-Server/pom.xml`, `DevBridge-Server/src/main/java/com/devbridge/server/ai/provider/*`, `DevBridge-Server/src/main/java/com/devbridge/server/ai/tool/*`, `DevBridge-Server/src/main/java/com/devbridge/server/ai/rag/*`, `DevBridge-Server/src/main/java/com/devbridge/server/ai/observation/*`
  - 预期结果: 业务服务只依赖 Gateway 接口，不直接依赖 Spring AI 具体实现。
- [x] 【Provider】(后端) 基于 Spring AI 实现对话调用和错误映射 `← depends: 【配置存储】, 【AI框架】`
  - 目标: 基于 Spring AI ChatClient/OpenAI-compatible 模型调用普通文本对话，并映射 Provider 错误。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/provider/*`, `DevBridge-Server/src/test/java/com/devbridge/server/ai/provider/*`
  - 预期结果: Provider 成功、鉴权失败、限流、超时、格式不兼容路径有稳定错误码。
- [x] 【AI接口】(后端) 实现配置、连接测试、普通对话 REST 接口 `← depends: 【配置存储】, 【Provider】`
  - 目标: 新增 `/api/ai/**` 配置状态、保存、测试和普通对话接口。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/api/AiController.java`, `DevBridge-Server/src/main/java/com/devbridge/server/ai/conversation/*`, `DevBridge-Server/src/test/java/com/devbridge/server/api/*`
  - 预期结果: 前端可通过统一 `ApiError` 识别 AI 未配置、配置错误和 Provider 错误。
- [x] 【脱敏分析】(后端) 实现日志脱敏和日志分析服务 `← depends: 【Provider】`
  - 目标: 对日志、设备序列号和 Provider 错误脱敏，限制日志上下文并返回结构化分析结果。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/analysis/*`, `DevBridge-Server/src/main/java/com/devbridge/server/ai/security/*`, `DevBridge-Server/src/test/java/com/devbridge/server/ai/analysis/*`, `DevBridge-Server/src/test/java/com/devbridge/server/ai/security/*`
  - 预期结果: 空日志、平台不支持、截断、脱敏规则都有测试覆盖。
- [x] 【AI入口】(前端) 实现浮动入口和右侧侧边栏容器
  - 目标: 在主界面右下角挂载 AI Shell，关闭后主界面状态保持不变。
  - 涉及文件: `DevBridge-Front/src/app/App.tsx`, `DevBridge-Front/src/app/ai/AiAssistantShell.tsx`, `DevBridge-Front/src/app/ai/aiTypes.ts`
  - 预期结果: AI 入口固定可见，未配置时打开配置弹窗，已配置时打开右侧侧边栏。
- [x] 【AI配置】(前端) 实现配置弹窗和连接测试 `← depends: 【AI接口】`
  - 目标: 支持 Provider、API URL、API Key、模型输入，保存后不回显 API Key。
  - 涉及文件: `DevBridge-Front/src/app/ai/AiConfigDialog.tsx`, `DevBridge-Front/src/app/ai/aiApi.ts`
  - 预期结果: 必填校验、连接测试、保存配置和后端错误展示可用。
- [x] 【AI对话】(前端) 实现普通对话和取消当前请求 `← depends: 【AI接口】, 【AI入口】`
  - 目标: 支持普通对话、进行中状态和取消当前请求。
  - 涉及文件: `DevBridge-Front/src/app/ai/AiChatPanel.tsx`, `DevBridge-Front/src/app/ai/aiApi.ts`
  - 预期结果: Provider 失败展示可读错误，取消后可继续发送新问题。
- [x] 【日志分析】(前端) 实现日志快照分析交互 `← depends: 【脱敏分析】, 【AI入口】`
  - 目标: 从现有日志桶读取当前设备最近日志，调用日志分析接口并展示结构化结果。
  - 涉及文件: `DevBridge-Front/src/app/App.tsx`, `DevBridge-Front/src/app/ai/AiChatPanel.tsx`, `DevBridge-Front/src/app/ai/aiApi.ts`
  - 预期结果: AI 分析不修改日志页过滤条件，不关闭用户手动采集。
- [x] 【联调回归】(全栈) 完成端到端联调和回归验证 `← depends: all`
  - 目标: 验证配置、连接测试、普通对话、日志分析和现有功能回归。
  - 涉及文件: `DevBridge-Server`, `DevBridge-Front`, `DevBridge-Electron`
  - 预期结果: 后端测试、前端构建通过；设备、文件、应用、日志功能无接口契约回归。
- [x] 【模型切换设置入口】(全栈) 支持 AI 侧边栏内切换模型 `← delta`
  - 目标: 在 AI 侧边栏关闭按钮前增加设置入口，复用现有配置弹窗切换 Provider/模型。
  - 涉及文件: `DevBridge-Front/src/app/ai/AiChatPanel.tsx`, `DevBridge-Front/src/app/ai/AiAssistantShell.tsx`, `DevBridge-Front/src/app/ai/AiConfigDialog.tsx`, `DevBridge-Server/src/main/java/com/devbridge/server/ai/config/AiConfigService.java`
  - 预期结果: 已配置状态下可打开配置弹窗切换模型；最终以【配置明文回填】任务定义的完整回填行为为准。
- [x] 【配置明文回填】(全栈) 配置弹窗直接显示已保存 API URL 和 API Key `← delta`
  - 目标: 打开 AI 配置弹窗时直接显示已保存的 API URL 和 API Key，不再使用“留空沿用当前配置”交互。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/config/*`, `DevBridge-Server/src/main/java/com/devbridge/server/api/AiController.java`, `DevBridge-Front/src/app/ai/AiConfigDialog.tsx`, `DevBridge-Front/src/app/ai/aiApi.ts`, `DevBridge-Front/src/app/ai/aiTypes.ts`
  - 预期结果: 已配置状态下表单直接回填 Provider、API URL、API Key 和模型；保存和测试都要求完整配置。
- [x] 【多 Provider 配置切换】(全栈) Provider 切换时回填对应配置 `← delta`
  - 目标: AI 配置弹窗切换 Provider 时，API URL、API Key 和模型同步切换为该 Provider 的已保存配置；未保存过则使用 Provider 默认 URL/模型。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/config/*`, `DevBridge-Server/src/main/java/com/devbridge/server/api/AiController.java`, `DevBridge-Front/src/app/ai/AiConfigDialog.tsx`, `DevBridge-Front/src/app/ai/aiApi.ts`
  - 预期结果: 多个 Provider 配置相互独立，保存某个 Provider 不覆盖其他 Provider；前端切换 Provider 时不继续显示上一个 Provider 的配置。
- [x] 【Provider 端点解析】(后端) 修复 GLM/Qwen 等 Provider 路径重复拼接 `← delta`
  - 目标: 后端根据 Provider 和 API URL 解析实际 baseUrl 与 chat completions path，避免 `/v4/v1/chat/completions`、`/v1/v1/chat/completions` 这类错误路径。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/provider/*`, `DevBridge-Server/src/test/java/com/devbridge/server/ai/provider/*`
  - 预期结果: GLM、Qwen、ERNIE 等已带版本路径的 Provider 使用 `/chat/completions`；OpenAI host-only 地址继续使用 `/v1/chat/completions`；单测覆盖路径解析。
- [x] 【AI 长回复渲染性能】(前端) 修复长回复导致页面崩溃 `← delta`
  - 目标: 长回复和流式回复不再因高频 Markdown 解析、平滑滚动动画和超大表格 DOM 导致浏览器页面崩溃。
  - 涉及文件: `DevBridge-Front/src/app/ai/AiChatPanel.tsx`, `DevBridge-Front/src/app/ai/MarkdownContent.tsx`
  - 预期结果: 流式 chunk 合并低频刷新；流式阶段实时解析 Markdown；极端超长内容使用轻量模式保留完整文本。
- [x] 【提示词配置】(全栈) 支持 AI 系统提示词配置 `← delta`
  - 目标: AI 配置弹窗左侧增加“提示词配置”和“模型配置”菜单；切换菜单时保留未保存草稿；点击保存时统一保存模型配置和提示词。
  - 涉及文件: `DevBridge-Front/src/app/ai/AiConfigDialog.tsx`, `DevBridge-Front/src/app/ai/aiTypes.ts`, `DevBridge-Server/src/main/java/com/devbridge/server/ai/config/*`, `DevBridge-Server/src/main/java/com/devbridge/server/ai/conversation/AiConversationService.java`
  - 预期结果: 系统提示词可回填、编辑、恢复默认并保存；普通对话和流式对话使用已保存提示词。
- [x] 【模型输入下拉合一】(前端) 模型配置支持同框选择和手动输入 `← delta`
  - 目标: 模型配置只保留一个模型输入框，拉取模型列表后在同一输入框内提供候选选择，同时允许继续手动输入。
  - 涉及文件: `DevBridge-Front/src/app/ai/AiConfigDialog.tsx`
  - 预期结果: 不再出现模型下拉框和手动输入框分离的交互；模型字段始终写入同一个表单值。
- [x] 【AI 助手侧栏视觉优化】(前端) 提升 AI 助手专业感并统一字体 `← delta`
  - 目标: 统一“已完成思考与执行”等过程卡片字号；在不改变整体应用风格和功能逻辑的前提下优化 AI 助手侧栏视觉层次。
  - 涉及文件: `DevBridge-Front/src/app/ai/AiChatPanel.tsx`
  - 预期结果: 侧栏顶部、快捷操作、设备上下文、过程卡片和输入区更精致；字体大小与对话内容保持一致。
- [x] 【AI 助手毛玻璃背景】(前端) 降低半透明背景视觉干扰 `← delta`
  - 目标: 将 AI 助手侧栏从直接半透明叠层调整为高斯模糊毛玻璃效果，减少主界面内容穿透造成的干扰。
  - 涉及文件: `DevBridge-Front/src/app/ai/AiChatPanel.tsx`
  - 预期结果: 侧栏主体、标题区、设备上下文和输入区使用稳定生成的 backdrop blur 与高透明度背景，保持专业克制的应用风格。
- [x] 【AI 助手全屏】(前端) 支持侧栏扩展到整个页面 `← delta`
  - 目标: 在 AI 助手右上角增加全屏/退出全屏按钮，让 AI 助手可覆盖整个页面，而不是只显示在右侧侧边栏。
  - 涉及文件: `DevBridge-Front/src/app/ai/AiChatPanel.tsx`
  - 预期结果: 点击全屏按钮后 AI 助手容器铺满页面；再次点击或按 Esc 退出全屏；关闭后再次打开仍为普通侧边栏。
- [x] 【AI 助手全屏输入区宽度】(前端) 全屏时输入框保持侧栏宽度并居中 `← delta`
  - 目标: 全屏模式下对话输入框不随页面无限拉宽，最大宽度与普通侧栏下输入框一致并居中显示。
  - 涉及文件: `DevBridge-Front/src/app/ai/AiChatPanel.tsx`
  - 预期结果: 普通侧栏输入区保持原样；全屏输入区最大宽度约 548px 且居中，回到底部按钮贴近输入区右侧。

## 完成状态

> 进度: 22/22 已完成
