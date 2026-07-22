package com.devbridge.server.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.config.AiProviderType;
import com.devbridge.server.ai.config.AiRuntimeConfig;
import org.junit.jupiter.api.Test;

/**
 * AI Provider 端点解析测试，覆盖不同厂商 baseUrl 与 Spring AI 路径拼接规则。
 *
 * <p>by AI.Coding</p>
 */
class AiProviderEndpointResolverTest {

    private final AiProviderEndpointResolver resolver = new AiProviderEndpointResolver();

    /**
     * 验证 GLM 官方地址已包含 /v4 时不会再追加 /v1。
     */
    @Test
    void glmShouldUseChatCompletionsUnderVersionedBaseUrl() {
        AiProviderEndpoint endpoint = resolver.resolve(config(
                AiProviderType.GLM,
                "https://open.bigmodel.cn/api/paas/v4"));

        assertThat(endpoint.baseUrl()).isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(endpoint.completionsPath()).isEqualTo("/chat/completions");
    }

    /**
     * 验证 Qwen 兼容模式地址已包含 /v1 时不会拼成 /v1/v1。
     */
    @Test
    void qwenShouldAvoidDuplicatedVersionPath() {
        AiProviderEndpoint endpoint = resolver.resolve(config(
                AiProviderType.QWEN,
                "https://dashscope.aliyuncs.com/compatible-mode/v1"));

        assertThat(endpoint.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(endpoint.completionsPath()).isEqualTo("/chat/completions");
        assertThat(endpoint.modelsPath()).isEqualTo("/models");
    }

    /**
     * 验证 ERNIE 默认 v2 地址按版本化 baseUrl 处理。
     */
    @Test
    void ernieShouldUseVersionedBaseUrlPath() {
        AiProviderEndpoint endpoint = resolver.resolve(config(
                AiProviderType.ERNIE,
                "https://qianfan.baidubce.com/v2/"));

        assertThat(endpoint.baseUrl()).isEqualTo("https://qianfan.baidubce.com/v2");
        assertThat(endpoint.completionsPath()).isEqualTo("/chat/completions");
    }

    /**
     * 验证 OpenAI 官方 host-only 地址仍使用默认 /v1/chat/completions。
     */
    @Test
    void openaiHostOnlyShouldKeepDefaultOpenAiPath() {
        AiProviderEndpoint endpoint = resolver.resolve(config(
                AiProviderType.OPENAI,
                "https://api.openai.com"));

        assertThat(endpoint.baseUrl()).isEqualTo("https://api.openai.com");
        assertThat(endpoint.completionsPath()).isEqualTo("/v1/chat/completions");
        assertThat(endpoint.modelsPath()).isEqualTo("/v1/models");
    }

    /**
     * 验证用户手动输入带 /v1 的 OpenAI-compatible 地址时不会重复追加版本号。
     */
    @Test
    void customVersionedBaseUrlShouldUseRelativeChatPath() {
        AiProviderEndpoint endpoint = resolver.resolve(config(
                AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
                "https://example.com/openai/v1"));

        assertThat(endpoint.baseUrl()).isEqualTo("https://example.com/openai/v1");
        assertThat(endpoint.completionsPath()).isEqualTo("/chat/completions");
    }

    /**
     * 验证用户粘贴完整 chat completions 地址时可被拆分为 baseUrl 和 path。
     */
    @Test
    void fullChatCompletionsUrlShouldBeNormalized() {
        AiProviderEndpoint endpoint = resolver.resolve(config(
                AiProviderType.GLM,
                "https://open.bigmodel.cn/api/paas/v4/chat/completions"));

        assertThat(endpoint.baseUrl()).isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(endpoint.completionsPath()).isEqualTo("/chat/completions");
    }

    /**
     * 创建测试用运行时配置。
     *
     * @param provider Provider 类型
     * @param apiUrl API URL
     * @return 运行时配置
     */
    private AiRuntimeConfig config(AiProviderType provider, String apiUrl) {
        return new AiRuntimeConfig(provider, apiUrl, "sk-test", "test-model");
    }
}
