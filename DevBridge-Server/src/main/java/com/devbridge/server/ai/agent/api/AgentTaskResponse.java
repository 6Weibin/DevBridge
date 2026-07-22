package com.devbridge.server.ai.agent.api;

import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import java.time.Instant;

/**
 * Agent Task REST 响应。
 *
 * <p>by AI.Coding</p>
 *
 * @param taskId 任务标识
 * @param conversationId 会话标识
 * @param goal 任务目标
 * @param state 当前状态
 * @param stateReason 状态原因
 * @param version 任务版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record AgentTaskResponse(
        String taskId,
        String conversationId,
        String goal,
        AgentTaskState state,
        String stateReason,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 从中立任务模型构造 REST 响应。
     *
     * @param task 任务模型
     * @return REST 响应
     */
    public static AgentTaskResponse from(AgentTask task) {
        return new AgentTaskResponse(
                task.taskId(), task.conversationId(), task.goal(), task.state(),
                task.stateReason(), task.version(), task.createdAt(), task.updatedAt());
    }
}
