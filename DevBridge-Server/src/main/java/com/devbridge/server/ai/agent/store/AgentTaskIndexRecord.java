package com.devbridge.server.ai.agent.store;

import com.devbridge.server.ai.agent.model.AgentTaskState;
import java.time.Instant;

/**
 * Agent Task 可重建索引记录，只保存分页查询需要的元数据。
 *
 * <p>by AI.Coding</p>
 *
 * @param taskId 任务标识
 * @param state 当前状态
 * @param version 当前版本
 * @param updatedAt 更新时间
 */
public record AgentTaskIndexRecord(String taskId, AgentTaskState state, long version, Instant updatedAt) {
}
