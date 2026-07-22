package com.devbridge.server.ai.agent.checkpoint;

import java.time.Instant;

/**
 * 当前 Checkpoint 原子指针。
 *
 * <p>by AI.Coding</p>
 *
 * @param checkpointId Checkpoint 标识
 * @param fileName 文件名
 * @param taskVersion 任务版本
 * @param createdAt 创建时间
 */
public record AgentCheckpointPointer(
        String checkpointId, String fileName, long taskVersion, Instant createdAt) {
}
