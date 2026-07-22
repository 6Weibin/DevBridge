package com.devbridge.server.ai.security.egress;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.agent.confirmation.AgentConfirmation;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationBinding;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationRiskLevel;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationStatus;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationStore;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Assessment;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Context;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Decision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 数据外发守卫测试，验证确认不能跨步骤、模型调用或内容摘要复用。
 *
 * <p>by AI.Coding</p>
 */
class AiDataEgressGuardTest {

    /**
     * 验证已接受确认只有在任务、步骤、模型调用和数据摘要全部一致时才有效。
     */
    @Test
    void approvedShouldRequireExactAcceptedBinding() {
        Assessment assessment = assessment("digest-1");
        Context context = context("confirmation-1", "model-call-1");
        AgentConfirmation confirmation = confirmation("digest-1", "model-call-1");
        AiDataEgressGuard guard = guard(confirmation);

        assertThat(guard.approved(context, assessment)).isTrue();
        assertThat(guard.approved(context("confirmation-1", "model-call-2"), assessment)).isFalse();
        assertThat(guard.approved(context, assessment("digest-2"))).isFalse();
    }

    /** 确认被 Runtime 消费后，本次摘要完全一致的续跑外发仍应通过。 */
    @Test
    void approvedShouldAllowConsumedConfirmationForExactBinding() {
        AgentConfirmation consumed = new AgentConfirmation(
                "confirmation-1", "task-1", binding("digest-1", "model-call-1"),
                AgentConfirmationStatus.CONSUMED, Instant.now().minusSeconds(10),
                Instant.now().plusSeconds(600), Instant.now(), "用户已确认");

        assertThat(guard(consumed).approved(
                context("confirmation-1", "model-call-1"), assessment("digest-1"))).isTrue();
    }

    /**
     * 验证过期确认不能继续授权数据外发。
     */
    @Test
    void approvedShouldRejectExpiredConfirmation() {
        AgentConfirmation expired = new AgentConfirmation(
                "confirmation-1",
                "task-1",
                binding("digest-1", "model-call-1"),
                AgentConfirmationStatus.ACCEPTED,
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(60),
                Instant.now().minusSeconds(90),
                "用户已确认");

        assertThat(guard(expired).approved(
                context("confirmation-1", "model-call-1"), assessment("digest-1"))).isFalse();
    }

    /**
     * 创建只用于确认校验的外发守卫。
     *
     * @param confirmation 确认记录
     * @return 外发守卫
     */
    private AiDataEgressGuard guard(AgentConfirmation confirmation) {
        return new AiDataEgressGuard(
                null,
                null,
                new SingleConfirmationStore(confirmation),
                null,
                null);
    }

    /**
     * 创建测试外发评估。
     *
     * @param digest 数据摘要
     * @return 外发评估
     */
    private Assessment assessment(String digest) {
        return new Assessment(
                Decision.CONFIRM,
                "openai",
                "gpt-4o-mini",
                List.of("DEVICE_LOG"),
                1024,
                List.of("DEVICE_LOG"),
                digest,
                "需要确认");
    }

    /**
     * 创建测试外发上下文。
     *
     * @param confirmationId 确认标识
     * @param modelCallId 模型调用标识
     * @return 外发上下文
     */
    private Context context(String confirmationId, String modelCallId) {
        return new Context(
                "task-1",
                "conversation-1",
                "step-1",
                modelCallId,
                "分析日志",
                List.of(),
                confirmationId);
    }

    /**
     * 创建已接受确认。
     *
     * @param digest 数据摘要
     * @param modelCallId 模型调用标识
     * @return 确认记录
     */
    private AgentConfirmation confirmation(String digest, String modelCallId) {
        return new AgentConfirmation(
                "confirmation-1",
                "task-1",
                binding(digest, modelCallId),
                AgentConfirmationStatus.ACCEPTED,
                Instant.now().minusSeconds(10),
                Instant.now().plusSeconds(600),
                Instant.now(),
                "用户已确认");
    }

    /**
     * 创建模型外发确认绑定。
     *
     * @param digest 数据摘要
     * @param modelCallId 模型调用标识
     * @return 确认绑定
     */
    private AgentConfirmationBinding binding(String digest, String modelCallId) {
        return new AgentConfirmationBinding(
                "step-1",
                modelCallId,
                "model-data-egress",
                digest,
                AgentConfirmationRiskLevel.MEDIUM,
                "发送日志");
    }

    /**
     * 仅保存一个确认记录的显式测试替身。
     *
     * <p>by AI.Coding</p>
     */
    private record SingleConfirmationStore(AgentConfirmation confirmation) implements AgentConfirmationStore {

        /**
         * 测试不创建新确认。
         *
         * @param value 确认记录
         * @return 不支持
         */
        @Override
        public AgentConfirmation save(AgentConfirmation value) {
            throw new UnsupportedOperationException();
        }

        /**
         * 测试不更新确认。
         *
         * @param value 确认记录
         * @param expectedStatus 预期状态
         * @return 不支持
         */
        @Override
        public AgentConfirmation update(AgentConfirmation value, AgentConfirmationStatus expectedStatus) {
            throw new UnsupportedOperationException();
        }

        /**
         * 按测试绑定返回唯一确认。
         *
         * @param taskId 任务标识
         * @param confirmationId 确认标识
         * @return 匹配确认
         */
        @Override
        public Optional<AgentConfirmation> find(String taskId, String confirmationId) {
            return confirmation.taskId().equals(taskId) && confirmation.confirmationId().equals(confirmationId)
                    ? Optional.of(confirmation)
                    : Optional.empty();
        }
    }
}
