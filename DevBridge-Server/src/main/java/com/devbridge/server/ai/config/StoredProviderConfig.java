package com.devbridge.server.ai.config;

import java.time.Instant;

/**
 * 单个 Provider 的本地配置，API Key 字段保存密文。
 *
 * <p>by AI.Coding</p>
 *
 * @param apiUrl API 基础地址
 * @param model 模型名称
 * @param encryptedApiKey 加密后的 API Key
 * @param updatedAt 更新时间
 */
record StoredProviderConfig(String apiUrl, String model, String encryptedApiKey, Instant updatedAt) {
}
