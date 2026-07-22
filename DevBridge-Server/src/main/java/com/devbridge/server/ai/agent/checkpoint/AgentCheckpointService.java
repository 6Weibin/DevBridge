package com.devbridge.server.ai.agent.checkpoint;

import com.devbridge.server.ai.config.AiConfigCrypto;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.store.AgentTaskStore;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Agent Checkpoint 应用服务，负责保存恢复点和执行一致性校验。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AgentCheckpointService {

    private final AgentTaskStore taskStore;
    private final AgentCheckpointStore checkpointStore;
    private final ObjectMapper objectMapper;
    private final AiConfigCrypto crypto;
    private final Path protectionRoot;

    /**
     * 注入任务和 Checkpoint 存储端口。
     *
     * @param taskStore 任务 Store
     * @param checkpointStore Checkpoint Store
     */
    @Autowired
    public AgentCheckpointService(
            AgentTaskStore taskStore,
            AgentCheckpointStore checkpointStore,
            ObjectMapper objectMapper,
            AiConfigCrypto crypto,
            DevBridgeProperties properties) {
        this(taskStore, checkpointStore, objectMapper, crypto,
                Path.of(properties.getAiAgentDataRoot()).resolve("protected"));
    }

    /**
     * 创建兼容测试的 Checkpoint 服务；生产环境使用 Spring 构造器启用正式数据目录。
     *
     * @param taskStore 任务 Store
     * @param checkpointStore Checkpoint Store
     */
    public AgentCheckpointService(AgentTaskStore taskStore, AgentCheckpointStore checkpointStore) {
        this(taskStore, checkpointStore, new ObjectMapper().findAndRegisterModules(),
                new AiConfigCrypto(), Path.of(System.getProperty("java.io.tmpdir"), "devbridge-agent-test-protected"));
    }

    /**
     * 统一初始化 Checkpoint 存储和受保护载荷编解码依赖。
     *
     * @param taskStore 任务 Store
     * @param checkpointStore Checkpoint Store
     * @param objectMapper JSON 编解码器
     * @param crypto AES-GCM 加解密工具
     * @param protectionRoot 受保护数据密钥目录
     */
    private AgentCheckpointService(
            AgentTaskStore taskStore,
            AgentCheckpointStore checkpointStore,
            ObjectMapper objectMapper,
            AiConfigCrypto crypto,
            Path protectionRoot) {
        this.taskStore = taskStore;
        this.checkpointStore = checkpointStore;
        this.objectMapper = objectMapper;
        this.crypto = crypto;
        this.protectionRoot = protectionRoot;
    }

    /**
     * 将恢复所需的小型结构化数据加密后再写入 Checkpoint。
     *
     * @param value 待保护对象
     * @return AES-GCM 密文
     */
    public String protect(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return crypto.encrypt(protectionRoot, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException ex) {
            throw new AgentCheckpointStoreException("Checkpoint 受保护载荷序列化失败", ex);
        }
    }

    /**
     * 解密并恢复 Checkpoint 中的小型结构化数据。
     *
     * @param protectedValue AES-GCM 密文
     * @param type 目标类型
     * @param <T> 目标类型
     * @return 恢复对象
     */
    public <T> T restore(String protectedValue, Class<T> type) {
        if (!StringUtils.hasText(protectedValue)) {
            return null;
        }
        try {
            return objectMapper.readValue(crypto.decrypt(protectionRoot, protectedValue), type);
        } catch (JsonProcessingException ex) {
            throw new AgentCheckpointStoreException("Checkpoint 受保护载荷解析失败", ex);
        }
    }

    /**
     * 根据任务当前版本保存新的 Checkpoint。
     *
     * @param taskId 任务标识
     * @param eventSequence 已包含事件序号
     * @param recoveryState 恢复状态
     * @return 已保存 Checkpoint
     */
    public AgentCheckpoint saveCheckpoint(
            String taskId, long eventSequence, AgentRecoveryState recoveryState) {
        AgentTask task = taskStore.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 任务不存在: " + taskId));
        validateRecoveryState(task, eventSequence, recoveryState);
        AgentCheckpoint checkpoint = new AgentCheckpoint(
                "checkpoint-" + UUID.randomUUID(),
                task.taskId(),
                task.version(),
                eventSequence,
                task.state(),
                recoveryState,
                Instant.now());
        return checkpointStore.save(checkpoint);
    }

    /**
     * 加载并验证任务与最后完整 Checkpoint 的一致性。
     *
     * @param taskId 任务标识
     * @return 可恢复任务
     */
    public Optional<AgentTaskRecovery> loadRecovery(String taskId) {
        Optional<AgentTask> task = taskStore.findById(taskId);
        Optional<AgentCheckpoint> checkpoint = checkpointStore.findLatest(taskId);
        if (task.isEmpty() || checkpoint.isEmpty()) {
            return Optional.empty();
        }
        validateCheckpoint(task.get(), checkpoint.get());
        return Optional.of(new AgentTaskRecovery(task.get(), checkpoint.get()));
    }

    /**
     * 校验保存时的状态约束。
     *
     * @param task 当前任务
     * @param eventSequence 事件序号
     * @param recoveryState 恢复状态
     */
    private void validateRecoveryState(
            AgentTask task, long eventSequence, AgentRecoveryState recoveryState) {
        if (eventSequence < 0) {
            throw new IllegalArgumentException("事件序号不能小于零");
        }
        if (recoveryState == null) {
            throw new IllegalArgumentException("恢复状态不能为空");
        }
        if (task.state() == AgentTaskState.WAITING_CONFIRMATION
                && !StringUtils.hasText(recoveryState.pendingConfirmationId())) {
            throw new IllegalArgumentException("等待确认任务必须保存确认标识");
        }
        if (task.state() == AgentTaskState.WAITING_INPUT
                && !StringUtils.hasText(recoveryState.pendingInputKey())) {
            throw new IllegalArgumentException("等待输入任务必须保存输入键");
        }
    }

    /**
     * 校验恢复点属于当前任务完整版本，防止使用过期或未来状态恢复。
     *
     * @param task 当前任务
     * @param checkpoint Checkpoint
     */
    private void validateCheckpoint(AgentTask task, AgentCheckpoint checkpoint) {
        if (!task.taskId().equals(checkpoint.taskId())) {
            throw new IllegalStateException("Checkpoint 与任务标识不一致");
        }
        if (task.state() == AgentTaskState.CANCELED
                && checkpoint.taskVersion() < task.version()) {
            // 取消终态在资源传播完成后提交，最后执行快照仍可用于展示已完成影响。
            return;
        }
        if (isPendingTransitionCheckpoint(task, checkpoint)) {
            // 等待状态切换和 Checkpoint 是两个原子文件，允许恢复紧邻状态切换前的预写恢复点。
            return;
        }
        if (task.state() == AgentTaskState.RUNNING
                && checkpoint.taskState() == AgentTaskState.WAITING_CONFIRMATION
                && task.version() == checkpoint.taskVersion() + 1
                && StringUtils.hasText(checkpoint.recoveryState().pendingConfirmationId())) {
            // 批准状态和 RUNNING 已提交但新版本 Checkpoint 尚未写入时，启动对账可继续完成提交。
            return;
        }
        if (task.version() != checkpoint.taskVersion()) {
            throw new IllegalStateException("Checkpoint 任务版本与当前任务版本不一致");
        }
        if (task.state() != checkpoint.taskState()) {
            throw new IllegalStateException("Checkpoint 任务状态与当前任务状态不一致");
        }
    }

    /**
     * 判断 Checkpoint 是否为等待确认/输入状态切换前的预写恢复点。
     *
     * @param task 当前任务
     * @param checkpoint 最近完整恢复点
     * @return 可以安全对账时返回 true
     */
    private boolean isPendingTransitionCheckpoint(AgentTask task, AgentCheckpoint checkpoint) {
        if (task.version() != checkpoint.taskVersion() + 1) {
            return false;
        }
        AgentRecoveryState state = checkpoint.recoveryState();
        if (task.state() == AgentTaskState.WAITING_CONFIRMATION) {
            return StringUtils.hasText(state.pendingConfirmationId());
        }
        return task.state() == AgentTaskState.WAITING_INPUT
                && StringUtils.hasText(state.pendingInputKey());
    }
}
