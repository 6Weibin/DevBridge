package com.devbridge.server.ai.config;

import com.devbridge.server.ai.config.AiModelCapabilityRegistry.ModelCapability;

/**
 * AI 运行时配置，仅在后端内存中使用，禁止直接返回前端。
 *
 * <p>by AI.Coding</p>
 *
 * @param provider Provider 类型
 * @param apiUrl API 基础地址
 * @param apiKey API Key 明文
 * @param model 模型名称
 * @param userPreferencePrompt 用户可编辑的偏好提示词，不得直接作为 Provider system message
 * @param capability 当前模型能力和预算快照
 */
public record AiRuntimeConfig(
        AiProviderType provider,
        String apiUrl,
        String apiKey,
        String model,
        String userPreferencePrompt,
        ModelCapability capability) {

    /** 兼容旧运行时对象缺失能力字段。 */
    public AiRuntimeConfig {
        capability = capability == null
                ? new AiModelCapabilityRegistry().resolve(provider, model)
                : capability;
    }

    /**
     * 兼容未配置提示词的旧调用方；默认提示词仍保持原有助手行为。
     *
     * @param provider Provider 类型
     * @param apiUrl API 基础地址
     * @param apiKey API Key 明文
     * @param model 模型名称
     */
    public AiRuntimeConfig(AiProviderType provider, String apiUrl, String apiKey, String model) {
        this(provider, apiUrl, apiKey, model, AiPromptDefaults.DEFAULT_USER_PREFERENCE_PROMPT, null);
    }

    /**
     * 兼容已传入用户偏好但尚未传入能力快照的调用方。
     *
     * @param provider Provider 类型
     * @param apiUrl API 基础地址
     * @param apiKey API Key
     * @param model 模型名称
     * @param userPreferencePrompt 用户偏好提示词
     */
    public AiRuntimeConfig(
            AiProviderType provider,
            String apiUrl,
            String apiKey,
            String model,
            String userPreferencePrompt) {
        this(provider, apiUrl, apiKey, model, userPreferencePrompt, null);
    }
}
