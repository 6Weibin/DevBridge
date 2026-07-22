package com.devbridge.server.ai.agent.confirmation;

/**
 * 确认与步骤、工具和规范化参数的不可变绑定。
 *
 * <p>by AI.Coding</p>
 *
 * @param conversationId 所属会话标识
 * @param stepId 步骤标识
 * @param toolCallId 工具调用标识
 * @param toolId 工具标识
 * @param argumentDigest 参数摘要
 * @param riskLevel 风险等级
 * @param impactSummary 用户可读影响摘要
 */
public record AgentConfirmationBinding(
        String conversationId,
        String stepId,
        String toolCallId,
        String toolId,
        String argumentDigest,
        AgentConfirmationRiskLevel riskLevel,
        String impactSummary) {

    /** 兼容旧确认文件缺少会话字段。 */
    public AgentConfirmationBinding {
        conversationId = conversationId == null ? "" : conversationId;
    }

    /** 兼容旧 Java 构造点；旧记录没有会话绑定，API 不会自动放行。 */
    public AgentConfirmationBinding(
            String stepId,
            String toolCallId,
            String toolId,
            String argumentDigest,
            AgentConfirmationRiskLevel riskLevel,
            String impactSummary) {
        this("", stepId, toolCallId, toolId, argumentDigest, riskLevel, impactSummary);
    }
}
