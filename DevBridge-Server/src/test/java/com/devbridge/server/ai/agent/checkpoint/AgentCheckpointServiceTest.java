package com.devbridge.server.ai.agent.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.runtime.AgentTaskService;
import com.devbridge.server.ai.agent.runtime.AgentTaskStateMachine;
import com.devbridge.server.ai.agent.runtime.CreateAgentTaskCommand;
import com.devbridge.server.ai.agent.store.AgentTaskFileCodec;
import com.devbridge.server.ai.agent.store.FileAgentTaskStore;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agent Checkpoint 服务测试，覆盖等待确认、工具去重和崩溃恢复。
 *
 * <p>by AI.Coding</p>
 */
class AgentCheckpointServiceTest {

    /**
     * 验证等待确认任务重启后仍保留原确认标识和执行位置。
     *
     * @param tempDir 临时存储目录
     */
    @Test
    void recoveryShouldRestoreWaitingConfirmation(@TempDir Path tempDir) {
        TestRuntime first = runtime(tempDir);
        AgentTask task = runningTask(first.taskService());
        task = first.taskService().transitionTask(
                task.taskId(), AgentTaskState.WAITING_CONFIRMATION, "等待确认");
        AgentRecoveryState state = recoveryState("step-install", "confirmation-1", Map.of());
        first.checkpointService().saveCheckpoint(task.taskId(), 12L, state);

        TestRuntime restarted = runtime(tempDir);
        AgentTaskRecovery recovery = restarted.checkpointService().loadRecovery(task.taskId()).orElseThrow();

        assertThat(recovery.task().state()).isEqualTo(AgentTaskState.WAITING_CONFIRMATION);
        assertThat(recovery.checkpoint().recoveryState().pendingConfirmationId()).isEqualTo("confirmation-1");
        assertThat(recovery.checkpoint().recoveryState().currentStepId()).isEqualTo("step-install");
    }

    /**
     * 验证服务重启后仍能恢复确认续跑所需的原问题、设备和最近文本历史。
     *
     * @param tempDir 临时存储目录
     */
    @Test
    void recoveryShouldRestoreConversationContinuationContext(@TempDir Path tempDir) {
        TestRuntime first = runtime(tempDir);
        AgentTask task = runningTask(first.taskService());
        task = first.taskService().transitionTask(
                task.taskId(), AgentTaskState.WAITING_CONFIRMATION, "等待确认");
        AgentRecoveryState.AgentContinuationContext continuation =
                new AgentRecoveryState.AgentContinuationContext(
                        "卸载应用后继续检查设备", "conversation-checkpoint",
                        new AgentRecoveryState.AgentDeviceSnapshot(
                                "android", "serial-1", "Pixel", "14", "connected"),
                        List.of(new AgentRecoveryState.AgentHistorySnapshot("user", "先检查应用状态")));
        AgentRecoveryState state = new AgentRecoveryState(
                "step-uninstall", List.of(), Map.of(), "confirmation-1", null, continuation);
        first.checkpointService().saveCheckpoint(task.taskId(), 13L, state);

        AgentRecoveryState restored = runtime(tempDir).checkpointService()
                .loadRecovery(task.taskId()).orElseThrow().checkpoint().recoveryState();

        assertThat(restored.continuationContext().message()).isEqualTo("卸载应用后继续检查设备");
        assertThat(restored.continuationContext().device().serial()).isEqualTo("serial-1");
        assertThat(restored.continuationContext().history())
                .containsExactly(new AgentRecoveryState.AgentHistorySnapshot("user", "先检查应用状态"));
    }

    /**
     * 验证新增续跑字段后仍可读取不含该字段的旧 Checkpoint 文件。
     *
     * @param tempDir 临时存储目录
     * @throws Exception 文件处理失败时抛出
     */
    @Test
    void codecShouldReadLegacyCheckpointWithoutContinuationField(@TempDir Path tempDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AgentCheckpointFileCodec codec = new AgentCheckpointFileCodec(mapper);
        AgentCheckpoint checkpoint = new AgentCheckpoint(
                "checkpoint-legacy", "task-legacy", 3, 8, AgentTaskState.RUNNING,
                new AgentRecoveryState("step-legacy", List.of(), Map.of(), null, null), Instant.now());
        Path file = tempDir.resolve("checkpoint-legacy.json");
        codec.writeCheckpoint(file, checkpoint);
        ObjectNode envelope = (ObjectNode) mapper.readTree(file.toFile());
        ObjectNode recovery = (ObjectNode) envelope.path("payload").path("recoveryState");
        recovery.remove("continuationContext");
        byte[] payload = mapper.writeValueAsBytes(envelope.path("payload"));
        String checksum = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload));
        envelope.put("sha256", checksum);
        Files.write(file, mapper.writeValueAsBytes(envelope));

        AgentCheckpoint restored = codec.readCheckpoint(file);

        assertThat(restored.recoveryState().continuationContext()).isNull();
        assertThat(restored.recoveryState().currentStepId()).isEqualTo("step-legacy");
    }

    /**
     * 验证当前 Checkpoint 写入后可直接读取，摘要计算必须使用同一 payload。
     *
     * @param tempDir 临时存储目录
     */
    @Test
    void codecShouldReadCurrentCheckpoint(@TempDir Path tempDir) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AgentCheckpointFileCodec codec = new AgentCheckpointFileCodec(mapper);
        AgentCheckpoint checkpoint = new AgentCheckpoint(
                "checkpoint-current", "task-current", 3, 8, AgentTaskState.RUNNING,
                new AgentRecoveryState("step-current", List.of(), Map.of(), null, null), Instant.now());
        Path file = tempDir.resolve("checkpoint-current.json");

        codec.writeCheckpoint(file, checkpoint);

        assertThat(codec.readCheckpoint(file)).isEqualTo(checkpoint);
    }

    /**
     * 验证已成功工具调用在恢复后被识别为已完成，不能重放。
     *
     * @param tempDir 临时存储目录
     */
    @Test
    void recoveryShouldSkipCompletedToolCall(@TempDir Path tempDir) {
        TestRuntime first = runtime(tempDir);
        AgentTask task = runningTask(first.taskService());
        AgentToolCallCheckpoint tool = new AgentToolCallCheckpoint(
                "tool-call-1", "step-query", AgentToolCallCheckpointStatus.SUCCEEDED,
                "idempotency-1", "digest-1", "result:42", true);
        AgentRecoveryState state = new AgentRecoveryState(
                "step-summary", List.of("step-query"), Map.of(tool.toolCallId(), tool), null, null);
        first.checkpointService().saveCheckpoint(task.taskId(), 42L, state);

        AgentTaskRecovery recovery = runtime(tempDir).checkpointService().loadRecovery(task.taskId()).orElseThrow();

        assertThat(recovery.checkpoint().recoveryState().shouldSkipToolCall("tool-call-1")).isTrue();
        assertThat(recovery.checkpoint().recoveryState().completedStepIds()).containsExactly("step-query");
    }

    /**
     * 验证最新 Checkpoint 损坏后会回退到上一个完整版本。
     *
     * @param tempDir 临时存储目录
     * @throws Exception 文件写入失败时抛出
     */
    @Test
    void recoveryShouldFallbackToPreviousCheckpoint(@TempDir Path tempDir) throws Exception {
        TestRuntime runtime = runtime(tempDir);
        AgentTask task = runningTask(runtime.taskService());
        AgentCheckpoint first = runtime.checkpointService().saveCheckpoint(
                task.taskId(), 10L, recoveryState("step-1", null, Map.of()));
        AgentCheckpoint second = runtime.checkpointService().saveCheckpoint(
                task.taskId(), 20L, recoveryState("step-2", null, Map.of()));
        Files.writeString(findCheckpoint(tempDir, second.checkpointId()), "{broken");

        AgentTaskRecovery recovery = runtime(tempDir).checkpointService().loadRecovery(task.taskId()).orElseThrow();

        assertThat(recovery.checkpoint().checkpointId()).isEqualTo(first.checkpointId());
        assertThat(recovery.checkpoint().eventSequence()).isEqualTo(10L);
    }

    /**
     * 验证未来任务版本的 Checkpoint 不会覆盖当前任务状态。
     *
     * @param tempDir 临时存储目录
     */
    @Test
    void recoveryShouldRejectCheckpointAheadOfTaskVersion(@TempDir Path tempDir) {
        TestRuntime runtime = runtime(tempDir);
        AgentTask task = runningTask(runtime.taskService());
        AgentCheckpoint invalid = new AgentCheckpoint(
                "checkpoint-invalid", task.taskId(), task.version() + 1, 1L,
                task.state(), recoveryState("step", null, Map.of()), Instant.now());
        runtime.checkpointStore().save(invalid);

        assertThatThrownBy(() -> runtime.checkpointService().loadRecovery(task.taskId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("任务版本");
    }

    /**
     * 验证等待确认状态必须保存对应确认记录标识。
     *
     * @param tempDir 临时存储目录
     */
    @Test
    void checkpointShouldRejectWaitingStateWithoutConfirmation(@TempDir Path tempDir) {
        TestRuntime runtime = runtime(tempDir);
        AgentTask task = runningTask(runtime.taskService());
        task = runtime.taskService().transitionTask(
                task.taskId(), AgentTaskState.WAITING_CONFIRMATION, "等待确认");
        String taskId = task.taskId();

        assertThatThrownBy(() -> runtime.checkpointService().saveCheckpoint(
                taskId, 1L, recoveryState("step", null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("确认标识");
    }

    /**
     * 创建完整测试 Runtime。
     *
     * @param root 存储根目录
     * @return 测试 Runtime
     */
    private TestRuntime runtime(Path root) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiAgentDataRoot(root.toString());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileAgentTaskStore taskStore = new FileAgentTaskStore(properties, new AgentTaskFileCodec(mapper));
        AgentTaskService taskService = new AgentTaskService(taskStore, new AgentTaskStateMachine());
        FileAgentCheckpointStore checkpointStore = new FileAgentCheckpointStore(
                properties, new AgentCheckpointFileCodec(mapper));
        return new TestRuntime(taskService, checkpointStore, new AgentCheckpointService(taskStore, checkpointStore));
    }

    /**
     * 创建运行中任务。
     *
     * @param service 任务服务
     * @return 运行中任务
     */
    private AgentTask runningTask(AgentTaskService service) {
        AgentTask task = service.createTask(new CreateAgentTaskCommand("conversation-checkpoint", "执行任务"));
        task = service.transitionTask(task.taskId(), AgentTaskState.PLANNING, "开始规划");
        return service.transitionTask(task.taskId(), AgentTaskState.RUNNING, "开始执行");
    }

    /**
     * 创建恢复状态。
     *
     * @param stepId 当前步骤
     * @param confirmationId 待确认标识
     * @param toolCalls 工具状态
     * @return 恢复状态
     */
    private AgentRecoveryState recoveryState(
            String stepId, String confirmationId, Map<String, AgentToolCallCheckpoint> toolCalls) {
        return new AgentRecoveryState(stepId, List.of(), toolCalls, confirmationId, null);
    }

    /**
     * 查找指定 Checkpoint 文件。
     *
     * @param root 存储根目录
     * @param checkpointId Checkpoint 标识
     * @return Checkpoint 文件
     * @throws Exception 搜索失败时抛出
     */
    private Path findCheckpoint(Path root, String checkpointId) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().equals(checkpointId + ".json"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    /**
     * 测试 Runtime 依赖集合。
     *
     * @param taskService 任务服务
     * @param checkpointStore Checkpoint Store
     * @param checkpointService Checkpoint 服务
     */
    private record TestRuntime(
            AgentTaskService taskService,
            FileAgentCheckpointStore checkpointStore,
            AgentCheckpointService checkpointService) {
    }
}
