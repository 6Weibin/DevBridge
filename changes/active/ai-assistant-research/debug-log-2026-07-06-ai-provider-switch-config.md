# AI Provider 切换配置不联动排查记录

## 问题

AI 配置弹窗中切换 Provider 时，API URL、API Key 和模型仍保持上一个 Provider 的值。例如从 DeepSeek 切换到 OpenAI 后仍显示 DeepSeek 的 URL/Key/模型。

## 根因

后端原先只保存一份当前 AI 配置，没有按 Provider 维度保存多份配置。前端 Provider 下拉框只是改了 `provider` 字段，API URL、API Key 和模型没有对应 Provider 的数据来源，因此不会自动变化。

## 修复

- 后端配置文件改为多 Provider 结构：`activeProvider + providers`。
- 兼容旧版单 Provider 配置文件，读取后可正常回填，后续保存自动转为新结构。
- `GET /api/ai/config?provider=xxx` 支持读取指定 Provider 的配置详情。
- 保存配置时只更新当前 Provider，并把它设为 active Provider，不覆盖其他 Provider。
- 前端切换 Provider 时：
  - 先缓存当前 Provider 表单草稿。
  - 再读取目标 Provider 已保存配置。
  - 如果目标 Provider 未配置过，则使用该 Provider 默认 API URL 和模型，API Key 为空。

## 验证

- `npm run build` 通过，版本 `V2026.7.0035`。
- `mvn -Dtest=AiConfigServiceTest test` 通过，覆盖 4 个配置服务用例。
- 8080 已重启并读取 Electron runtime AI 配置目录。
- 接口验证：
  - 当前 active Provider 为 `deepseek`。
  - `GET /api/ai/config?provider=deepseek` 返回已保存 DeepSeek 配置。
  - 未保存过的 `openai`、`qwen` 返回未配置。
- 浏览器验证：
  - 初始 DeepSeek：`https://api.deepseek.com` / `deepseek-v4-pro` / API Key 已回填。
  - 切 OpenAI：`https://api.openai.com` / `gpt-4o-mini` / API Key 为空。
  - 切 Qwen：`https://dashscope.aliyuncs.com/compatible-mode/v1` / `qwen-plus` / API Key 为空。
  - 切回 DeepSeek：恢复 DeepSeek URL、模型和 API Key。
