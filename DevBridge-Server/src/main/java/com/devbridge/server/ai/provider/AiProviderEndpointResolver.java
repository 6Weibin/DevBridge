package com.devbridge.server.ai.provider;

import com.devbridge.server.ai.config.AiProviderType;
import com.devbridge.server.ai.config.AiRuntimeConfig;
import org.springframework.stereotype.Component;

/**
 * AI Provider 端点解析器，集中处理各厂商 OpenAI-compatible 路径差异。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AiProviderEndpointResolver {

    private static final String OPENAI_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String OPENAI_EMBEDDINGS_PATH = "/v1/embeddings";
    private static final String OPENAI_MODELS_PATH = "/v1/models";
    private static final String VERSIONED_COMPLETIONS_PATH = "/chat/completions";
    private static final String VERSIONED_EMBEDDINGS_PATH = "/embeddings";
    private static final String VERSIONED_MODELS_PATH = "/models";
    private static final String CHAT_COMPLETIONS_SUFFIX = "/chat/completions";

    /**
     * 根据 Provider 和用户配置解析实际请求端点，防止 GLM/Qwen 这类已带版本号的 baseUrl 再追加 /v1。
     *
     * @param config AI 运行时配置
     * @return 实际 Provider 请求端点
     */
    public AiProviderEndpoint resolve(AiRuntimeConfig config) {
        String baseUrl = trimTrailingSlash(config.apiUrl());
        if (baseUrl.endsWith(CHAT_COMPLETIONS_SUFFIX)) {
            // 用户可能直接粘贴完整 chat completions 地址，这里回退为 Spring AI 需要的 baseUrl + path 形式。
            baseUrl = baseUrl.substring(0, baseUrl.length() - CHAT_COMPLETIONS_SUFFIX.length());
        }
        if (hasVersionedBasePath(config.provider(), baseUrl)) {
            return new AiProviderEndpoint(baseUrl, VERSIONED_COMPLETIONS_PATH, VERSIONED_EMBEDDINGS_PATH, VERSIONED_MODELS_PATH);
        }
        return new AiProviderEndpoint(baseUrl, OPENAI_COMPLETIONS_PATH, OPENAI_EMBEDDINGS_PATH, OPENAI_MODELS_PATH);
    }

    /**
     * 判断 baseUrl 是否已经包含 Provider 的版本路径，包含时不能再追加 OpenAI 默认 /v1 前缀。
     *
     * @param provider Provider 类型
     * @param baseUrl 用户配置的基础地址
     * @return 已包含版本路径返回 true
     */
    private boolean hasVersionedBasePath(AiProviderType provider, String baseUrl) {
        return provider == AiProviderType.GLM
                || provider == AiProviderType.QWEN
                || provider == AiProviderType.ERNIE
                || baseUrl.matches(".*/v\\d+$");
    }

    /**
     * 去除 URL 末尾斜杠，保证后续路径拼接结果稳定。
     *
     * @param value 原始 URL
     * @return 去除末尾斜杠后的 URL
     */
    private String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
