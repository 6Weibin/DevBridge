package com.devbridge.server.ai.agent.confirmation;

import com.devbridge.server.ai.agent.checkpoint.AgentCheckpoint;
import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointService;
import com.devbridge.server.ai.agent.checkpoint.AgentRecoveryState;
import com.devbridge.server.ai.agent.checkpoint.AgentTaskRecovery;
import com.devbridge.server.ai.agent.checkpoint.AgentToolCallCheckpoint;
import com.devbridge.server.ai.agent.checkpoint.AgentToolCallCheckpointStatus;
import com.devbridge.server.ai.agent.event.AgentEventContext;
import com.devbridge.server.ai.agent.event.AgentEventRequest;
import com.devbridge.server.ai.agent.event.AgentEventScope;
import com.devbridge.server.ai.agent.event.AgentEventSequencer;
import com.devbridge.server.ai.agent.event.AgentEventStore;
import com.devbridge.server.ai.agent.event.AgentEventType;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.runtime.AgentTaskService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

/**
 * Agent 确认协调器，负责等待、决策、Checkpoint 和后端自动续跑。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AgentConfirmationCoordinator {

    private static final Duration MAX_CONFIRMATION_TTL = Duration.ofHours(24);
    private static final int DECISION_LOCK_STRIPES = 64;

    private final AgentTaskService taskService;
    private final AgentCheckpointService checkpointService;
    private final AgentConfirmationStore confirmationStore;
    private final AgentEventSequencer eventSequencer;
    private final AgentEventStore eventStore;
    private final AgentConfirmationTokenService tokenService;
    private final Object[] decisionLocks = createDecisionLocks();

    /**
     * 注入确认控制面依赖。
     *
     * @param taskService 任务服务
     * @param checkpointService Checkpoint 服务
     * @param confirmationStore 确认 Store
     * @param eventSequencer 事件序列器
     * @param eventStore 事件 Store
     */
    @Autowired
    public AgentConfirmationCoordinator(
            AgentTaskService taskService,
            AgentCheckpointService checkpointService,
            AgentConfirmationStore confirmationStore,
            AgentEventSequencer eventSequencer,
            AgentEventStore eventStore,
            AgentConfirmationTokenService tokenService) {
        this.taskService = taskService;
        this.checkpointService = checkpointService;
        this.confirmationStore = confirmationStore;
        this.eventSequencer = eventSequencer;
        this.eventStore = eventStore;
        this.tokenService = tokenService;
    }

    /** 创建兼容测试的确认协调器。 */
    public AgentConfirmationCoordinator(
            AgentTaskService taskService,
            AgentCheckpointService checkpointService,
            AgentConfirmationStore confirmationStore,
            AgentEventSequencer eventSequencer,
            AgentEventStore eventStore) {
        this(taskService, checkpointService, confirmationStore, eventSequencer, eventStore, null);
    }

    /**
     * 创建确认记录、暂停任务并保存可恢复 Checkpoint。
     *
     * @param request 确认请求
     * @return 待决确认记录
     */
    public AgentConfirmation request(AgentConfirmationRequest request) {
        validateRequest(request);
        AgentTask task = requireTask(request.taskId());
        if (task.state() != AgentTaskState.RUNNING) {
            throw new IllegalStateException("只有 RUNNING 任务可以请求确认");
        }
        AgentRecoveryState previous = currentRecovery(task.taskId());
        Instant now = Instant.now();
        AgentConfirmation confirmation = new AgentConfirmation(
                "confirmation-" + UUID.randomUUID(),
                task.taskId(),
                binding(request, task.conversationId()),
                AgentConfirmationStatus.PENDING,
                now,
                now.plus(request.ttl()),
                null,
                null);
        AgentRecoveryState recovery = waitingRecovery(previous, request, confirmation.confirmationId());
        // 先保存恢复意图，任何后续步骤中断都能由启动对账恢复，不会丢失原工具身份。
        checkpointService.saveCheckpoint(
                task.taskId(), eventStore.lastSequence(task.taskId()), recovery);
        confirmation = confirmationStore.save(confirmation);
        AgentTask waiting = taskService.transitionTask(
                task.taskId(), AgentTaskState.WAITING_CONFIRMATION, "等待用户确认敏感操作");
        checkpointService.saveCheckpoint(
                waiting.taskId(), eventStore.lastSequence(waiting.taskId()), recovery);
        publish(waiting, confirmation, AgentEventType.CONFIRMATION_REQUIRED, "等待用户确认");
        return confirmation;
    }

    /**
     * 幂等批准确认并自动从原 Checkpoint 继续任务。
     *
     * @param taskId 任务标识
     * @param confirmationId 确认标识
     * @return 已接受确认和恢复上下文
     */
    public AgentConfirmationApproval approve(String taskId, String confirmationId) {
        return approve(taskId, confirmationId, "", "");
    }

    /**
     * 校验会话和签名令牌后幂等批准确认。
     *
     * @param taskId 任务标识
     * @param confirmationId 确认标识
     * @param conversationId 会话标识
     * @param approvalToken 授权签名
     * @return 已接受确认和恢复上下文
     */
    public AgentConfirmationApproval approve(
            String taskId, String confirmationId, String conversationId, String approvalToken) {
        synchronized (decisionLock(confirmationId)) {
            return approveLocked(taskId, confirmationId, conversationId, approvalToken);
        }
    }

    /** 在确认级临界区内完成授权校验、状态提交和任务恢复。 */
    private AgentConfirmationApproval approveLocked(
            String taskId, String confirmationId, String conversationId, String approvalToken) {
        AgentConfirmation current = requireConfirmation(taskId, confirmationId);
        validateAuthorization(current, conversationId, approvalToken);
        if (current.status() == AgentConfirmationStatus.ACCEPTED
                || current.status() == AgentConfirmationStatus.CONSUMED) {
            return existingApproval(current);
        }
        ensurePending(current);
        if (Instant.now().isAfter(current.expiresAt())) {
            expire(current);
            throw new IllegalStateException("确认已经过期");
        }
        AgentTaskRecovery waiting = checkpointService.loadRecovery(taskId)
                .orElseThrow(() -> new IllegalStateException("等待确认任务缺少 Checkpoint"));
        AgentRecoveryState approved = approvedRecovery(waiting.checkpoint().recoveryState());
        // 批准恢复意图先落盘；确认状态或任务状态中断时由启动对账继续，不重放已完成工具。
        checkpointService.saveCheckpoint(taskId, eventStore.lastSequence(taskId), approved);
        AgentConfirmation accepted = confirmationStore.update(
                decided(current, AgentConfirmationStatus.ACCEPTED, "用户已确认"),
                AgentConfirmationStatus.PENDING);
        AgentTask resumed = taskService.transitionTask(taskId, AgentTaskState.RUNNING, "用户确认后自动恢复");
        AgentCheckpoint checkpoint = checkpointService.saveCheckpoint(
                taskId, eventStore.lastSequence(taskId), approved);
        publish(resumed, accepted, AgentEventType.CONFIRMATION_ACCEPTED, "用户已确认");
        return new AgentConfirmationApproval(
                accepted, new AgentTaskRecovery(resumed, checkpoint), null);
    }

    /**
     * 处理已批准确认的重复提交；运行中任务继续返回恢复点，终态任务直接返回既有结果。
     *
     * @param confirmation 已批准或已消费确认
     * @return 幂等批准结果
     */
    private AgentConfirmationApproval existingApproval(AgentConfirmation confirmation) {
        AgentTask task = requireTask(confirmation.taskId());
        if (task.state() == AgentTaskState.COMPLETED
                || task.state() == AgentTaskState.FAILED
                || task.state() == AgentTaskState.CANCELED) {
            // 网络重试发生在任务完成后时不能重新加载旧 Checkpoint，更不能重新执行敏感工具。
            return new AgentConfirmationApproval(confirmation, null, task);
        }
        if (task.state() != AgentTaskState.RUNNING) {
            throw new IllegalStateException("已批准确认任务状态不允许继续: " + task.state());
        }
        AgentTaskRecovery recovery = checkpointService.loadRecovery(task.taskId())
                .orElseThrow(() -> new IllegalStateException("已批准确认任务缺少 Checkpoint"));
        return new AgentConfirmationApproval(confirmation, recovery, null);
    }

    /**
     * 拒绝确认并终止依赖该敏感操作的任务路径。
     *
     * @param taskId 任务标识
     * @param confirmationId 确认标识
     * @param reason 拒绝原因
     * @return 已拒绝确认
     */
    public AgentConfirmation reject(String taskId, String confirmationId, String reason) {
        return reject(taskId, confirmationId, "", "", reason);
    }

    /** 校验会话和签名令牌后拒绝敏感操作。 */
    public AgentConfirmation reject(
            String taskId,
            String confirmationId,
            String conversationId,
            String approvalToken,
            String reason) {
        synchronized (decisionLock(confirmationId)) {
            return rejectLocked(taskId, confirmationId, conversationId, approvalToken, reason);
        }
    }

    /** 在确认级临界区内提交拒绝，避免与并发批准交叉写入。 */
    private AgentConfirmation rejectLocked(
            String taskId,
            String confirmationId,
            String conversationId,
            String approvalToken,
            String reason) {
        AgentConfirmation current = requireConfirmation(taskId, confirmationId);
        validateAuthorization(current, conversationId, approvalToken);
        if (current.status() == AgentConfirmationStatus.REJECTED) {
            return current;
        }
        ensurePending(current);
        AgentConfirmation rejected = confirmationStore.update(
                decided(current, AgentConfirmationStatus.REJECTED, normalizedReason(reason)),
                AgentConfirmationStatus.PENDING);
        AgentTask failed = taskService.transitionTask(
                taskId, AgentTaskState.FAILED, "用户拒绝敏感操作，依赖步骤停止");
        publish(failed, rejected, AgentEventType.CONFIRMATION_REJECTED, rejected.decisionReason());
        return rejected;
    }

    /**
     * 构造等待确认恢复状态，并保留之前已完成步骤和工具结果。
     *
     * @param previous 前一恢复状态
     * @param request 确认请求
     * @param confirmationId 确认标识
     * @return 等待确认恢复状态
     */
    private AgentRecoveryState waitingRecovery(
            AgentRecoveryState previous,
            AgentConfirmationRequest request,
            String confirmationId) {
        Map<String, AgentToolCallCheckpoint> toolCalls = new HashMap<>(previous.toolCalls());
        AgentToolCallCheckpoint savedRequest = toolCalls.get(request.toolCallId());
        toolCalls.put(request.toolCallId(), new AgentToolCallCheckpoint(
                request.toolCallId(), request.stepId(), AgentToolCallCheckpointStatus.REQUESTED,
                null, request.argumentDigest(), null, false,
                savedRequest == null ? null : savedRequest.protectedRequest()));
        return new AgentRecoveryState(
                request.stepId(), previous.completedStepIds(), toolCalls, confirmationId, null,
                previous.continuationContext(), "READY");
    }

    /**
     * 保存已批准确认的恢复状态；确认标识保留到任务终态，供服务重启后定位原授权。
     *
     * @param state 原恢复状态
     * @return 已恢复状态
     */
    private AgentRecoveryState approvedRecovery(AgentRecoveryState state) {
        return new AgentRecoveryState(
                state.currentStepId(), state.completedStepIds(), state.toolCalls(), state.pendingConfirmationId(),
                state.pendingInputKey(), state.continuationContext(),
                state.continuationState());
    }

    /**
     * 加载现有恢复状态，无 Checkpoint 时创建空状态。
     *
     * @param taskId 任务标识
     * @return 当前恢复状态
     */
    private AgentRecoveryState currentRecovery(String taskId) {
        return checkpointService.loadRecovery(taskId)
                .map(recovery -> recovery.checkpoint().recoveryState())
                .orElseGet(() -> new AgentRecoveryState(null, List.of(), Map.of(), null, null));
    }

    /**
     * 处理过期确认并使等待任务明确失败。
     *
     * @param current 当前确认
     */
    private void expire(AgentConfirmation current) {
        AgentConfirmation expired = confirmationStore.update(
                decided(current, AgentConfirmationStatus.EXPIRED, "确认已过期"),
                AgentConfirmationStatus.PENDING);
        AgentTask task = requireTask(current.taskId());
        if (task.state() == AgentTaskState.WAITING_CONFIRMATION) {
            AgentTask failed = taskService.transitionTask(
                    task.taskId(), AgentTaskState.FAILED, "敏感操作确认已过期");
            publish(failed, expired, AgentEventType.CONFIRMATION_EXPIRED, "确认已过期");
        }
    }

    /**
     * 发布确认决策事件。
     *
     * @param task 任务快照
     * @param confirmation 确认记录
     * @param type 事件类型
     * @param message 事件摘要
     */
    private void publish(
            AgentTask task,
            AgentConfirmation confirmation,
            AgentEventType type,
            String message) {
        AgentConfirmationBinding binding = confirmation.binding();
        AgentEventContext context = new AgentEventContext(
                task.conversationId(), null, binding.stepId(), binding.toolCallId(),
                confirmation.confirmationId(), null, task.version());
        eventSequencer.publish(task.taskId(), new AgentEventRequest(
                type, AgentEventScope.CONFIRMATION, context,
                Map.of(
                        "toolId", binding.toolId(),
                        "riskLevel", binding.riskLevel().name(),
                        "message", message),
                Instant.now(), "confirmation-coordinator"));
    }

    /**
     * 构造确认绑定。
     *
     * @param request 确认请求
     * @return 确认绑定
     */
    private AgentConfirmationBinding binding(
            AgentConfirmationRequest request, String conversationId) {
        return new AgentConfirmationBinding(
                conversationId,
                request.stepId(), request.toolCallId(), request.toolId(), request.argumentDigest(),
                request.riskLevel(), request.impactSummary());
    }

    /** 校验确认只能由原会话持有的签名令牌决定。 */
    private void validateAuthorization(
            AgentConfirmation confirmation, String conversationId, String approvalToken) {
        if (tokenService == null) {
            return;
        }
        if (!StringUtils.hasText(confirmation.binding().conversationId())
                || !confirmation.binding().conversationId().equals(conversationId)
                || !tokenService.matches(confirmation, approvalToken)) {
            throw new IllegalArgumentException("确认授权令牌或会话绑定无效");
        }
    }

    /** 为前端兼容卡片签发当前确认的授权令牌。 */
    public String approvalToken(AgentConfirmation confirmation) {
        return tokenService == null ? "" : tokenService.issue(confirmation);
    }

    /**
     * 构造确认终态记录。
     *
     * @param current 当前确认
     * @param status 目标状态
     * @param reason 决策原因
     * @return 新确认记录
     */
    private AgentConfirmation decided(
            AgentConfirmation current, AgentConfirmationStatus status, String reason) {
        return new AgentConfirmation(
                current.confirmationId(), current.taskId(), current.binding(), status,
                current.createdAt(), current.expiresAt(), Instant.now(), reason);
    }

    /**
     * 校验确认请求边界。
     *
     * @param request 确认请求
     */
    private void validateRequest(AgentConfirmationRequest request) {
        if (request == null || request.riskLevel() != AgentConfirmationRiskLevel.MEDIUM) {
            throw new IllegalArgumentException("只有中风险操作进入用户确认");
        }
        if (!StringUtils.hasText(request.taskId()) || !StringUtils.hasText(request.stepId())
                || !StringUtils.hasText(request.toolCallId()) || !StringUtils.hasText(request.toolId())
                || !StringUtils.hasText(request.argumentDigest()) || !StringUtils.hasText(request.impactSummary())) {
            throw new IllegalArgumentException("确认请求绑定字段不完整");
        }
        if (request.ttl() == null || request.ttl().isZero() || request.ttl().isNegative()
                || request.ttl().compareTo(MAX_CONFIRMATION_TTL) > 0) {
            throw new IllegalArgumentException("确认有效期不合法");
        }
    }

    /** 返回确认标识对应的固定锁分片。 */
    private Object decisionLock(String confirmationId) {
        return decisionLocks[Math.floorMod(confirmationId.hashCode(), decisionLocks.length)];
    }

    /** 创建固定数量的确认决策锁，避免按任务数量增长内存。 */
    private static Object[] createDecisionLocks() {
        Object[] locks = new Object[DECISION_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    /**
     * 获取任务。
     *
     * @param taskId 任务标识
     * @return 任务
     */
    private AgentTask requireTask(String taskId) {
        return taskService.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 任务不存在: " + taskId));
    }

    /**
     * 获取确认记录。
     *
     * @param taskId 任务标识
     * @param confirmationId 确认标识
     * @return 确认记录
     */
    private AgentConfirmation requireConfirmation(String taskId, String confirmationId) {
        return confirmationStore.find(taskId, confirmationId)
                .orElseThrow(() -> new IllegalArgumentException("确认记录不存在: " + confirmationId));
    }

    /**
     * 确认记录必须处于待决状态。
     *
     * @param confirmation 确认记录
     */
    private void ensurePending(AgentConfirmation confirmation) {
        if (confirmation.status() != AgentConfirmationStatus.PENDING) {
            throw new IllegalStateException("确认记录已经结束: " + confirmation.status());
        }
    }

    /**
     * 规范化用户拒绝原因。
     *
     * @param reason 原始原因
     * @return 非空原因
     */
    private String normalizedReason(String reason) {
        return StringUtils.hasText(reason) ? reason.trim() : "用户拒绝敏感操作";
    }

    /**
     * 确认批准结果，控制面决策与后端续跑输入通过同一返回值交付。
     *
     * <p>by AI.Coding</p>
     *
     * @param confirmation 已接受确认
     * @param recovery 已验证恢复上下文
     * @param terminalTask 重复确认时已存在的终态任务，可空
     */
    public record AgentConfirmationApproval(
            AgentConfirmation confirmation,
            AgentTaskRecovery recovery,
            AgentTask terminalTask) {
    }
}
