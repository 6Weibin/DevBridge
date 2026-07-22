# 任务清单

> 来源: design.md
> 生成时间: 2026-07-06

## 实施任务

- [x] 【工具模型】(后端) 定义 ADB MCP 统一模型
  - 目标: 定义工具定义、请求、结果、风险、错误码、输出限制、确认令牌和运行中调用模型，保证返回结构覆盖 spec 要求字段。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/model/*`
  - 预期结果: 后端拥有可序列化的 ADB MCP 工具契约，供目录、执行、确认和 REST 复用。
- [x] 【工具目录】(后端) 实现 ADB MCP 工具目录
  - 目标: 按 ADB 顶层命令域定义工具清单、JSON Schema、风险级别、是否需要设备和输出限制。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/catalog/*`, `DevBridge-Server/src/test/java/com/devbridge/server/ai/mcp/catalog/*`
  - 预期结果: 工具目录覆盖 spec 声明的全部 ADB 命令域，并能按名称获取工具定义。
- [x] 【命令规划】(后端) 实现工具入参到 ADB 参数数组规划
  - 目标: 将工具调用参数转换为受控 ADB 参数数组，支持任意 `adb shell COMMAND...`、global options 和环境上下文白名单。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/execution/*`, `DevBridge-Server/src/test/java/com/devbridge/server/ai/mcp/execution/*`
  - 预期结果: 每个工具都能生成不经过宿主机 shell 的 ADB 参数数组，非法顶层命令返回稳定错误。
- [x] 【设备校验】(后端) 实现目标设备状态校验
  - 目标: 复用现有设备枚举能力，对需要设备的工具校验 serial 和 connected 状态。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/execution/AdbDeviceValidator.java`
  - 预期结果: 设备不存在、offline、unauthorized 能返回稳定错误，不执行 ADB 命令。
- [x] 【敏感识别】(后端) 实现 ADB 风险分类
  - 目标: 覆盖 spec 定义的 18 类敏感操作，识别顶层命令和 shell 命令风险。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/risk/*`, `DevBridge-Server/src/test/java/com/devbridge/server/ai/mcp/risk/*`
  - 预期结果: 敏感命令返回风险说明和影响范围，非敏感只读命令可直执。
- [x] 【确认机制】(后端) 实现一次性确认令牌
  - 目标: 令牌绑定会话、设备、完整 ADB 参数、风险和过期时间，支持过期、重复使用、参数不匹配和取消。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/confirmation/*`, `DevBridge-Server/src/test/java/com/devbridge/server/ai/mcp/confirmation/*`
  - 预期结果: 敏感操作首次调用不执行，确认后仅能执行令牌绑定的同一命令。
- [x] 【输出安全】(后端) 实现输出截断、脱敏和审计
  - 目标: 限制 stdout/stderr 行数和字符数，脱敏凭证、邮箱、手机号、设备序列号和常见密钥字段，并记录审计摘要。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/security/*`, `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/audit/*`
  - 预期结果: 工具输出进入前端、AI Prompt 和日志前都已截断和脱敏。
- [x] 【执行取消】(后端) 实现工具执行和取消管理
  - 目标: 短命令复用 `CommandRunner`，长命令复用 `StreamingCommandRunner` 并登记取消句柄。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/execution/*`
  - 预期结果: 超时和用户取消均能停止进程或返回 canceled 状态。
- [x] 【Spring适配】(后端) 接入 Spring AI ToolCallback
  - 目标: 从工具目录生成 `ToolCallback`，让 `AiToolRegistry` 按 `ADB_DEVICE_MANAGEMENT` 返回 ADB MCP 工具。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/adapter/spring/*`, `DevBridge-Server/src/main/java/com/devbridge/server/ai/tool/*`
  - 预期结果: Spring AI 可通过 Tool Calling 调用 ADB MCP 工具，工具结果为统一 JSON。
- [x] 【REST接口】(后端) 实现工具目录、调用、确认、取消接口
  - 目标: 新增 ADB MCP Controller，提供目录、调用、流式调用、确认和取消接口。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/mcp/adapter/rest/*`
  - 预期结果: 前端和调试工具可通过 REST/SSE 使用 ADB MCP 能力。
- [x] 【AI编排】(后端) 将对话和日志分析接入 MCP 工具
  - 目标: 普通对话启用 ADB MCP 工具调用，日志分析兼容入口内部通过 MCP 获取日志上下文。
  - 涉及文件: `DevBridge-Server/src/main/java/com/devbridge/server/ai/conversation/*`, `DevBridge-Server/src/main/java/com/devbridge/server/ai/analysis/*`, `DevBridge-Server/src/main/java/com/devbridge/server/ai/provider/*`
  - 预期结果: AI 对话可触发工具调用，旧日志分析接口响应结构保持兼容。
- [x] 【前端类型】(前端) 扩展 AI 工具事件和 API 封装
  - 目标: 新增工具定义、工具结果、确认请求、工具事件类型，封装工具目录、确认、取消接口。
  - 涉及文件: `DevBridge-Front/src/app/ai/aiTypes.ts`, `DevBridge-Front/src/app/ai/aiApi.ts`
  - 预期结果: 前端具备渲染工具状态和确认卡片所需类型与 API。
- [x] 【前端确认】(前端) 实现工具状态和确认卡片
  - 目标: 侧边栏展示工具名、状态、参数摘要、结果摘要和敏感操作确认卡片，支持确认、取消和关闭侧边栏取消运行中工具。
  - 涉及文件: `DevBridge-Front/src/app/ai/AiChatPanel.tsx`, `DevBridge-Front/src/app/ai/AiToolCallCard.tsx`, `DevBridge-Front/src/app/ai/AiConfirmationCard.tsx`
  - 预期结果: AI 工具调用交互不影响主界面页签、日志、文件、应用状态。
- [x] 【兼容回归】(全栈) 完成 ADB MCP 全能力验收
  - 目标: 验证全部 ADB 顶层命令域覆盖、任意 shell、敏感确认、输出脱敏截断、审计取消和现有功能无回归。
  - 涉及文件: 后端 Maven 测试、前端构建、AI/MCP 相关入口
  - 预期结果: 后端测试通过，前端构建通过，现有设备、文件、应用、日志接口契约不变。

## 完成状态

> 进度: 14/14 已完成
