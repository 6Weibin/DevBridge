# AI 模型切换设置入口实现记录

## 需求

- AI 助手侧边栏右上角关闭按钮前增加设置按钮。
- 点击设置按钮后可以切换选择和配置其他模型。

## 实现步骤

- 阅读 `AiAssistantShell.tsx`、`AiChatPanel.tsx`、`AiConfigDialog.tsx` 和后端 AI 配置服务。
- 复用现有 `AiConfigDialog`，避免新增第二套配置页面。
- 在 `AiChatPanel` 标题区增加设置按钮，只触发 `onOpenConfig` 回调。
- 在 `AiAssistantShell` 中统一控制配置弹窗，确保弹窗渲染在侧边栏之后。
- 在 `AiConfigService` 中支持已配置状态下同 Provider/API URL 切换模型时复用本机已加密 API Key。

## 安全约束

- 前端仍不回显 API Key。
- 已配置时 API URL 和 API Key 可以留空沿用当前配置。
- 如果 Provider 或 API URL 变化，后端要求重新输入 API Key，避免旧 Key 被发送到新 Provider 或新地址。
- 连接测试仍要求填写完整 API URL/API Key，因为测试接口使用临时配置，不读取已保存密钥。

## 验证

- `npm run build` 通过，生成 `V2026.7.0031`。
- `mvn -Dtest=AiConfigServiceTest test` 通过，覆盖 3 个配置服务用例。
- 8080 页面返回新资源 `index-waoJYY5S.js`。
- 浏览器验证：
  - AI 面板标题区存在 `title="切换模型设置"` 的按钮。
  - 设置按钮位于关闭按钮前。
  - 点击后展示 `z-[60]` 配置弹窗。
  - 已配置状态显示当前摘要：`deepseek / deepseek-v4-pro / api.deepseek.com`。
  - API URL/API Key 输入框提示为“留空则沿用当前 ...”。
