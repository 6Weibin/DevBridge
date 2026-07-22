package com.devbridge.server.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 模型能力注册表业务边界测试。
 *
 * <p>by AI.Coding</p>
 */
class AiModelCapabilityRegistryTest {

    private final AiModelCapabilityRegistry registry = new AiModelCapabilityRegistry();

    /** 验证聊天模型获得工具和有界预算。 */
    @Test
    void shouldResolveChatModelCapabilities() {
        var capability = registry.resolve(AiProviderType.QWEN, "qwen-max");

        assertThat(capability.chat()).isTrue();
        assertThat(capability.toolCalling()).isTrue();
        assertThat(capability.limits().contextWindowTokens()).isEqualTo(128_000);
        assertThat(capability.limits().maxRetries()).isEqualTo(2);
    }

    /** 验证非聊天模型不会进入对话和 Tool Calling。 */
    @Test
    void shouldRejectNonChatModelCapabilities() {
        var capability = registry.resolve(AiProviderType.OPENAI, "text-embedding-3-large");

        assertThat(capability.chat()).isFalse();
        assertThat(capability.toolCalling()).isFalse();
    }
}
