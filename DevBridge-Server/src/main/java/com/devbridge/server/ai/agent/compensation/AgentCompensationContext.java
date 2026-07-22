package com.devbridge.server.ai.agent.compensation;

/**
 * 补偿处理器上下文。
 *
 * <p>by AI.Coding</p>
 *
 * @param taskId 任务标识
 * @param stepId 步骤标识
 */
public record AgentCompensationContext(String taskId, String stepId) {
}
