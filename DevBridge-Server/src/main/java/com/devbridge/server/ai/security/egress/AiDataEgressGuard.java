package com.devbridge.server.ai.security.egress;

import com.devbridge.server.ai.agent.confirmation.AgentConfirmation;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationCoordinator;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationRequest;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationRiskLevel;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationStatus;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationStore;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationTokenService;
import com.devbridge.server.ai.agent.event.AgentEventContext;
import com.devbridge.server.ai.agent.event.AgentEventRequest;
import com.devbridge.server.ai.agent.event.AgentEventScope;
import com.devbridge.server.ai.agent.event.AgentEventSequencer;
import com.devbridge.server.ai.agent.event.AgentEventType;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.runtime.AgentTaskService;
import com.devbridge.server.ai.provider.AiProviderRequest;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Assessment;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Context;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Decision;
import com.devbridge.server.model.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Provider 网络调用前的数据外发强制守卫，统一处理评估、确认和任务事件记录。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AiDataEgressGuard {

    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(30);
    public static final String MODEL_EGRESS_TOOL_ID = "model-data-egress";

    private final AiDataEgressPolicyService policyService;
    private final AgentConfirmationCoordinator confirmationCoordinator;
    private final AgentConfirmationStore confirmationStore;
    private final AgentTaskService taskService;
    private final AgentEventSequencer eventSequencer;
    private final AgentConfirmationTokenService tokenService;

    /**
     * 注入数据外发策略、确认和事件依赖。
     *
     * @param policyService 分类策略
     * @param confirmationCoordinator Agent 确认协调器
     * @param confirmationStore Agent 确认存储
     * @param taskService Agent 任务服务
     * @param eventSequencer Agent 事件序列器
     */
    @Autowired
    public AiDataEgressGuard(
            AiDataEgressPolicyService policyService,
            AgentConfirmationCoordinator confirmationCoordinator,
            AgentConfirmationStore confirmationStore,
            AgentTaskService taskService,
            AgentEventSequencer eventSequencer,
            AgentConfirmationTokenService tokenService) {
        this.policyService = policyService;
        this.confirmationCoordinator = confirmationCoordinator;
        this.confirmationStore = confirmationStore;
        this.taskService = taskService;
        this.eventSequencer = eventSequencer;
        this.tokenService = tokenService;
    }

    /** 创建兼容现有测试的外发守卫。 */
    public AiDataEgressGuard(
            AiDataEgressPolicyService policyService,
            AgentConfirmationCoordinator confirmationCoordinator,
            AgentConfirmationStore confirmationStore,
            AgentTaskService taskService,
            AgentEventSequencer eventSequencer) {
        this(policyService, confirmationCoordinator, confirmationStore, taskService, eventSequencer, null);
    }

    /**
     * 强制执行单次 Provider 外发策略，返回即表示网络调用可以继续。
     *
     * @param request Provider 请求
     * @return 实际允许外发的评估结果
     */
    public Assessment enforce(AiProviderRequest request) {
        Context context = request.egressContext();
        Assessment assessment = policyService.assess(request.config(), context);
        if (assessment.decision() == Decision.ALLOW) {
            record(context, assessment);
            return assessment;
        }
        if (assessment.decision() == Decision.BLOCK) {
            record(context, assessment);
            throw blocked(assessment);
        }
        if (approved(context, assessment)) {
            Assessment approved = approvedAssessment(assessment);
            record(context, approved);
            return approved;
        }
        AgentConfirmation confirmation = requestConfirmation(context, assessment);
        record(context, assessment);
        String confirmationId = confirmation == null ? "" : confirmation.confirmationId();
        throw confirmationRequired(assessment, confirmation, confirmationId);
    }

    /**
     * 校验已接受确认是否仍与当前任务、步骤、模型调用和数据摘要完全绑定。
     *
     * @param context 当前外发上下文
     * @param assessment 当前外发评估
     * @return 确认有效返回 true
     */
    boolean approved(Context context, Assessment assessment) {
        if (!hasTaskBinding(context) || !StringUtils.hasText(context.confirmationId())) {
            return false;
        }
        return confirmationStore.find(context.taskId(), context.confirmationId())
                // Runtime 在启动续跑前将确认标记为 CONSUMED，本次绑定外发仍必须允许通过。
                .filter(confirmation -> confirmation.status() == AgentConfirmationStatus.ACCEPTED
                        || confirmation.status() == AgentConfirmationStatus.CONSUMED)
                .filter(confirmation -> !Instant.now().isAfter(confirmation.expiresAt()))
                .filter(confirmation -> MODEL_EGRESS_TOOL_ID.equals(confirmation.binding().toolId()))
                .filter(confirmation -> context.stepId().equals(confirmation.binding().stepId()))
                .filter(confirmation -> context.modelCallId().equals(confirmation.binding().toolCallId()))
                .filter(confirmation -> assessment.dataDigest().equals(confirmation.binding().argumentDigest()))
                .isPresent();
    }

    /**
     * 为具备完整任务绑定的外发请求创建持久确认。
     *
     * @param context 外发上下文
     * @param assessment 外发评估
     * @return 已创建确认，旧兼容请求返回 null
     */
    private AgentConfirmation requestConfirmation(Context context, Assessment assessment) {
        if (!hasTaskBinding(context)) {
            return null;
        }
        String impact = "将 " + assessment.dataTypes() + " 共 " + assessment.totalBytes()
                + " 字节发送到 " + assessment.provider() + "/" + assessment.model()
                + "；已脱敏类型=" + assessment.maskedTypes();
        return confirmationCoordinator.request(new AgentConfirmationRequest(
                context.taskId(),
                context.stepId(),
                context.modelCallId(),
                MODEL_EGRESS_TOOL_ID,
                assessment.dataDigest(),
                AgentConfirmationRiskLevel.MEDIUM,
                impact,
                CONFIRMATION_TTL));
    }

    /**
     * 将任务级外发决策写入有序事件；旧对话无任务标识时不伪造记录。
     *
     * @param context 外发上下文
     * @param assessment 外发评估
     */
    private void record(Context context, Assessment assessment) {
        if (!hasTaskBinding(context)) {
            return;
        }
        AgentTask task = taskService.findTask(context.taskId()).orElse(null);
        if (task == null) {
            return;
        }
        AgentEventContext eventContext = new AgentEventContext(
                task.conversationId(), null, context.stepId(), null,
                context.confirmationId(), context.modelCallId(), task.version());
        eventSequencer.publish(task.taskId(), new AgentEventRequest(
                AgentEventType.MODEL_EGRESS_DECIDED,
                AgentEventScope.MODEL_CALL,
                eventContext,
                payload(context, assessment),
                Instant.now(),
                "model-egress-policy"));
    }

    /**
     * 构造不含正文的有界事件载荷。
     *
     * @param context 外发上下文
     * @param assessment 外发评估
     * @return 审计载荷
     */
    private Map<String, Object> payload(Context context, Assessment assessment) {
        return Map.of(
                "decision", assessment.decision().name(),
                "provider", assessment.provider(),
                "model", assessment.model(),
                "purpose", context.purpose(),
                "dataTypes", assessment.dataTypes(),
                "maskedTypes", assessment.maskedTypes(),
                "totalBytes", assessment.totalBytes(),
                "dataDigest", assessment.dataDigest());
    }

    /**
     * 将已确认外发转换为实际允许决策。
     *
     * @param assessment 原确认决策
     * @return 已确认允许决策
     */
    private Assessment approvedAssessment(Assessment assessment) {
        return new Assessment(
                Decision.ALLOW,
                assessment.provider(),
                assessment.model(),
                assessment.dataTypes(),
                assessment.totalBytes(),
                assessment.maskedTypes(),
                assessment.dataDigest(),
                "用户已确认本次数据外发");
    }

    /**
     * 判断请求是否具备持久确认和任务事件所需的完整关联标识。
     *
     * @param context 外发上下文
     * @return 标识完整返回 true
     */
    private boolean hasTaskBinding(Context context) {
        return context != null
                && StringUtils.hasText(context.taskId())
                && StringUtils.hasText(context.stepId())
                && StringUtils.hasText(context.modelCallId());
    }

    /**
     * 构造数据外发阻断错误。
     *
     * @param assessment 外发评估
     * @return 业务异常
     */
    private BusinessException blocked(Assessment assessment) {
        return new BusinessException(
                "AI_DATA_EGRESS_BLOCKED",
                "当前数据不允许发送到所选模型",
                HttpStatus.FORBIDDEN,
                detail(assessment, ""));
    }

    /**
     * 构造数据外发确认错误。
     *
     * @param assessment 外发评估
     * @param confirmationId 确认标识
     * @return 业务异常
     */
    private BusinessException confirmationRequired(
            Assessment assessment,
            AgentConfirmation confirmation,
            String confirmationId) {
        String token = confirmation == null || tokenService == null ? "" :
                "agent-confirmation:" + confirmation.taskId() + ":"
                        + confirmation.confirmationId() + ":" + tokenService.issue(confirmation);
        return new ConfirmationRequiredException(
                detail(assessment, confirmationId), token, assessment.reason());
    }

    /** 数据外发确认异常携带可直接展示的签名令牌，但不包含原始敏感正文。 */
    public static final class ConfirmationRequiredException extends BusinessException {

        private final String confirmationToken;
        private final String impact;
        private final java.util.concurrent.atomic.AtomicBoolean published =
                new java.util.concurrent.atomic.AtomicBoolean();

        /** 创建数据外发确认异常。 */
        private ConfirmationRequiredException(String detail, String confirmationToken, String impact) {
            super("AI_DATA_EGRESS_CONFIRMATION_REQUIRED",
                    "敏感数据发送到外部模型前需要用户确认",
                    HttpStatus.CONFLICT, detail);
            this.confirmationToken = confirmationToken;
            this.impact = impact;
        }

        /** 返回前端确认卡片使用的签名令牌。 */
        public String confirmationToken() {
            return confirmationToken;
        }

        /** 返回不含正文的数据外发影响摘要。 */
        public String impact() {
            return impact;
        }

        /** 标记确认事件已经实时发布给当前客户端。 */
        public void markPublished() {
            published.set(true);
        }

        /** 判断迟到的框架异常是否已经由实时确认事件处理。 */
        public boolean published() {
            return published.get();
        }
    }

    /**
     * 构造不含正文和密钥的诊断详情。
     *
     * @param assessment 外发评估
     * @param confirmationId 确认标识
     * @return 结构化摘要文本
     */
    private String detail(Assessment assessment, String confirmationId) {
        return "provider=" + assessment.provider()
                + ", model=" + assessment.model()
                + ", dataTypes=" + assessment.dataTypes()
                + ", totalBytes=" + assessment.totalBytes()
                + ", maskedTypes=" + assessment.maskedTypes()
                + ", dataDigest=" + assessment.dataDigest()
                + (confirmationId.isBlank() ? "" : ", confirmationId=" + confirmationId);
    }
}
