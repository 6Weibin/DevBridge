package com.devbridge.server.ai.config;

/**
 * AI 模型列表请求，只需要 Provider、API URL 和 API Key，不要求用户先填写模型。
 *
 * <p>by AI.Coding</p>
 *
 * @param provider Provider 类型
 * @param apiUrl API 基础地址
 * @param apiKey API Key
 */
public record AiModelListRequest(String provider, String apiUrl, String apiKey) {
}
