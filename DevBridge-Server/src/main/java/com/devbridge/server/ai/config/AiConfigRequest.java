package com.devbridge.server.ai.config;

import java.util.List;

/**
 * AI 配置保存和连接测试请求。
 *
 * <p>by AI.Coding</p>
 *
 * @param provider Provider 类型
 * @param apiUrl API 基础地址
 * @param apiKey API Key
 * @param model 模型名称
 * @param systemPrompt 用户偏好提示词；字段名为兼容旧前端保留
 * @param localShellAuthorizations Local Shell MCP 命令授权规则
 */
public record AiConfigRequest(
        String provider,
        String apiUrl,
        String apiKey,
        String model,
        String systemPrompt,
        List<AiCommandAuthorizationRule> localShellAuthorizations) {

    /**
     * 兼容未传授权配置的旧前端和测试；授权规则默认空列表。
     *
     * @param provider Provider 类型
     * @param apiUrl API 基础地址
     * @param apiKey API Key
     * @param model 模型名称
     * @param systemPrompt 系统提示词
     */
    public AiConfigRequest(String provider, String apiUrl, String apiKey, String model, String systemPrompt) {
        this(provider, apiUrl, apiKey, model, systemPrompt, List.of());
    }

    /**
     * 兼容旧测试和旧调用方；未传提示词时表示无用户偏好。
     *
     * @param provider Provider 类型
     * @param apiUrl API 基础地址
     * @param apiKey API Key
     * @param model 模型名称
     */
    public AiConfigRequest(String provider, String apiUrl, String apiKey, String model) {
        this(provider, apiUrl, apiKey, model, AiPromptDefaults.DEFAULT_USER_PREFERENCE_PROMPT);
    }
}
