package com.devbridge.server.ai.agent.api;

import com.devbridge.server.ai.agent.confirmation.AgentConfirmation;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationBinding;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationStatus;
import java.time.Instant;

/**
 * Agent 确认 REST 响应。
 *
 * <p>by AI.Coding</p>
 *
 * @param confirmationId 确认标识
 * @param taskId 任务标识
 * @param binding 工具绑定
 * @param status 确认状态
 * @param createdAt 创建时间
 * @param expiresAt 过期时间
 * @param decidedAt 决策时间
 * @param decisionReason 决策原因
 */
public record AgentConfirmationResponse(
        String confirmationId,
        String taskId,
        AgentConfirmationBinding binding,
        AgentConfirmationStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt,
        String decisionReason) {

    /**
     * 从确认领域模型构造 REST 响应。
     *
     * @param source 确认领域模型
     * @return REST 响应
     */
    public static AgentConfirmationResponse from(AgentConfirmation source) {
        return new AgentConfirmationResponse(
                source.confirmationId(), source.taskId(), source.binding(), source.status(),
                source.createdAt(), source.expiresAt(), source.decidedAt(), source.decisionReason());
    }
}
