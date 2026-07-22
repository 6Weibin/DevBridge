package com.devbridge.server.ai.agent.compensation;

/**
 * 单个步骤补偿结果。
 *
 * <p>by AI.Coding</p>
 *
 * @param stepId 步骤标识
 * @param status 补偿状态
 * @param message 结果说明
 */
public record AgentCompensationOutcome(
        String stepId, AgentCompensationStatus status, String message) {
}
