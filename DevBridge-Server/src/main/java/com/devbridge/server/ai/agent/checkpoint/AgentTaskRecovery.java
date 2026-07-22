package com.devbridge.server.ai.agent.checkpoint;

import com.devbridge.server.ai.agent.model.AgentTask;

/**
 * 通过一致性校验的任务和 Checkpoint 组合。
 *
 * <p>by AI.Coding</p>
 *
 * @param task 当前任务快照
 * @param checkpoint 可用于恢复的 Checkpoint
 */
public record AgentTaskRecovery(AgentTask task, AgentCheckpoint checkpoint) {
}
