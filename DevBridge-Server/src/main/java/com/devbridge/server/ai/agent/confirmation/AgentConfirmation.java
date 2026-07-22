package com.devbridge.server.ai.agent.confirmation;

import java.time.Instant;

/**
 * 持久化 Agent 确认记录。
 *
 * <p>by AI.Coding</p>
 *
 * @param confirmationId 确认标识
 * @param taskId 任务标识
 * @param binding 确认绑定
 * @param status 当前状态
 * @param createdAt 创建时间
 * @param expiresAt 过期时间
 * @param decidedAt 决策时间，可空
 * @param decisionReason 决策原因，可空
 */
public record AgentConfirmation(
        String confirmationId,
        String taskId,
        AgentConfirmationBinding binding,
        AgentConfirmationStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt,
        String decisionReason) {
}
