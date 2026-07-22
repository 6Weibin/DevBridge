package com.devbridge.server.ai.agent.runtime;

import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Agent Task 确定性状态机，集中维护全部合法转换。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AgentTaskStateMachine {

    private final Map<AgentTaskState, Set<AgentTaskState>> transitions = new EnumMap<>(AgentTaskState.class);

    /**
     * 根据已确认规格初始化合法状态转换表。
     */
    public AgentTaskStateMachine() {
        transitions.put(AgentTaskState.CREATED, Set.of(AgentTaskState.PLANNING, AgentTaskState.CANCELED));
        transitions.put(AgentTaskState.PLANNING, Set.of(
                AgentTaskState.RUNNING, AgentTaskState.WAITING_INPUT,
                AgentTaskState.FAILED, AgentTaskState.CANCELED));
        transitions.put(AgentTaskState.RUNNING, Set.of(
                AgentTaskState.WAITING_CONFIRMATION, AgentTaskState.WAITING_INPUT,
                AgentTaskState.PAUSED, AgentTaskState.RETRYING, AgentTaskState.COMPLETED,
                AgentTaskState.FAILED, AgentTaskState.CANCELED));
        transitions.put(AgentTaskState.WAITING_CONFIRMATION, Set.of(
                AgentTaskState.RUNNING, AgentTaskState.FAILED, AgentTaskState.CANCELED));
        transitions.put(AgentTaskState.WAITING_INPUT, Set.of(
                AgentTaskState.PLANNING, AgentTaskState.RUNNING,
                AgentTaskState.FAILED, AgentTaskState.CANCELED));
        transitions.put(AgentTaskState.PAUSED, Set.of(
                AgentTaskState.PLANNING, AgentTaskState.RUNNING, AgentTaskState.CANCELED));
        transitions.put(AgentTaskState.RETRYING, Set.of(
                AgentTaskState.RUNNING, AgentTaskState.FAILED, AgentTaskState.CANCELED));
        transitions.put(AgentTaskState.COMPLETED, Set.of());
        transitions.put(AgentTaskState.FAILED, Set.of());
        transitions.put(AgentTaskState.CANCELED, Set.of());
    }

    /**
     * 校验并生成下一个不可变任务快照。
     *
     * @param current 当前任务
     * @param target 目标状态
     * @param reason 状态变化原因
     * @return 版本递增后的任务快照
     */
    public AgentTask transition(AgentTask current, AgentTaskState target, String reason) {
        return transition(current, target, reason, current.protectedResult());
    }

    /**
     * 校验状态转换并同时写入受保护任务结果。
     *
     * @param current 当前任务
     * @param target 目标状态
     * @param reason 状态原因
     * @param protectedResult 加密后的最终结果
     * @return 版本递增后的任务快照
     */
    public AgentTask transition(
            AgentTask current,
            AgentTaskState target,
            String reason,
            String protectedResult) {
        if (target == null) {
            throw new IllegalArgumentException("目标状态不能为空");
        }
        if (!StringUtils.hasText(reason)) {
            throw new IllegalArgumentException("状态变化原因不能为空");
        }
        Set<AgentTaskState> allowed = transitions.getOrDefault(current.state(), Set.of());
        if (!allowed.contains(target)) {
            throw transitionError(current.state(), target);
        }
        return new AgentTask(
                current.taskId(), current.conversationId(), current.goal(), target, reason.trim(),
                current.version() + 1,
                new AgentTask.AgentTaskTiming(current.createdAt(), Instant.now()),
                protectedResult);
    }

    /**
     * 构造区分终态和普通非法跳转的诊断异常。
     *
     * @param source 当前状态
     * @param target 目标状态
     * @return 状态转换异常
     */
    private AgentTaskTransitionException transitionError(AgentTaskState source, AgentTaskState target) {
        if (Set.of(AgentTaskState.COMPLETED, AgentTaskState.FAILED, AgentTaskState.CANCELED).contains(source)) {
            return new AgentTaskTransitionException("终态 " + source + " 不能转换为 " + target);
        }
        return new AgentTaskTransitionException("非法状态转换: " + source + " -> " + target);
    }
}
