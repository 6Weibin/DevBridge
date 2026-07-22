package com.devbridge.server.ai.security.egress;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.config.AiProviderType;
import com.devbridge.server.ai.config.AiRuntimeConfig;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Assessment;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Classification;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Context;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.DataType;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Decision;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Item;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 模型数据外发策略测试，覆盖直接外发、本地模型限定和绝对禁止数据。
 *
 * <p>by AI.Coding</p>
 */
class AiDataEgressPolicyServiceTest {

    private final AiDataEgressPolicyService policyService = new AiDataEgressPolicyService();

    /**
     * 验证普通敏感设备数据可直接发往用户配置的外部 Provider。
     */
    @Test
    void assessShouldAllowExternalDeviceLogsWithoutConfirmation() {
        Assessment assessment = policyService.assess(
                config("https://api.openai.com", "gpt-4o-mini"),
                context(item(DataType.DEVICE_LOG, Classification.CONFIRMATION_REQUIRED, true, "masked log")));

        assertThat(assessment.decision()).isEqualTo(Decision.ALLOW);
        assertThat(assessment.dataTypes()).containsExactly("DEVICE_LOG");
        assertThat(assessment.maskedTypes()).containsExactly("DEVICE_LOG");
        assertThat(assessment.totalBytes()).isPositive();
        assertThat(assessment.dataDigest()).hasSize(64);
    }

    /**
     * 验证企业源码类数据只能进入本地回环模型，私网或公网 Provider 均不能自动视为本地。
     */
    @Test
    void assessShouldAllowLocalOnlyDataOnlyForLoopbackModel() {
        Context context = context(item(
                DataType.SOURCE_CODE,
                Classification.LOCAL_MODEL_ONLY,
                false,
                "private source"));

        assertThat(policyService.assess(config("http://127.0.0.1:11434", "qwen-local"), context).decision())
                .isEqualTo(Decision.ALLOW);
        assertThat(policyService.assess(config("http://192.168.1.20:11434", "qwen-lan"), context).decision())
                .isEqualTo(Decision.BLOCK);
    }

    /**
     * 验证凭据类数据对本地和外部模型都绝对阻断，用户确认不能降低该分类。
     */
    @Test
    void assessShouldBlockProhibitedCredentialsForEveryProvider() {
        Context context = context(item(
                DataType.CREDENTIAL,
                Classification.PROHIBITED,
                false,
                "sk-secret"));

        assertThat(policyService.assess(config("http://localhost:11434", "local"), context).decision())
                .isEqualTo(Decision.BLOCK);
        assertThat(policyService.assess(config("https://api.openai.com", "remote"), context).decision())
                .isEqualTo(Decision.BLOCK);
    }

    /**
     * 验证审计摘要仍绑定 Provider、模型和数据正文。
     */
    @Test
    void assessShouldBindDigestToProviderModelAndContent() {
        Assessment first = policyService.assess(
                config("https://api.openai.com", "model-a"),
                context(item(DataType.APPLICATION_LIST, Classification.CONFIRMATION_REQUIRED, true, "app-a")));
        Assessment changed = policyService.assess(
                config("https://api.openai.com", "model-b"),
                context(item(DataType.APPLICATION_LIST, Classification.CONFIRMATION_REQUIRED, true, "app-b")));

        assertThat(changed.dataDigest()).isNotEqualTo(first.dataDigest());
    }

    /**
     * 创建测试运行时配置。
     *
     * @param apiUrl Provider URL
     * @param model 模型
     * @return 测试配置
     */
    private AiRuntimeConfig config(String apiUrl, String model) {
        return new AiRuntimeConfig(AiProviderType.OPENAI, apiUrl, "test-key", model, "");
    }

    /**
     * 创建带完整 Agent 关联标识的外发上下文。
     *
     * @param item 数据项
     * @return 外发上下文
     */
    private Context context(Item item) {
        return new Context(
                "task-1",
                "conversation-1",
                "step-1",
                "model-call-1",
                "分析设备数据",
                List.of(item),
                "");
    }

    /**
     * 创建文本数据摘要项。
     *
     * @param type 数据类型
     * @param classification 分类
     * @param masked 是否脱敏
     * @param content 内容
     * @return 数据摘要项
     */
    private Item item(
            DataType type,
            Classification classification,
            boolean masked,
            String content) {
        return Item.fromText(type, classification, "test-source", masked, content);
    }
}
