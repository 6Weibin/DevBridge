package com.devbridge.server.ai.agent.checkpoint;

import com.devbridge.server.ai.agent.model.AgentTaskState;
import java.time.Instant;

/**
 * Agent Task 持久恢复点。
 *
 * <p>by AI.Coding</p>
 *
 * @param checkpointId Checkpoint 标识
 * @param taskId 任务标识
 * @param taskVersion 任务版本
 * @param eventSequence 已包含的最后事件序号
 * @param taskState 保存时任务状态
 * @param recoveryState 恢复控制状态
 * @param createdAt 创建时间
 */
public record AgentCheckpoint(
        String checkpointId,
        String taskId,
        long taskVersion,
        long eventSequence,
        AgentTaskState taskState,
        AgentRecoveryState recoveryState,
        Instant createdAt) {
}
