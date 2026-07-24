package com.devbridge.server.ai.agent.runtime;

import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.store.AgentTaskStore;
import com.devbridge.server.ai.agent.store.AgentTaskPage;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.ai.storage.AiDataMaintenanceLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

/**
 * Agent Runtime 任务入口，负责创建任务并读取任务当前状态。
 *
 * <p>当前实现任务创建、读取和确定性状态推进；事件和恢复由后续任务实现。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AgentTaskService {

    private static final int MAX_CONVERSATION_ID_LENGTH = 128;
    private static final int MAX_GOAL_LENGTH = 16_384;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final AgentTaskStore taskStore;
    private final AgentTaskStateMachine stateMachine;
    private final SensitiveDataMasker sensitiveDataMasker;
    private final AiDataMaintenanceLock maintenanceLock;

    /**
     * 注入中立任务存储端口，避免 Runtime 依赖具体持久化技术。
     *
     * @param taskStore 任务存储
     * @param stateMachine 任务状态机
     */
    @Autowired
    public AgentTaskService(
            AgentTaskStore taskStore,
            AgentTaskStateMachine stateMachine,
            SensitiveDataMasker sensitiveDataMasker,
            AiDataMaintenanceLock maintenanceLock) {
        this.taskStore = taskStore;
        this.stateMachine = stateMachine;
        this.sensitiveDataMasker = sensitiveDataMasker;
        this.maintenanceLock = maintenanceLock;
    }

    /**
     * 创建兼容测试的任务服务。
     *
     * @param taskStore 任务 Store
     * @param stateMachine 状态机
     */
    public AgentTaskService(AgentTaskStore taskStore, AgentTaskStateMachine stateMachine) {
        this(taskStore, stateMachine, new SensitiveDataMasker(), new AiDataMaintenanceLock());
    }

    /**
     * 创建处于 CREATED 状态的新任务，并由后端生成任务标识。
     *
     * @param command 创建任务命令
     * @return 已保存任务
     */
    public AgentTask createTask(CreateAgentTaskCommand command) {
        return createTaskResult(command).task();
    }

    /**
     * 幂等创建任务并返回本次是否真正创建，供事件层避免重复发布创建事件。
     *
     * @param command 创建命令
     * @return 任务创建结果
     */
    public TaskCreationResult createTaskResult(CreateAgentTaskCommand command) {
        return maintenanceLock.read(() -> createTaskUnlocked(command));
    }

    /** 在维护锁保护下执行实际幂等创建。 */
    private TaskCreationResult createTaskUnlocked(CreateAgentTaskCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("创建任务命令不能为空");
        }
        String conversationId = required(command.conversationId(), "会话标识不能为空");
        String goal = sensitiveDataMasker.protectCredentials(required(command.goal(), "任务目标不能为空"));
        String idempotencyKey = optional(command.idempotencyKey());
        validateLength(conversationId, MAX_CONVERSATION_ID_LENGTH, "会话标识");
        validateLength(goal, MAX_GOAL_LENGTH, "任务目标");
        validateLength(idempotencyKey, MAX_IDEMPOTENCY_KEY_LENGTH, "幂等键");

        String taskId = StringUtils.hasText(idempotencyKey)
                ? deterministicTaskId(conversationId, idempotencyKey)
                : UUID.randomUUID().toString();
        Optional<AgentTask> existing = taskStore.findById(taskId);
        if (existing.isPresent()) {
            return duplicateResult(existing.get(), conversationId, goal);
        }

        // 创建和更新时间使用同一时刻，避免初始快照出现不必要的时间偏差。
        Instant now = Instant.now();
        AgentTask task = new AgentTask(
                taskId,
                conversationId,
                goal,
                AgentTaskState.CREATED,
                "任务已创建",
                1L,
                now,
                now);
        try {
            return new TaskCreationResult(taskStore.save(task), true);
        } catch (IllegalStateException ex) {
            // 并发重复提交会竞争同一稳定任务 ID，败方重新读取并校验请求摘要。
            AgentTask concurrent = taskStore.findById(taskId).orElseThrow(() -> ex);
            return duplicateResult(concurrent, conversationId, goal);
        }
    }

    /** 校验重复请求摘要，相同请求复用原任务，不同请求明确冲突。 */
    private TaskCreationResult duplicateResult(AgentTask task, String conversationId, String goal) {
        if (!requestDigest(task.conversationId(), task.goal()).equals(requestDigest(conversationId, goal))) {
            throw new AgentTaskIdempotencyException("相同幂等键对应了不同的 Agent Task 请求");
        }
        return new TaskCreationResult(task, false);
    }

    /** 使用会话和客户端幂等键生成跨重启稳定的任务标识。 */
    private String deterministicTaskId(String conversationId, String idempotencyKey) {
        String scopedKey = "agent-task\0" + conversationId + "\0" + idempotencyKey;
        return UUID.nameUUIDFromBytes(scopedKey.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 计算创建请求摘要，阻止相同键覆盖不同业务目标。 */
    private String requestDigest(String conversationId, String goal) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (conversationId + "\0" + goal).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", ex);
        }
    }

    /**
     * 按后端任务标识读取当前状态；空标识视为不存在。
     *
     * @param taskId 任务标识
     * @return 任务当前快照
     */
    public Optional<AgentTask> findTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Optional.empty();
        }
        return maintenanceLock.read(() -> taskStore.findById(taskId.trim()));
    }

    /**
     * 分页读取任务快照，避免历史任务一次进入内存。
     *
     * @param page 从零开始的页码
     * @param size 每页数量
     * @return 任务分页
     */
    public AgentTaskPage listTasks(int page, int size) {
        return maintenanceLock.read(() -> taskStore.list(page, size));
    }

    /** 在维护写锁持有期间重建 Task Store 索引。 */
    public void recoverIndex() {
        taskStore.recoverIndex();
    }

    /**
     * 按任务标识推进状态，并通过 Store 乐观版本避免并发覆盖。
     *
     * @param taskId 任务标识
     * @param targetState 目标状态
     * @param reason 状态变化原因
     * @return 更新后的任务快照
     */
    public AgentTask transitionTask(String taskId, AgentTaskState targetState, String reason) {
        return transitionTask(taskId, targetState, reason, null);
    }

    /**
     * 推进状态并在完成任务时原子写入同一任务快照中的受保护结果。
     *
     * @param taskId 任务标识
     * @param targetState 目标状态
     * @param reason 状态原因
     * @param protectedResult 加密后的最终结果；为空时保留当前结果
     * @return 更新后的任务快照
     */
    public AgentTask transitionTask(
            String taskId,
            AgentTaskState targetState,
            String reason,
            String protectedResult) {
        return maintenanceLock.read(() -> transitionTaskUnlocked(
                taskId, targetState, reason, protectedResult));
    }

    /** 仅当任务版本和状态仍与扫描快照一致时推进，避免超时与用户操作互相覆盖。 */
    public Optional<AgentTask> transitionTaskIfVersion(
            String taskId,
            long expectedVersion,
            AgentTaskState expectedState,
            AgentTaskState targetState,
            String reason) {
        return maintenanceLock.read(() -> transitionIfVersionUnlocked(
                taskId, expectedVersion, expectedState, targetState, reason));
    }

    /** 在 Store 乐观锁下执行条件状态推进，冲突表示任务已被其它请求处理。 */
    private Optional<AgentTask> transitionIfVersionUnlocked(
            String taskId,
            long expectedVersion,
            AgentTaskState expectedState,
            AgentTaskState targetState,
            String reason) {
        AgentTask current = taskStore.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 任务不存在: " + taskId));
        if (current.version() != expectedVersion || current.state() != expectedState) {
            return Optional.empty();
        }
        AgentTask updated = stateMachine.transition(current, targetState, reason, current.protectedResult());
        try {
            return Optional.of(taskStore.update(updated, expectedVersion));
        } catch (IllegalStateException ex) {
            AgentTask latest = taskStore.findById(taskId).orElseThrow(() -> ex);
            if (latest.version() != expectedVersion || latest.state() != expectedState) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    /** 在维护锁保护下推进任务状态。 */
    private AgentTask transitionTaskUnlocked(
            String taskId,
            AgentTaskState targetState,
            String reason,
            String protectedResult) {
        AgentTask current = taskStore.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 任务不存在: " + taskId));
        String effectiveResult = StringUtils.hasText(protectedResult)
                ? protectedResult
                : current.protectedResult();
        AgentTask updated = stateMachine.transition(current, targetState, reason, effectiveResult);
        return taskStore.update(updated, current.version());
    }

    /**
     * 读取并规范化必填文本。
     *
     * @param value 原始文本
     * @param message 校验失败消息
     * @return 去除首尾空白后的文本
     */
    private String required(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /** 将可空幂等键规范为空字符串，兼容旧内部调用方。 */
    private String optional(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 限制控制面输入长度，防止无界请求进入任务 Store。
     *
     * @param value 待检查文本
     * @param maxLength 最大字符数
     * @param fieldName 字段名称
     */
    private void validateLength(String value, int maxLength, String fieldName) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过 " + maxLength + " 个字符");
        }
    }

    /**
     * Agent Task 幂等创建结果。
     *
     * <p>by AI.Coding</p>
     *
     * @param task 当前任务
     * @param created 本次是否实际创建
     */
    public record TaskCreationResult(AgentTask task, boolean created) {
    }
}
