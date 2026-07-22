package com.devbridge.server.ai.agent.runtime;

import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointService;
import com.devbridge.server.ai.agent.checkpoint.AgentRecoveryState;
import com.devbridge.server.ai.agent.checkpoint.AgentToolCallCheckpoint;
import com.devbridge.server.ai.agent.checkpoint.AgentToolCallCheckpointStatus;
import com.devbridge.server.ai.agent.event.AgentEventStore;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Agent 步骤幂等服务，以持久 Checkpoint 作为工具执行真相源。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AgentStepIdempotencyService {

    private static final int LOCK_STRIPES = 64;
    private static final Set<AgentToolCallCheckpointStatus> TERMINAL_STATUSES = Set.of(
            AgentToolCallCheckpointStatus.SUCCEEDED,
            AgentToolCallCheckpointStatus.FAILED,
            AgentToolCallCheckpointStatus.CANCELED,
            AgentToolCallCheckpointStatus.UNKNOWN);

    private final AgentTaskService taskService;
    private final AgentCheckpointService checkpointService;
    private final AgentEventStore eventStore;
    private final Object[] locks = createLocks();

    /**
     * 注入任务、Checkpoint 和事件依赖。
     *
     * @param taskService 任务服务
     * @param checkpointService Checkpoint 服务
     * @param eventStore 事件 Store
     */
    public AgentStepIdempotencyService(
            AgentTaskService taskService,
            AgentCheckpointService checkpointService,
            AgentEventStore eventStore) {
        this.taskService = taskService;
        this.checkpointService = checkpointService;
        this.eventStore = eventStore;
    }

    /**
     * 在风险策略执行前保存原始工具请求，确保进入确认状态后仍可确定性恢复。
     *
     * @param request 工具请求快照
     * @return 已保存或已存在的工具快照
     */
    public AgentToolCallCheckpoint rememberRequest(AgentToolExecutionRequest request) {
        validateRequestIdentity(request);
        if (!StringUtils.hasText(request.protectedRequest())) {
            throw new IllegalArgumentException("工具恢复请求不能为空");
        }
        synchronized (lockFor(request.taskId())) {
            AgentTask task = requireExecutableTask(request.taskId());
            AgentRecoveryState state = currentRecovery(task.taskId());
            AgentToolCallCheckpoint existing = state.toolCalls().get(request.toolCallId());
            if (existing != null) {
                validateDigest(existing, request);
                if (StringUtils.hasText(existing.protectedRequest())) {
                    return existing;
                }
            }
            AgentToolCallCheckpoint requested = new AgentToolCallCheckpoint(
                    request.toolCallId(), request.stepId(), AgentToolCallCheckpointStatus.REQUESTED,
                    null, request.requestDigest(), null, false, request.protectedRequest());
            save(task, replace(state, requested));
            return requested;
        }
    }

    /**
     * 加密工具恢复载荷，统一复用 Checkpoint 的密钥和目录策略。
     *
     * @param value 待保护对象
     * @return 加密载荷
     */
    public String protect(Object value) {
        return checkpointService.protect(value);
    }

    /**
     * 解密工具恢复载荷。
     *
     * @param protectedValue 加密载荷
     * @param type 目标类型
     * @param <T> 目标类型
     * @return 恢复对象
     */
    public <T> T restore(String protectedValue, Class<T> type) {
        return checkpointService.restore(protectedValue, type);
    }

    /**
     * 预留工具调用；重复调用返回已有记录且不允许再次执行。
     *
     * @param request 工具执行请求
     * @return 幂等决策
     */
    public AgentToolExecutionDecision reserve(AgentToolExecutionRequest request) {
        validateRequest(request);
        synchronized (lockFor(request.taskId())) {
            AgentTask task = requireExecutableTask(request.taskId());
            AgentRecoveryState state = currentRecovery(task.taskId());
            AgentToolCallCheckpoint existing = findExisting(state, request);
            if (existing != null) {
                validateDigest(existing, request);
                if (canUpgradeConfirmationRequest(existing)) {
                    AgentToolCallCheckpoint reserved = reserved(request, existing);
                    save(task, replace(state, reserved));
                    return new AgentToolExecutionDecision(true, reserved);
                }
                return new AgentToolExecutionDecision(false, existing);
            }
            AgentToolCallCheckpoint reserved = reserved(request, null);
            save(task, replace(state, reserved));
            return new AgentToolExecutionDecision(true, reserved);
        }
    }

    /**
     * 持久化工具最终结果，后续重复请求直接复用该结果。
     *
     * @param taskId 任务标识
     * @param toolCallId 工具调用标识
     * @param status 最终状态
     * @param resultReference 结果引用
     * @param sideEffectVerified 是否验证副作用
     * @return 最终工具快照
     */
    public AgentToolCallCheckpoint complete(
            String taskId,
            String toolCallId,
            AgentToolCallCheckpointStatus status,
            String resultReference,
            boolean sideEffectVerified) {
        if (!TERMINAL_STATUSES.contains(status)) {
            throw new IllegalArgumentException("工具完成状态不合法");
        }
        synchronized (lockFor(taskId)) {
            AgentTask task = requireCompletableTask(taskId);
            AgentRecoveryState state = currentRecovery(taskId);
            AgentToolCallCheckpoint current = state.toolCalls().get(toolCallId);
            if (current == null) {
                throw new IllegalArgumentException("工具调用尚未预留: " + toolCallId);
            }
            if (TERMINAL_STATUSES.contains(current.status())) {
                return validateSameCompletion(current, status, resultReference);
            }
            AgentToolCallCheckpoint completed = new AgentToolCallCheckpoint(
                    current.toolCallId(), current.stepId(), status, current.idempotencyKey(),
                    current.requestDigest(), resultReference, sideEffectVerified, current.protectedRequest());
            save(task, replace(state, completed));
            return completed;
        }
    }

    /**
     * 查找相同调用标识或幂等键的现有记录。
     *
     * @param state 当前恢复状态
     * @param request 执行请求
     * @return 现有记录，无匹配时返回 null
     */
    private AgentToolCallCheckpoint findExisting(
            AgentRecoveryState state, AgentToolExecutionRequest request) {
        AgentToolCallCheckpoint byCall = state.toolCalls().get(request.toolCallId());
        AgentToolCallCheckpoint byKey = state.toolCalls().values().stream()
                .filter(value -> request.idempotencyKey().equals(value.idempotencyKey()))
                .findFirst()
                .orElse(null);
        if (byCall != null && byKey != null && !byCall.toolCallId().equals(byKey.toolCallId())) {
            throw new IllegalStateException("工具调用标识和幂等键指向不同记录");
        }
        return byCall != null ? byCall : byKey;
    }

    /**
     * 校验重复请求没有替换原工具参数。
     *
     * @param existing 现有记录
     * @param request 新请求
     */
    private void validateDigest(
            AgentToolCallCheckpoint existing, AgentToolExecutionRequest request) {
        if (!request.requestDigest().equals(existing.requestDigest())) {
            throw new IllegalStateException("幂等请求参数摘要发生变化");
        }
        if (!request.stepId().equals(existing.stepId())) {
            throw new IllegalStateException("幂等请求步骤标识发生变化");
        }
    }

    /**
     * 判断确认阶段请求是否可升级为实际执行预留。
     *
     * @param existing 现有工具快照
     * @return 可升级返回 true
     */
    private boolean canUpgradeConfirmationRequest(AgentToolCallCheckpoint existing) {
        return existing.status() == AgentToolCallCheckpointStatus.REQUESTED
                && !StringUtils.hasText(existing.idempotencyKey());
    }

    /**
     * 构造工具预留记录。
     *
     * @param request 执行请求
     * @return 预留记录
     */
    private AgentToolCallCheckpoint reserved(
            AgentToolExecutionRequest request, AgentToolCallCheckpoint existing) {
        String protectedRequest = StringUtils.hasText(request.protectedRequest())
                ? request.protectedRequest()
                : existing == null ? null : existing.protectedRequest();
        return new AgentToolCallCheckpoint(
                request.toolCallId(), request.stepId(), AgentToolCallCheckpointStatus.RESERVED,
                request.idempotencyKey(), request.requestDigest(), null, false, protectedRequest);
    }

    /**
     * 替换恢复状态中的单个工具快照。
     *
     * @param state 原恢复状态
     * @param checkpoint 新工具快照
     * @return 新恢复状态
     */
    private AgentRecoveryState replace(
            AgentRecoveryState state, AgentToolCallCheckpoint checkpoint) {
        Map<String, AgentToolCallCheckpoint> toolCalls = new HashMap<>(state.toolCalls());
        toolCalls.put(checkpoint.toolCallId(), checkpoint);
        return new AgentRecoveryState(
                checkpoint.stepId(), state.completedStepIds(), toolCalls,
                state.pendingConfirmationId(), state.pendingInputKey(), state.continuationContext(),
                state.continuationState());
    }

    /**
     * 保存新的幂等 Checkpoint。
     *
     * @param task 当前任务
     * @param state 新恢复状态
     */
    private void save(AgentTask task, AgentRecoveryState state) {
        checkpointService.saveCheckpoint(
                task.taskId(), eventStore.lastSequence(task.taskId()), state);
    }

    /**
     * 加载恢复状态，无 Checkpoint 时返回空状态。
     *
     * @param taskId 任务标识
     * @return 恢复状态
     */
    private AgentRecoveryState currentRecovery(String taskId) {
        return checkpointService.loadRecovery(taskId)
                .map(recovery -> recovery.checkpoint().recoveryState())
                .orElseGet(() -> new AgentRecoveryState(null, List.of(), Map.of(), null, null));
    }

    /**
     * 获取允许执行工具的任务状态。
     *
     * @param taskId 任务标识
     * @return 当前任务
     */
    private AgentTask requireExecutableTask(String taskId) {
        AgentTask task = taskService.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 任务不存在: " + taskId));
        if (task.state() != AgentTaskState.RUNNING && task.state() != AgentTaskState.RETRYING) {
            throw new IllegalStateException("当前任务状态不允许执行工具: " + task.state());
        }
        return task;
    }

    /**
     * 获取允许落盘工具终态的任务；取消传播后仍需保存工具取消结果。
     *
     * @param taskId 任务标识
     * @return 当前任务
     */
    private AgentTask requireCompletableTask(String taskId) {
        AgentTask task = taskService.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 任务不存在: " + taskId));
        if (task.state() != AgentTaskState.RUNNING
                && task.state() != AgentTaskState.RETRYING
                && task.state() != AgentTaskState.CANCELED) {
            throw new IllegalStateException("当前任务状态不允许保存工具结果: " + task.state());
        }
        return task;
    }

    /**
     * 校验工具执行请求。
     *
     * @param request 工具执行请求
     */
    private void validateRequest(AgentToolExecutionRequest request) {
        validateRequestIdentity(request);
        if (!StringUtils.hasText(request.idempotencyKey())) {
            throw new IllegalArgumentException("工具幂等请求字段不完整");
        }
    }

    /**
     * 校验工具请求身份和参数摘要，预保存阶段不强制要求幂等键。
     *
     * @param request 工具执行请求
     */
    private void validateRequestIdentity(AgentToolExecutionRequest request) {
        if (request == null || !StringUtils.hasText(request.taskId())
                || !StringUtils.hasText(request.stepId()) || !StringUtils.hasText(request.toolCallId())
                || !StringUtils.hasText(request.requestDigest())) {
            throw new IllegalArgumentException("工具请求身份字段不完整");
        }
    }

    /**
     * 校验重复完成请求与已有终态一致。
     *
     * @param current 当前终态记录
     * @param status 新状态
     * @param resultReference 新结果引用
     * @return 当前终态记录
     */
    private AgentToolCallCheckpoint validateSameCompletion(
            AgentToolCallCheckpoint current,
            AgentToolCallCheckpointStatus status,
            String resultReference) {
        if (current.status() != status
                || !java.util.Objects.equals(current.resultReference(), resultReference)) {
            throw new IllegalStateException("工具调用已经以不同结果完成");
        }
        return current;
    }

    /**
     * 获取固定任务锁分片。
     *
     * @param taskId 任务标识
     * @return 锁对象
     */
    private Object lockFor(String taskId) {
        return locks[Math.floorMod(taskId.hashCode(), locks.length)];
    }

    /**
     * 初始化固定锁分片。
     *
     * @return 锁分片
     */
    private static Object[] createLocks() {
        Object[] values = new Object[LOCK_STRIPES];
        for (int index = 0; index < values.length; index++) {
            values[index] = new Object();
        }
        return values;
    }
}
