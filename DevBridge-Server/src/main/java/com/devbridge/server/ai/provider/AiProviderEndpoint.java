package com.devbridge.server.ai.provider;

/**
 * AI Provider 实际请求端点，拆分 baseUrl 和路径，避免不同厂商版本路径被重复拼接。
 *
 * <p>by AI.Coding</p>
 *
 * @param baseUrl Provider 基础地址
 * @param completionsPath 对话补全路径
 * @param embeddingsPath 向量路径
 * @param modelsPath 模型列表路径
 */
record AiProviderEndpoint(String baseUrl, String completionsPath, String embeddingsPath, String modelsPath) {
}
