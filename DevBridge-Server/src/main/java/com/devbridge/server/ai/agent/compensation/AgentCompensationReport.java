package com.devbridge.server.ai.agent.compensation;

import java.util.List;

/**
 * Agent 任务补偿报告。
 *
 * <p>by AI.Coding</p>
 *
 * @param successful 是否全部可执行补偿成功
 * @param outcomes 步骤补偿结果
 * @param irreversibleImpacts 不可逆剩余影响
 */
public record AgentCompensationReport(
        boolean successful,
        List<AgentCompensationOutcome> outcomes,
        List<String> irreversibleImpacts) {

    /**
     * 固化补偿报告集合。
     */
    public AgentCompensationReport {
        outcomes = List.copyOf(outcomes);
        irreversibleImpacts = List.copyOf(irreversibleImpacts);
    }
}
