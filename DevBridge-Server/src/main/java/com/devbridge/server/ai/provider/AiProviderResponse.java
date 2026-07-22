package com.devbridge.server.ai.provider;

/**
 * Provider 对话响应。
 *
 * <p>by AI.Coding</p>
 *
 * @param answer AI 回复
 * @param provider Provider 类型
 * @param model 模型名称
 * @param elapsedMillis 耗时毫秒
 */
public record AiProviderResponse(String answer, String provider, String model, long elapsedMillis) {
}
