package com.devbridge.server.ai.agent.confirmation;

import java.time.Duration;

/**
 * Runtime 创建敏感操作确认的命令。
 *
 * <p>by AI.Coding</p>
 *
 * @param taskId 任务标识
 * @param stepId 步骤标识
 * @param toolCallId 工具调用标识
 * @param toolId 工具标识
 * @param argumentDigest 规范化参数摘要
 * @param riskLevel 风险等级
 * @param impactSummary 影响摘要
 * @param ttl 确认有效期
 */
public record AgentConfirmationRequest(
        String taskId,
        String stepId,
        String toolCallId,
        String toolId,
        String argumentDigest,
        AgentConfirmationRiskLevel riskLevel,
        String impactSummary,
        Duration ttl) {
}
