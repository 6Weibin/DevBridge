package com.devbridge.server.ai.agent.confirmation;

import com.devbridge.server.ai.agent.checkpoint.AgentTaskRecovery;
import com.devbridge.server.ai.agent.checkpoint.AgentCheckpoint;
import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointService;
import com.devbridge.server.ai.agent.checkpoint.AgentRecoveryState;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationCoordinator.AgentConfirmationApproval;
import com.devbridge.server.ai.agent.event.AgentEventContext;
import com.devbridge.server.ai.agent.event.AgentEventRequest;
import com.devbridge.server.ai.agent.event.AgentEventScope;
import com.devbridge.server.ai.agent.event.AgentEventSequencer;
import com.devbridge.server.ai.agent.event.AgentEventType;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.runtime.AgentTaskApplicationService;
import com.devbridge.server.ai.agent.runtime.AgentTaskService;
import com.devbridge.server.ai.agent.store.AgentTaskPage;
import com.devbridge.server.ai.conversation.AiConversationService;
import com.devbridge.server.ai.security.egress.AiDataEgressGuard;
import com.devbridge.server.config.ToolExecutorConfiguration;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent Runtime 默认续跑入口，确认恢复后发布事件并重新进入当前 Chat 主链路。
 *
 * <p>后端从 Checkpoint 重建请求，不再依赖前端发送“继续”消息或拼接内部 Prompt。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AgentRuntimeContinuationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRuntimeContinuationService.class);
    private static final int RECOVERY_PAGE_SIZE = 100;

    private final AgentEventSequencer eventSequencer;
    private final AiConversationService conversationService;
    private final AgentConfirmationStore confirmationStore;
    private final ExecutorService executor;
    private final AgentTaskService taskService;
    private final AgentCheckpointService checkpointService;
    private final AgentTaskApplicationService taskApplicationService;
    private final ConcurrentMap<String, Boolean> activeContinuations = new ConcurrentHashMap<>();

    /**
     * 注入事件序列器。
     *
     * @param eventSequencer 事件序列器
     * @param conversationService 当前 Chat 主链路
     * @param confirmationStore 确认记录 Store
     * @param executor 有界工具执行器
     * @param taskService 任务查询服务
     * @param checkpointService Checkpoint 服务
     * @param taskApplicationService 任务应用服务
     */
    @Autowired
    public AgentRuntimeContinuationService(
            AgentEventSequencer eventSequencer,
            AiConversationService conversationService,
            AgentConfirmationStore confirmationStore,
            @Qualifier(ToolExecutorConfiguration.TOOL_EXECUTION_EXECUTOR) ExecutorService executor,
            AgentTaskService taskService,
            AgentCheckpointService checkpointService,
            AgentTaskApplicationService taskApplicationService) {
        this.eventSequencer = eventSequencer;
        this.conversationService = conversationService;
        this.confirmationStore = confirmationStore;
        this.executor = executor;
        this.taskService = taskService;
        this.checkpointService = checkpointService;
        this.taskApplicationService = taskApplicationService;
    }

    /**
     * 创建兼容测试的续跑服务；覆盖方法的测试替身不启动真实执行器。
     *
     * @param eventSequencer 事件序列器
     * @param conversationService Chat 主链路
     */
    public AgentRuntimeContinuationService(
            AgentEventSequencer eventSequencer,
            AiConversationService conversationService) {
        this(eventSequencer, conversationService, null, null, null, null, null);
    }

    /**
     * 处理确认批准结果；终态重复提交直接重放结果，运行中任务从 Checkpoint 继续。
     *
     * @param approval 已校验批准结果
     * @return 兼容前端的事件流
     */
    public SseEmitter continueTask(AgentConfirmationApproval approval) {
        if (approval.terminalTask() != null) {
            return conversationService.replayTaskResult(approval.terminalTask());
        }
        AgentTask current = taskService == null
                ? approval.recovery().task()
                : taskService.findTask(approval.recovery().task().taskId())
                        .orElse(approval.recovery().task());
        if (current.state() == AgentTaskState.COMPLETED
                || current.state() == AgentTaskState.FAILED
                || current.state() == AgentTaskState.CANCELED) {
            // 协调器返回后任务可能并发进入终态，执行入口必须再次校验以阻断迟到的重复续跑。
            return conversationService.replayTaskResult(current);
        }
        return continueTask(approval.recovery(), approval.confirmation());
    }

    /**
     * 发布任务已从 Checkpoint 恢复的事实，供执行器和 UI 继续消费。
     *
     * @param recovery 已验证恢复上下文
     * @param confirmation 已接受确认
     * @return 后端自动续跑事件流
     */
    public SseEmitter continueTask(
            AgentTaskRecovery recovery, AgentConfirmation confirmation) {
        return startContinuation(
                recovery, confirmation,
                new SseEmitter(AiConversationService.CHAT_STREAM_TIMEOUT_MILLIS));
    }

    /**
     * 启动一次确认续跑并保证同一任务确认只有一个活动执行。
     *
     * @param recovery 已验证恢复上下文
     * @param confirmation 已接受确认
     * @param emitter 前端事件流或后台丢弃流
     * @return 传入的事件流
     */
    private SseEmitter startContinuation(
            AgentTaskRecovery recovery,
            AgentConfirmation confirmation,
            SseEmitter emitter) {
        String key = recovery.task().taskId() + ":" + confirmation.confirmationId();
        if (activeContinuations.putIfAbsent(key, Boolean.TRUE) != null) {
            throw new IllegalStateException("确认任务正在继续执行，请勿重复提交");
        }
        try {
            AgentConfirmation consumed = consume(confirmation);
            Runnable work = () -> runContinuation(recovery, consumed, emitter, key);
            if (executor == null) {
                work.run();
            } else {
                executor.execute(work);
            }
            return emitter;
        } catch (RuntimeException ex) {
            activeContinuations.remove(key);
            throw ex;
        }
    }

    /**
     * 应用启动完成后异步扫描可确定恢复的确认续跑任务。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverContinuationsOnStartup() {
        if (executor == null || taskService == null || checkpointService == null || taskApplicationService == null) {
            return;
        }
        // 启动线程只负责提交恢复扫描，避免历史任务分页读取阻塞 Spring Ready 事件。
        executor.execute(this::recoverPersistedContinuations);
    }

    /**
     * 扫描并恢复确认已经批准、但服务重启前尚未完成的任务。
     */
    void recoverPersistedContinuations() {
        for (String taskId : continuationTaskIds()) {
            recoverPersistedTask(taskId);
        }
    }

    /**
     * 分页收集运行中任务标识，先完成快照读取再启动续跑，避免分页顺序被任务更新时间改变。
     *
     * @return 运行中任务标识
     */
    private List<String> continuationTaskIds() {
        List<String> taskIds = new ArrayList<>();
        for (int page = 0; ; page++) {
            AgentTaskPage tasks = taskService.listTasks(page, RECOVERY_PAGE_SIZE);
            tasks.items().stream()
                    .filter(task -> task.state() != AgentTaskState.COMPLETED
                            && task.state() != AgentTaskState.FAILED
                            && task.state() != AgentTaskState.CANCELED)
                    .map(AgentTask::taskId)
                    .forEach(taskIds::add);
            if (tasks.items().size() < RECOVERY_PAGE_SIZE) {
                return taskIds;
            }
        }
    }

    /**
     * 从持久 Checkpoint 和确认记录恢复单个确定性续跑任务。
     *
     * @param taskId 任务标识
     */
    private void recoverPersistedContinuation(String taskId) {
        try {
            AgentTaskRecovery recovery = checkpointService.loadRecovery(taskId).orElse(null);
            if (recovery == null || !isConfirmedContinuation(recovery.checkpoint().recoveryState())) {
                return;
            }
            String confirmationId = recovery.checkpoint().recoveryState().pendingConfirmationId();
            AgentConfirmation confirmation = confirmationStore.find(taskId, confirmationId)
                    .orElseThrow(() -> new IllegalStateException("恢复任务缺少确认记录"));
            if (confirmation.status() == AgentConfirmationStatus.PENDING) {
                reconcilePendingConfirmation(recovery, confirmation);
                return;
            }
            if (confirmation.status() != AgentConfirmationStatus.ACCEPTED
                    && confirmation.status() != AgentConfirmationStatus.CONSUMED) {
                throw new IllegalStateException("恢复任务确认状态不合法: " + confirmation.status());
            }
            AgentTaskRecovery runnable = reconcileAcceptedConfirmation(recovery);
            // 后台恢复不再绑定旧 HTTP 连接，模型正文由任务结果持久化供前端查询。
            startContinuation(runnable, confirmation, new DiscardingSseEmitter());
            LOGGER.info("Agent 确认续跑已从 Checkpoint 恢复, taskId={}, confirmationId={}",
                    taskId, confirmationId);
        } catch (RuntimeException ex) {
            LOGGER.error("Agent 确认续跑恢复失败, taskId={}, errorType={}",
                    taskId, ex.getClass().getSimpleName());
            failRecoveredTask(taskId);
        }
    }

    /**
     * 按任务持久状态选择等待恢复、确认续跑或普通执行续跑。
     *
     * @param taskId 任务标识
     */
    private void recoverPersistedTask(String taskId) {
        AgentTask task = taskService.findTask(taskId).orElse(null);
        if (task == null || task.state() == AgentTaskState.WAITING_INPUT
                || task.state() == AgentTaskState.PAUSED) {
            // 用户输入和主动暂停必须继续等待明确动作，服务重启不能擅自解除。
            return;
        }
        AgentTaskRecovery recovery = checkpointService.loadRecovery(taskId).orElse(null);
        if (task.state() == AgentTaskState.WAITING_CONFIRMATION
                || recovery != null && isConfirmedContinuation(recovery.checkpoint().recoveryState())) {
            recoverPersistedContinuation(taskId);
            return;
        }
        recoverOrdinaryTask(taskId, recovery);
    }

    /** 普通执行态从最后完整 Checkpoint 重新进入同一对话主链路。 */
    private void recoverOrdinaryTask(String taskId, AgentTaskRecovery recovery) {
        try {
            if (recovery == null || recovery.checkpoint().recoveryState().continuationContext() == null) {
                throw new IllegalStateException("非终态任务缺少可恢复 Checkpoint");
            }
            String key = "restart:" + taskId;
            if (activeContinuations.putIfAbsent(key, Boolean.TRUE) != null) {
                return;
            }
            AgentTaskApplicationService.TaskOperationResult resumed =
                    taskApplicationService.recoverAfterRestart(taskId);
            AgentTaskRecovery runnable = new AgentTaskRecovery(
                    resumed.task(), recovery.checkpoint());
            conversationService.continueAfterRestart(
                    runnable, new DiscardingSseEmitter(), () -> activeContinuations.remove(key));
            LOGGER.info("Agent 普通任务已从 Checkpoint 恢复, taskId={}", taskId);
        } catch (RuntimeException ex) {
            activeContinuations.remove("restart:" + taskId);
            LOGGER.error("Agent 普通任务恢复失败, taskId={}, errorType={}",
                    taskId, ex.getClass().getSimpleName());
            failRecoveredTask(taskId);
        }
    }

    /** 将“确认记录已保存、等待状态未提交”的中断窗口对账为 WAITING_CONFIRMATION。 */
    private void reconcilePendingConfirmation(
            AgentTaskRecovery recovery, AgentConfirmation confirmation) {
        if (recovery.task().state() == AgentTaskState.WAITING_CONFIRMATION) {
            return;
        }
        AgentTask waiting = taskService.transitionTask(
                recovery.task().taskId(), AgentTaskState.WAITING_CONFIRMATION,
                "服务重启后恢复待确认状态");
        AgentCheckpoint checkpoint = checkpointService.saveCheckpoint(
                waiting.taskId(), eventSequencer.lastSequence(waiting.taskId()),
                recovery.checkpoint().recoveryState());
        eventSequencer.publish(waiting.taskId(), new AgentEventRequest(
                AgentEventType.CONFIRMATION_REQUIRED, AgentEventScope.CONFIRMATION,
                new AgentEventContext(
                        waiting.conversationId(), null, confirmation.binding().stepId(),
                        confirmation.binding().toolCallId(), confirmation.confirmationId(),
                        null, waiting.version()),
                Map.of("reconciled", true, "checkpointId", checkpoint.checkpointId()),
                Instant.now(), "agent-runtime"));
    }

    /** 将“确认已接受、运行状态或 Checkpoint 未完成”的窗口对账为可续跑状态。 */
    private AgentTaskRecovery reconcileAcceptedConfirmation(AgentTaskRecovery recovery) {
        AgentTask task = recovery.task();
        AgentRecoveryState state = recovery.checkpoint().recoveryState();
        if (task.state() == AgentTaskState.WAITING_CONFIRMATION) {
            task = taskService.transitionTask(task.taskId(), AgentTaskState.RUNNING,
                    "服务重启后恢复已批准确认");
        }
        AgentCheckpoint checkpoint = checkpointService.saveCheckpoint(
                task.taskId(), eventSequencer.lastSequence(task.taskId()), state);
        return new AgentTaskRecovery(task, checkpoint);
    }

    /**
     * 判断 Checkpoint 是否属于确认批准后的确定性续跑阶段。
     *
     * @param state 恢复状态
     * @return 可以自动恢复时返回 true
     */
    private boolean isConfirmedContinuation(AgentRecoveryState state) {
        return state != null
                && StringUtils.hasText(state.pendingConfirmationId())
                && ("READY".equals(state.continuationState())
                || "RUNNING".equals(state.continuationState()));
    }

    /**
     * 恢复资料损坏或状态冲突时明确结束任务，避免永久停留在 RUNNING。
     *
     * @param taskId 任务标识
     */
    private void failRecoveredTask(String taskId) {
        try {
            taskApplicationService.failTask(taskId, "服务重启后确认续跑恢复失败");
        } catch (RuntimeException ex) {
            LOGGER.error("Agent 恢复失败状态保存失败, taskId={}, errorType={}",
                    taskId, ex.getClass().getSimpleName());
        }
    }

    /**
     * 消费已接受确认；重启后的已消费确认允许依靠 Checkpoint 幂等恢复。
     *
     * @param confirmation 当前确认
     * @return 已消费确认
     */
    private AgentConfirmation consume(AgentConfirmation confirmation) {
        if (confirmation.status() == AgentConfirmationStatus.CONSUMED || confirmationStore == null) {
            return confirmation;
        }
        AgentConfirmation consumed = new AgentConfirmation(
                confirmation.confirmationId(), confirmation.taskId(), confirmation.binding(),
                AgentConfirmationStatus.CONSUMED, confirmation.createdAt(), confirmation.expiresAt(),
                confirmation.decidedAt(), confirmation.decisionReason());
        return confirmationStore.update(consumed, AgentConfirmationStatus.ACCEPTED);
    }

    /**
     * 发布恢复事件并执行确定性续跑，终态回调负责释放并发占位。
     *
     * @param recovery 恢复上下文
     * @param confirmation 已消费确认
     * @param emitter SSE Emitter
     * @param key 活动续跑键
     */
    private void runContinuation(
            AgentTaskRecovery recovery,
            AgentConfirmation confirmation,
            SseEmitter emitter,
            String key) {
        try {
            AgentEventContext context = new AgentEventContext(
                    recovery.task().conversationId(), null, null, null, null, null,
                    recovery.task().version());
            eventSequencer.publish(recovery.task().taskId(), new AgentEventRequest(
                    AgentEventType.RECOVERY_COMPLETED,
                    AgentEventScope.TASK,
                    context,
                    Map.of(
                            "checkpointId", recovery.checkpoint().checkpointId(),
                            "currentStepId", valueOrEmpty(recovery.checkpoint().recoveryState().currentStepId())),
                    Instant.now(),
                    "agent-runtime"));
            if (AiDataEgressGuard.MODEL_EGRESS_TOOL_ID.equals(confirmation.binding().toolId())) {
                conversationService.continueAfterDataEgressConfirmation(
                        recovery, confirmation, emitter, () -> activeContinuations.remove(key));
            } else {
                conversationService.continueAfterConfirmation(
                        recovery, confirmation, emitter, () -> activeContinuations.remove(key));
            }
        } catch (RuntimeException ex) {
            activeContinuations.remove(key);
            emitter.completeWithError(ex);
        }
    }

    /**
     * 将可空标识转换为空字符串，避免事件 Map 接收 null。
     *
     * @param value 标识值
     * @return 非空文本
     */
    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 后台恢复使用的丢弃型事件流，避免无人订阅时缓存完整模型正文。
     *
     * <p>by AI.Coding</p>
     */
    private static final class DiscardingSseEmitter extends SseEmitter {

        /**
         * 丢弃兼容 SSE 展示事件；任务结果和控制事件仍由后端正常持久化。
         *
         * @param builder SSE 事件
         */
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            // 后台没有 HTTP 消费者，禁止 ResponseBodyEmitter 把长回复累积到 earlySendAttempts。
        }
    }
}
