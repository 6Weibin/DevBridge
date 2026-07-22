package com.devbridge.server.ai.agent.runtime;

import com.devbridge.server.ai.agent.event.AgentEventContext;
import com.devbridge.server.ai.agent.event.AgentEventRequest;
import com.devbridge.server.ai.agent.event.AgentEventScope;
import com.devbridge.server.ai.agent.event.AgentEventSequencer;
import com.devbridge.server.ai.agent.event.AgentEventStore;
import com.devbridge.server.ai.agent.event.AgentEventStoreException;
import com.devbridge.server.ai.agent.event.AgentEventType;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointService;
import com.devbridge.server.ai.agent.checkpoint.AgentRecoveryState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Agent Task 应用编排服务，保证任务变化和可见事件使用同一后端入口。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AgentTaskApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskApplicationService.class);
    private static final int MAX_EVENT_GOAL_SUMMARY = 500;
    private static final Set<AgentTaskState> TERMINAL_STATES = Set.of(
            AgentTaskState.COMPLETED, AgentTaskState.FAILED, AgentTaskState.CANCELED);

    private final AgentTaskService taskService;
    private final AgentEventSequencer eventSequencer;
    private final AgentTaskCancellationCoordinator cancellationCoordinator;
    private final AgentEventStore eventStore;
    private final AgentCheckpointService checkpointService;

    /**
     * 注入任务服务和事件序列器。
     *
     * @param taskService 任务服务
     * @param eventSequencer 事件序列器
     * @param cancellationCoordinator 任务取消协调器
     */
    @Autowired
    public AgentTaskApplicationService(
            AgentTaskService taskService,
            AgentEventSequencer eventSequencer,
            AgentTaskCancellationCoordinator cancellationCoordinator,
            AgentEventStore eventStore,
            AgentCheckpointService checkpointService) {
        this.taskService = taskService;
        this.eventSequencer = eventSequencer;
        this.cancellationCoordinator = cancellationCoordinator;
        this.eventStore = eventStore;
        this.checkpointService = checkpointService;
    }

    /**
     * 创建兼容测试和显式装配的任务应用服务。
     *
     * @param taskService 任务服务
     * @param eventSequencer 事件序列器
     * @param cancellationCoordinator 取消协调器
     */
    public AgentTaskApplicationService(
            AgentTaskService taskService,
            AgentEventSequencer eventSequencer,
            AgentTaskCancellationCoordinator cancellationCoordinator) {
        this(taskService, eventSequencer, cancellationCoordinator, null, null);
    }

    /** 创建兼容显式事件 Store 装配的任务应用服务。 */
    public AgentTaskApplicationService(
            AgentTaskService taskService,
            AgentEventSequencer eventSequencer,
            AgentTaskCancellationCoordinator cancellationCoordinator,
            AgentEventStore eventStore) {
        this(taskService, eventSequencer, cancellationCoordinator, eventStore, null);
    }

    /**
     * 创建任务并发布第一个持久事件。
     *
     * @param command 创建命令
     * @return 已创建任务
     */
    public AgentTask createTask(CreateAgentTaskCommand command) {
        return createTaskResult(command).task();
    }

    /**
     * 幂等创建任务并公开本次创建标识，供 Chat 链路决定执行还是重放。
     *
     * @param command 创建命令
     * @return 应用层创建结果
     */
    public TaskOperationResult createTaskResult(CreateAgentTaskCommand command) {
        AgentTaskService.TaskCreationResult result = taskService.createTaskResult(command);
        AgentTask task = result.task();
        if (!result.created()) {
            return new TaskOperationResult(task, false);
        }
        eventSequencer.publish(task.taskId(), eventRequest(
                task, AgentEventType.TASK_CREATED,
                Map.of("state", task.state().name(), "goalSummary", goalSummary(task.goal()))));
        cancellationCoordinator.open(task.taskId());
        return new TaskOperationResult(task, true);
    }

    /**
     * 创建并启动一个可执行任务，统一完成 CREATED、PLANNING 和 RUNNING 状态推进。
     *
     * @param command 创建命令
     * @return 运行中任务
     */
    public AgentTask startTask(CreateAgentTaskCommand command) {
        return startTaskResult(command).task();
    }

    /**
     * 幂等创建并启动任务，重复请求返回原状态且不重新推进。
     *
     * @param command 创建命令
     * @return 启动结果
     */
    public TaskOperationResult startTaskResult(CreateAgentTaskCommand command) {
        TaskOperationResult creation = createTaskResult(command);
        AgentTask created = creation.task();
        if (!creation.changed()) {
            // 网络重试复用已运行或已终止任务，不允许再次推进状态或重放工具。
            return creation;
        }
        AgentTask planning = transition(created, AgentTaskState.PLANNING, "正在规划任务", AgentEventType.TASK_STATE_CHANGED);
        AgentTask running = transition(planning, AgentTaskState.RUNNING, "正在执行任务", AgentEventType.TASK_STATE_CHANGED);
        return new TaskOperationResult(running, true);
    }

    /**
     * 将确认暂停恢复到同一任务，供后端自动续跑复用原任务标识。
     *
     * @param taskId 任务标识
     * @param conversationId 会话标识
     * @return 运行中任务
     */
    public AgentTask resumeTask(String taskId, String conversationId) {
        AgentTask current = requireTask(taskId);
        if (!current.conversationId().equals(conversationId)) {
            throw new IllegalArgumentException("Agent 任务与当前会话不匹配");
        }
        if (current.state() == AgentTaskState.RUNNING) {
            return current;
        }
        if (current.state() != AgentTaskState.WAITING_CONFIRMATION) {
            throw new IllegalStateException("只有等待确认的任务可以恢复");
        }
        return transition(current, AgentTaskState.RUNNING, "用户确认后继续执行", AgentEventType.TASK_RESUMED);
    }

    /**
     * 将运行中任务标记为等待旧确认兼容链路，M2.5-04 将替换为正式 Agent Confirmation。
     *
     * @param taskId 任务标识
     * @return 等待确认任务
     */
    public AgentTask waitForConfirmation(String taskId) {
        AgentTask current = requireTask(taskId);
        if (current.state() == AgentTaskState.WAITING_CONFIRMATION) {
            return current;
        }
        return transition(
                current, AgentTaskState.WAITING_CONFIRMATION,
                "等待用户确认敏感操作", AgentEventType.TASK_STATE_CHANGED);
    }

    /**
     * 保存待补充输入恢复点并进入等待状态。
     *
     * @param taskId 任务标识
     * @param inputKey 输入项标识
     * @param reason 用户可读原因
     * @return 等待输入任务
     */
    public AgentTask waitForInput(String taskId, String inputKey, String reason) {
        AgentTask current = requireTask(taskId);
        if (current.state() == AgentTaskState.WAITING_INPUT) {
            return current;
        }
        String key = requiredText(inputKey, "输入项标识不能为空");
        AgentRecoveryState recovery = recoveryState(taskId);
        AgentRecoveryState waiting = copyRecovery(recovery, key, recovery.protectedInputValue());
        saveCheckpoint(current, waiting);
        AgentTask updated = transition(
                current, AgentTaskState.WAITING_INPUT,
                requiredText(reason, "等待输入原因不能为空"), AgentEventType.INPUT_REQUIRED);
        saveCheckpoint(updated, waiting);
        return updated;
    }

    /**
     * 接受与原等待项绑定的用户输入并自动恢复任务。
     *
     * @param taskId 任务标识
     * @param conversationId 会话标识
     * @param inputKey 输入项标识
     * @param value 用户补充内容
     * @return 已恢复任务和输入恢复状态
     */
    public AgentInputAcceptance acceptInput(
            String taskId, String conversationId, String inputKey, String value) {
        AgentTask current = requireTask(taskId);
        requireConversation(current, conversationId);
        if (current.state() != AgentTaskState.WAITING_INPUT) {
            throw new IllegalStateException("只有等待输入的任务可以提交补充信息");
        }
        AgentRecoveryState recovery = recoveryState(taskId);
        if (!requiredText(inputKey, "输入项标识不能为空").equals(recovery.pendingInputKey())) {
            throw new IllegalArgumentException("补充输入与当前等待项不匹配");
        }
        String protectedValue = checkpointService.protect(requiredText(value, "补充输入不能为空"));
        AgentRecoveryState accepted = copyRecovery(recovery, null, protectedValue);
        AgentTask planning = transition(current, AgentTaskState.PLANNING, "已收到补充输入", AgentEventType.INPUT_PROVIDED);
        AgentTask running = transition(planning, AgentTaskState.RUNNING, "根据补充输入继续执行", AgentEventType.TASK_RESUMED);
        cancellationCoordinator.open(taskId);
        saveCheckpoint(running, accepted);
        return new AgentInputAcceptance(running, accepted, inputKey.trim());
    }

    /**
     * 暂停运行中任务，停止新步骤并传播当前模型、工具和进程取消信号。
     *
     * @param taskId 任务标识
     * @return 已暂停任务
     */
    public AgentTask pauseTask(String taskId) {
        AgentTask current = requireTask(taskId);
        if (current.state() == AgentTaskState.PAUSED) {
            return current;
        }
        if (current.state() != AgentTaskState.RUNNING) {
            throw new IllegalStateException("只有运行中的任务可以暂停");
        }
        eventSequencer.publish(taskId, eventRequest(
                current, AgentEventType.TASK_PAUSE_REQUESTED, Map.of("reason", "用户暂停")));
        AgentRecoveryState recovery = recoveryState(taskId);
        cancellationCoordinator.cancel(taskId, "用户暂停");
        AgentTask paused = transition(current, AgentTaskState.PAUSED, "用户暂停", AgentEventType.TASK_STATE_CHANGED);
        cancellationCoordinator.close(taskId);
        saveCheckpoint(paused, recovery);
        return paused;
    }

    /**
     * 恢复用户主动暂停的任务并重新进入规划，避免盲目重放旧命令。
     *
     * @param taskId 任务标识
     * @param conversationId 会话标识
     * @return 恢复后的运行任务
     */
    public AgentTask resumePausedTask(String taskId, String conversationId) {
        return resumePausedTaskResult(taskId, conversationId).task();
    }

    /**
     * 幂等恢复暂停任务，并公开本次请求是否真正取得续跑执行权。
     *
     * @param taskId 任务标识
     * @param conversationId 会话标识
     * @return 恢复结果
     */
    public TaskOperationResult resumePausedTaskResult(String taskId, String conversationId) {
        AgentTask current = requireTask(taskId);
        requireConversation(current, conversationId);
        if (current.state() == AgentTaskState.RUNNING) {
            return new TaskOperationResult(current, false);
        }
        if (current.state() != AgentTaskState.PAUSED) {
            throw new IllegalStateException("只有已暂停任务可以恢复");
        }
        AgentRecoveryState recovery = recoveryState(taskId);
        cancellationCoordinator.open(taskId);
        AgentTask planning = transition(current, AgentTaskState.PLANNING, "恢复后重新规划", AgentEventType.TASK_RESUMED);
        AgentTask running = transition(planning, AgentTaskState.RUNNING, "任务已恢复", AgentEventType.TASK_STATE_CHANGED);
        saveCheckpoint(running, recovery);
        return new TaskOperationResult(running, true);
    }

    /**
     * 根据持久状态把服务重启前的执行态恢复到可重新调度状态。
     *
     * @param taskId 任务标识
     * @return 恢复后的任务和是否需要启动执行器
     */
    public TaskOperationResult recoverAfterRestart(String taskId) {
        AgentTask current = requireTask(taskId);
        cancellationCoordinator.open(taskId);
        return switch (current.state()) {
            case CREATED -> {
                AgentTask planning = transition(
                        current, AgentTaskState.PLANNING,
                        "服务重启后重新规划", AgentEventType.TASK_RESUMED);
                yield new TaskOperationResult(transition(
                        planning, AgentTaskState.RUNNING,
                        "服务重启后继续执行", AgentEventType.TASK_STATE_CHANGED), true);
            }
            case PLANNING, RETRYING -> new TaskOperationResult(transition(
                    current, AgentTaskState.RUNNING,
                    "服务重启后继续执行", AgentEventType.TASK_RESUMED), true);
            case RUNNING -> new TaskOperationResult(current, true);
            default -> new TaskOperationResult(current, false);
        };
    }

    /**
     * 将任务标记为完成；重复终态回调保持幂等。
     *
     * @param taskId 任务标识
     * @return 完成任务
     */
    public AgentTask completeTask(String taskId) {
        return completeTask(taskId, null);
    }

    /**
     * 将最终回复和完成状态写入同一任务快照，避免出现无结果的 COMPLETED 任务。
     *
     * @param taskId 任务标识
     * @param protectedResult 加密后的最终回复
     * @return 完成任务
     */
    public AgentTask completeTask(String taskId, String protectedResult) {
        AgentTask current = requireTask(taskId);
        if (TERMINAL_STATES.contains(current.state())) {
            return current;
        }
        if (cancellationRequested(taskId)) {
            // 用户取消已经取得执行权，迟到的 Provider 完成不能抢先提交 COMPLETED。
            return current;
        }
        try {
            return transition(
                    current, AgentTaskState.COMPLETED, "任务执行完成",
                    AgentEventType.TASK_COMPLETED, protectedResult);
        } finally {
            cancellationCoordinator.close(taskId);
        }
    }

    /**
     * 将任务标记为失败；终态后的异步错误回调不得覆盖既有结果。
     *
     * @param taskId 任务标识
     * @param reason 失败原因
     * @return 失败任务
     */
    public AgentTask failTask(String taskId, String reason) {
        AgentTask current = requireTask(taskId);
        if (TERMINAL_STATES.contains(current.state())) {
            return current;
        }
        if (cancellationRequested(taskId)) {
            // Provider 被主动取消时通常回调异常，该异常属于取消过程而不是任务失败。
            return current;
        }
        try {
            AgentFailureResult failure = failureResult(taskId, reason);
            String protectedFailure = checkpointService == null ? null : checkpointService.protect(failure);
            return transition(
                    current, AgentTaskState.FAILED, reason,
                    AgentEventType.TASK_FAILED, protectedFailure);
        } finally {
            cancellationCoordinator.close(taskId);
        }
    }

    /** 构造可持久化的结构化失败结果，正文和敏感工具输出不进入任务快照。 */
    private AgentFailureResult failureResult(String taskId, String reason) {
        List<String> completedSteps = checkpointService == null
                ? List.of()
                : checkpointService.loadRecovery(taskId)
                        .map(value -> value.checkpoint().recoveryState().completedStepIds())
                        .orElse(List.of());
        return new AgentFailureResult(
                "AGENT_TASK_FAILED", "AGENT_RUNTIME", "EXECUTION",
                requiredText(reason, "任务执行失败"), false,
                completedSteps, "未完成步骤已停止；已完成副作用不会自动回滚",
                "检查错误详情和任务 Trace 后重新发起任务");
    }

    /** 仅当等待输入任务仍是扫描到的原版本时超时失败。 */
    public java.util.Optional<AgentTask> expireWaitingInput(
            String taskId, long expectedVersion) {
        java.util.Optional<AgentTask> expired = taskService.transitionTaskIfVersion(
                taskId, expectedVersion, AgentTaskState.WAITING_INPUT,
                AgentTaskState.FAILED, "等待用户补充输入超时");
        expired.ifPresent(task -> {
            eventSequencer.publish(taskId, eventRequest(
                    task, AgentEventType.TASK_FAILED,
                    Map.of("state", task.state().name(), "reason", task.stateReason())));
            cancellationCoordinator.close(taskId);
        });
        return expired;
    }

    /**
     * 注册任务级可取消资源，供模型流和工具进程统一接收用户取消信号。
     *
     * @param taskId 任务标识
     * @param type 资源类型
     * @param handleId 资源标识
     * @param handle 取消回调
     * @return 可关闭注册
     */
    public AgentCancellationRegistration registerCancellation(
            String taskId,
            AgentCancellationHandleType type,
            String handleId,
            AgentCancellationHandle handle) {
        return cancellationCoordinator.open(taskId).register(type, handleId, handle);
    }

    /**
     * 幂等取消任务；终态任务直接返回已有状态。
     *
     * @param taskId 任务标识
     * @return 取消后的任务
     */
    public AgentTask cancelTask(String taskId) {
        AgentTask current = taskService.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 任务不存在: " + taskId));
        if (TERMINAL_STATES.contains(current.state())) {
            cancellationCoordinator.close(taskId);
            return current;
        }
        eventSequencer.publish(taskId, eventRequest(
                current, AgentEventType.TASK_CANCEL_REQUESTED, Map.of("reason", "用户取消")));
        // CancellationScope 会立即拒绝或取消迟到注册，先完成传播再向外提交终态。
        AgentCancellationResult cancellation = cancellationCoordinator.cancel(taskId, "用户取消");
        AgentTask latest = requireTask(taskId);
        if (TERMINAL_STATES.contains(latest.state())) {
            // 任务可能在取消意图发布前恰好完成；终态已成立时直接幂等返回，不能再次非法转换。
            cancellationCoordinator.close(taskId);
            return latest;
        }
        String reason = cancellation.failures().isEmpty()
                ? "用户取消，资源清理完成"
                : "用户取消，资源清理完成但存在 " + cancellation.failures().size() + " 个失败句柄";
        AgentTask canceled = taskService.transitionTask(taskId, AgentTaskState.CANCELED, reason);
        try {
            eventSequencer.publish(taskId, eventRequest(
                    canceled, AgentEventType.TASK_CANCELED,
                    Map.of(
                            "previousState", current.state().name(),
                            "state", canceled.state().name(),
                            "registeredHandles", cancellation.registeredHandleCount(),
                            "canceledHandles", cancellation.canceledHandleCount(),
                            "failedHandles", cancellation.failures().size())));
        } finally {
            cancellationCoordinator.close(taskId);
        }
        return canceled;
    }

    /** 判断当前任务是否已经进入用户取消传播阶段。 */
    private boolean cancellationRequested(String taskId) {
        return cancellationCoordinator.find(taskId)
                .map(AgentCancellationScope::isCancellationRequested)
                .orElse(false);
    }

    /**
     * 查询前补偿缺失的终态事件，处理任务文件已提交但事件追加瞬时失败的情况。
     *
     * @param taskId 任务标识
     */
    public void reconcileTerminalEvent(String taskId) {
        if (eventStore == null) {
            return;
        }
        AgentTask task = requireTask(taskId);
        AgentEventType terminalType = terminalEventType(task.state());
        if (terminalType == null) {
            return;
        }
        try {
            if (hasTerminalEvent(task, terminalType)) {
                return;
            }
        } catch (AgentEventStoreException ex) {
            // task.json 已是终态时，事件补偿不得阻塞前端获取最终结果。
            log.warn("Agent 终态事件校验失败，跳过本次补偿: taskId={}", taskId, ex);
            return;
        }
        eventSequencer.publish(taskId, eventRequest(
                task, terminalType,
                Map.of("state", task.state().name(), "reason", task.stateReason(), "reconciled", true)));
    }

    /**
     * 判断最近事件中是否已经存在当前任务版本的终态事实。
     *
     * @param task 当前任务
     * @param terminalType 终态事件类型
     * @return 已存在返回 true
     */
    private boolean hasTerminalEvent(AgentTask task, AgentEventType terminalType) {
        long last = eventStore.lastSequence(task.taskId());
        return eventStore.readAfter(task.taskId(), Math.max(0L, last - 50L), 50).stream()
                .anyMatch(event -> event.eventType() == terminalType
                        && event.context().taskVersion() == task.version());
    }

    /**
     * 将任务终态映射为唯一终态事件类型。
     *
     * @param state 任务状态
     * @return 终态事件类型，非终态返回 null
     */
    private AgentEventType terminalEventType(AgentTaskState state) {
        return switch (state) {
            case COMPLETED -> AgentEventType.TASK_COMPLETED;
            case FAILED -> AgentEventType.TASK_FAILED;
            case CANCELED -> AgentEventType.TASK_CANCELED;
            default -> null;
        };
    }

    /**
     * 构造任务级事件请求。
     *
     * @param task 任务快照
     * @param type 事件类型
     * @param payload 事件载荷
     * @return 事件请求
     */
    private AgentEventRequest eventRequest(
            AgentTask task, AgentEventType type, Map<String, Object> payload) {
        AgentEventContext context = new AgentEventContext(
                task.conversationId(), null, null, null, null, null, task.version());
        return new AgentEventRequest(type, AgentEventScope.TASK, context, payload, Instant.now(), "agent-runtime");
    }

    /**
     * 推进任务状态并发布与新版本一致的持久事件。
     *
     * @param current 当前任务
     * @param target 目标状态
     * @param reason 状态原因
     * @param eventType 事件类型
     * @return 更新任务
     */
    private AgentTask transition(
            AgentTask current,
            AgentTaskState target,
            String reason,
            AgentEventType eventType) {
        return transition(current, target, reason, eventType, null);
    }

    /**
     * 推进任务状态并可同时保存受保护结果。
     *
     * @param current 当前任务
     * @param target 目标状态
     * @param reason 状态原因
     * @param eventType 事件类型
     * @param protectedResult 加密后的最终结果
     * @return 更新任务
     */
    private AgentTask transition(
            AgentTask current,
            AgentTaskState target,
            String reason,
            AgentEventType eventType,
            String protectedResult) {
        AgentTask updated = taskService.transitionTask(current.taskId(), target, reason, protectedResult);
        eventSequencer.publish(updated.taskId(), eventRequest(
                updated,
                eventType,
                Map.of(
                        "previousState", current.state().name(),
                        "state", updated.state().name(),
                        "reason", updated.stateReason())));
        return updated;
    }

    /**
     * 获取任务，不存在时返回统一诊断。
     *
     * @param taskId 任务标识
     * @return 任务
     */
    private AgentTask requireTask(String taskId) {
        return taskService.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 任务不存在: " + taskId));
    }

    /** 读取最近恢复状态；尚无 Checkpoint 的任务使用空状态。 */
    private AgentRecoveryState recoveryState(String taskId) {
        if (checkpointService == null) {
            return new AgentRecoveryState(null, java.util.List.of(), Map.of(), null, null);
        }
        return checkpointService.loadRecovery(taskId)
                .map(value -> value.checkpoint().recoveryState())
                .orElseGet(() -> new AgentRecoveryState(null, java.util.List.of(), Map.of(), null, null));
    }

    /** 保存与当前任务版本一致的恢复点；测试兼容构造器未装配时跳过。 */
    private void saveCheckpoint(AgentTask task, AgentRecoveryState state) {
        if (checkpointService != null) {
            checkpointService.saveCheckpoint(task.taskId(), eventSequencer.lastSequence(task.taskId()), state);
        }
    }

    /** 复制恢复状态并更新待输入标识和受保护输入。 */
    private AgentRecoveryState copyRecovery(
            AgentRecoveryState state, String pendingInputKey, String protectedInputValue) {
        return new AgentRecoveryState(
                state.currentStepId(), state.completedStepIds(), state.toolCalls(),
                state.pendingConfirmationId(), pendingInputKey, state.continuationContext(),
                state.continuationState(), protectedInputValue);
    }

    /** 校验任务只能由所属会话恢复。 */
    private void requireConversation(AgentTask task, String conversationId) {
        if (!task.conversationId().equals(requiredText(conversationId, "会话标识不能为空"))) {
            throw new IllegalArgumentException("Agent 任务与当前会话不匹配");
        }
    }

    /** 规范必填控制文本。 */
    private String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /**
     * 生成有界任务目标摘要，避免完整长输入进入事件流。
     *
     * @param goal 任务目标
     * @return 有界摘要
     */
    private String goalSummary(String goal) {
        return goal.length() <= MAX_EVENT_GOAL_SUMMARY
                ? goal
                : goal.substring(0, MAX_EVENT_GOAL_SUMMARY) + "...";
    }

    /**
     * Agent Task 应用操作结果。
     *
     * <p>by AI.Coding</p>
     *
     * @param task 当前任务
     * @param changed 本次是否创建并推进了任务
     */
    public record TaskOperationResult(AgentTask task, boolean changed) {
    }

    /**
     * 任务结构化失败结果，不保存密钥、令牌和完整工具输出。
     *
     * <p>by AI.Coding</p>
     */
    public record AgentFailureResult(
            String errorCode,
            String source,
            String stage,
            String summary,
            boolean retryable,
            List<String> completedSteps,
            String possibleImpact,
            String suggestedAction) {

        /** 固化已完成步骤，避免任务结果在异步查询期间被修改。 */
        public AgentFailureResult {
            completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
        }
    }

    /**
     * 用户补充输入后的恢复结果。
     *
     * <p>by AI.Coding</p>
     *
     * @param task 已恢复任务
     * @param recoveryState 包含受保护输入的恢复状态
     * @param inputKey 已满足的输入项标识
     */
    public record AgentInputAcceptance(
            AgentTask task, AgentRecoveryState recoveryState, String inputKey) {
    }
}
