package com.devbridge.server.ai.agent.compensation;

/**
 * 已完成且可能需要补偿的 Agent 步骤。
 *
 * <p>by AI.Coding</p>
 *
 * @param stepId 步骤标识
 * @param ordinal 原执行顺序
 * @param compensation 补偿动作，可空
 * @param irreversible 是否不可逆
 * @param impactSummary 不可逆影响摘要
 */
public record AgentCompletedStep(
        String stepId,
        int ordinal,
        AgentCompensationAction compensation,
        boolean irreversible,
        String impactSummary) {
}
