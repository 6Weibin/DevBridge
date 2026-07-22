package com.devbridge.server.ai.agent.checkpoint;

import java.util.Optional;

/**
 * Agent Checkpoint 存储端口。
 *
 * <p>by AI.Coding</p>
 */
public interface AgentCheckpointStore {

    /**
     * 保存新的 Checkpoint 并更新当前指针。
     *
     * @param checkpoint Checkpoint
     * @return 已保存 Checkpoint
     */
    AgentCheckpoint save(AgentCheckpoint checkpoint);

    /**
     * 获取最后一个完整可读 Checkpoint。
     *
     * @param taskId 任务标识
     * @return Checkpoint 存在时返回
     */
    Optional<AgentCheckpoint> findLatest(String taskId);
}
