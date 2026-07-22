package com.devbridge.server.ai.agent.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Agent Task 当前状态快照，不依赖前端消息、模型框架或具体工具类型。
 *
 * <p>by AI.Coding</p>
 *
 * @param taskId 后端生成的任务标识
 * @param conversationId 所属历史会话标识
 * @param goal 用户业务目标
 * @param state 当前任务状态
 * @param stateReason 当前状态原因
 * @param version 任务乐观锁版本
 * @param timing 创建和更新时间
 * @param protectedResult 加密后的最终回复，可空
 */
public record AgentTask(
        String taskId,
        String conversationId,
        String goal,
        AgentTaskState state,
        String stateReason,
        long version,
        AgentTaskTiming timing,
        String protectedResult) {

    /**
     * 校验任务快照的基础不变量，防止非法状态进入 Store。
     */
    public AgentTask {
        Objects.requireNonNull(taskId, "任务标识不能为空");
        Objects.requireNonNull(conversationId, "会话标识不能为空");
        Objects.requireNonNull(goal, "任务目标不能为空");
        Objects.requireNonNull(state, "任务状态不能为空");
        Objects.requireNonNull(stateReason, "状态原因不能为空");
        Objects.requireNonNull(timing, "任务时间不能为空");
        if (taskId.isBlank() || conversationId.isBlank() || goal.isBlank() || stateReason.isBlank()) {
            throw new IllegalArgumentException("任务标识、会话标识、任务目标和状态原因不能为空");
        }
        if (version < 1) {
            throw new IllegalArgumentException("任务版本必须大于零");
        }
    }

    /**
     * 兼容既有构造点，未进入完成状态的任务没有最终结果。
     *
     * @param taskId 任务标识
     * @param conversationId 会话标识
     * @param goal 任务目标
     * @param state 任务状态
     * @param stateReason 状态原因
     * @param version 版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public AgentTask(
            String taskId,
            String conversationId,
            String goal,
            AgentTaskState state,
            String stateReason,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this(taskId, conversationId, goal, state, stateReason, version,
                new AgentTaskTiming(createdAt, updatedAt), null);
    }

    /**
     * 返回任务创建时间，保持现有调用方兼容。
     *
     * @return 创建时间
     */
    public Instant createdAt() {
        return timing.createdAt();
    }

    /**
     * 返回任务更新时间，保持现有调用方兼容。
     *
     * @return 更新时间
     */
    public Instant updatedAt() {
        return timing.updatedAt();
    }

    /**
     * Agent Task 时间值对象，聚合稳定时间字段并为受保护结果保留模型空间。
     *
     * <p>by AI.Coding</p>
     *
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record AgentTaskTiming(Instant createdAt, Instant updatedAt) {

        /**
         * 校验时间字段完整性。
         */
        public AgentTaskTiming {
            Objects.requireNonNull(createdAt, "创建时间不能为空");
            Objects.requireNonNull(updatedAt, "更新时间不能为空");
        }
    }
}
