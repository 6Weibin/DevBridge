package com.devbridge.server.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.config.AiProviderType;
import com.devbridge.server.ai.config.AiRuntimeConfig;
import com.devbridge.server.ai.observation.AiObservationRecorder;
import com.devbridge.server.ai.prompt.AiPromptComposer;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.ai.security.egress.AiDataEgressGuard;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Classification;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Context;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.DataType;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Item;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicyService;
import com.devbridge.server.model.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spring AI Provider Gateway 测试，覆盖模型列表响应解析。
 *
 * <p>by AI.Coding</p>
 */
class SpringAiProviderGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SpringAiProviderGateway gateway = new SpringAiProviderGateway(null, null, null);

    /**
     * 验证 OpenAI-compatible data 数组可解析为模型 ID 列表并保持顺序去重。
     *
     * @throws Exception JSON 解析失败时抛出
     */
    @Test
    void parseModelIdsShouldReadOpenAiCompatibleDataArray() throws Exception {
        var payload = objectMapper.readTree("""
                {
                  "object": "list",
                  "data": [
                    {"id": "gpt-4o-mini"},
                    {"id": "gpt-4o"},
                    {"id": "gpt-4o-mini"}
                  ]
                }
                """);

        assertThat(gateway.parseModelIds(payload)).containsExactly("gpt-4o-mini", "gpt-4o");
    }

    /**
     * 验证部分兼容服务返回字符串数组时仍可提取模型名。
     *
     * @throws Exception JSON 解析失败时抛出
     */
    @Test
    void parseModelIdsShouldReadStringModelArray() throws Exception {
        var payload = objectMapper.readTree("""
                {
                  "models": ["deepseek-chat", "deepseek-reasoner"]
                }
                """);

        assertThat(gateway.parseModelIds(payload)).containsExactly("deepseek-chat", "deepseek-reasoner");
    }

    /**
     * 验证本地数据外发阻断不会被 Gateway 误包装成 Provider 502 异常。
     */
    @Test
    void chatShouldKeepLocalDataEgressSecurityError() {
        AiDataEgressGuard guard = new AiDataEgressGuard(
                new AiDataEgressPolicyService(),
                null,
                null,
                null,
                null);
        SpringAiProviderGateway securedGateway = new SpringAiProviderGateway(
                new SensitiveDataMasker(),
                new AiObservationRecorder(),
                null,
                new AiPromptComposer(),
                guard);
        AiRuntimeConfig config = new AiRuntimeConfig(
                AiProviderType.OPENAI,
                "https://api.openai.com",
                "test-key",
                "gpt-test",
                "");
        Context context = new Context(
                "",
                "legacy-conversation",
                "",
                "",
                "安全测试",
                List.of(Item.fromText(
                        DataType.CREDENTIAL,
                        Classification.PROHIBITED,
                        "credential",
                        false,
                        "secret")),
                "");
        AiProviderRequest request = new AiProviderRequest(
                config,
                "连接测试",
                "请处理",
                10,
                0.1d,
                List.of(),
                java.util.Map.of(),
                context);

        assertThatThrownBy(() -> securedGateway.chat(request))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("AI_DATA_EGRESS_BLOCKED"));
    }

    /** 验证框架包装后的确认异常仍按原业务错误交给对话层处理。 */
    @Test
    void shouldRecoverWrappedBusinessException() {
        BusinessException expected = new BusinessException(
                "AI_DATA_EGRESS_CONFIRMATION_REQUIRED", "需要确认",
                org.springframework.http.HttpStatus.CONFLICT, "安全确认");

        BusinessException actual = gateway.findBusinessException(
                new IllegalStateException("tool callback failed", expected));

        assertThat(actual).isSameAs(expected);
    }
}
