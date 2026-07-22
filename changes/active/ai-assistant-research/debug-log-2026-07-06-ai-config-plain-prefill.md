# AI 配置明文回填实现记录

## 需求

用户要求 AI 配置页已保存的 API URL 和 API Key 直接显示，不再使用“留空则沿用当前配置”的交互。

## 实现

- 后端新增 `AiConfigDetail`，用于配置页回填 Provider、API URL、API Key 和模型。
- 后端新增 `GET /api/ai/config`，只在配置详情接口中解密 API Key。
- 后端保存逻辑恢复为完整配置必填，不再支持空 API URL/API Key 复用旧配置。
- 前端新增 `getConfigDetail()`，配置弹窗打开时读取详情并填入表单。
- 前端移除“留空沿用当前 API URL/API Key”的提示、校验和保存分支。

## 验证

- `npm run build` 通过，生成 `V2026.7.0032`。
- `mvn -Dtest=AiConfigServiceTest test` 通过。
- 已重启后端 8080。
- `GET /api/ai/config` 验证：
  - `configured=true`
  - `apiUrl=https://api.deepseek.com`
  - `apiKeyLength=35`
- 浏览器验证：
  - AI 配置弹窗显示 API URL。
  - API Key 已回填到密码输入框。
  - 页面和 placeholder 不再包含“留空”提示。

## 注意

API Key 明文只用于本机配置页回填；普通状态接口 `/api/ai/config/status` 仍不返回 API Key。
