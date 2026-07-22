package com.devbridge.server.ai.agent.confirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointFileCodec;
import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointService;
import com.devbridge.server.ai.agent.checkpoint.FileAgentCheckpointStore;
import com.devbridge.server.ai.agent.checkpoint.AgentTaskRecovery;
import com.devbridge.server.ai.agent.event.AgentEventFileCodec;
import com.devbridge.server.ai.agent.event.AgentEventSequencer;
import com.devbridge.server.ai.agent.event.FileAgentEventStore;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.runtime.AgentTaskService;
import com.devbridge.server.ai.agent.runtime.AgentTaskStateMachine;
import com.devbridge.server.ai.agent.runtime.CreateAgentTaskCommand;
import com.devbridge.server.ai.agent.store.AgentTaskFileCodec;
import com.devbridge.server.ai.agent.store.FileAgentTaskStore;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.ai.config.AiConfigCrypto;
import com.devbridge.server.ai.conversation.AiConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 确认协调器测试，覆盖等待、批准自动续跑、拒绝和幂等。
 *
 * <p>by AI.Coding</p>
 */
class AgentConfirmationCoordinatorTest {

    /**
     * 验证请求确认后任务进入等待状态并保存绑定信息。
     *
     * @param tempDir 临时数据目录
     */
    @Test
    void requestShouldPauseTaskAndPersistConfirmation(@TempDir Path tempDir) {
        TestRuntime runtime = runtime(tempDir);
        AgentTask task = runningTask(runtime.taskService());

        AgentConfirmation confirmation = runtime.coordinator().request(request(task.taskId()));

        assertThat(runtime.taskService().findTask(task.taskId()).orElseThrow().state())
                .isEqualTo(AgentTaskState.WAITING_CONFIRMATION);
        assertThat(runtime.confirmationStore().find(task.taskId(), confirmation.confirmationId()))
                .contains(confirmation);
        assertThat(runtime.checkpointService().loadRecovery(task.taskId()).orElseThrow()
                .checkpoint().recoveryState().pendingConfirmationId())
                .isEqualTo(confirmation.confirmationId());
    }

    /**
     * 验证批准后返回后端续跑所需恢复上下文，不要求新聊天消息。
     *
     * @param tempDir 临时数据目录
     */
    @Test
    void approveShouldResumeTaskAutomaticallyOnce(@TempDir Path tempDir) {
        TestRuntime runtime = runtime(tempDir);
        AgentTask task = runningTask(runtime.taskService());
        AgentConfirmation confirmation = runtime.coordinator().request(request(task.taskId()));

        AgentConfirmationCoordinator.AgentConfirmationApproval approval =
                runtime.coordinator().approve(task.taskId(), confirmation.confirmationId());
        AgentConfirmationCoordinator.AgentConfirmationApproval repeated =
                runtime.coordinator().approve(task.taskId(), confirmation.confirmationId());

        assertThat(runtime.taskService().findTask(task.taskId()).orElseThrow().state())
                .isEqualTo(AgentTaskState.RUNNING);
        assertThat(approval.recovery().task().taskId()).isEqualTo(task.taskId());
        assertThat(repeated.confirmation().status()).isEqualTo(AgentConfirmationStatus.ACCEPTED);
        assertThat(runtime.confirmationStore().find(task.taskId(), confirmation.confirmationId()).orElseThrow().status())
                .isEqualTo(AgentConfirmationStatus.ACCEPTED);
    }

    /** 两个并发重复批准都返回同一恢复结果，任务只提交一次确认状态。 */
    @Test
    void concurrentApprovalShouldRemainIdempotent(@TempDir Path tempDir) throws Exception {
        TestRuntime runtime = runtime(tempDir);
        AgentTask task = runningTask(runtime.taskService());
        AgentConfirmation confirmation = runtime.coordinator().request(request(task.taskId()));
        Callable<AgentConfirmationCoordinator.AgentConfirmationApproval> approve = () ->
                runtime.coordinator().approve(task.taskId(), confirmation.confirmationId());

        var executor = Executors.newFixedThreadPool(2);
        try {
            var approvals = executor.invokeAll(List.of(approve, approve)).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    }).toList();
            assertThat(approvals).allMatch(value -> value.confirmation().status() == AgentConfirmationStatus.ACCEPTED);
            assertThat(approvals).allMatch(value -> value.recovery().task().taskId().equals(task.taskId()));
        } finally {
            executor.shutdownNow();
        }
    }

    /** 签名令牌必须同时匹配原任务、确认和会话，错误请求不能改变等待状态。 */
    @Test
    void signedApprovalShouldRejectWrongConversationAndSignature(@TempDir Path tempDir) {
        TestRuntime runtime = runtime(tempDir);
        AgentConfirmationTokenService tokenService = new AgentConfirmationTokenService(
                new AiConfigCrypto(), runtime.properties());
        AgentConfirmationCoordinator secure = new AgentConfirmationCoordinator(
                runtime.taskService(), runtime.checkpointService(), runtime.confirmationStore(),
                runtime.sequencer(), runtime.eventStore(), tokenService);
        AgentTask task = runningTask(runtime.taskService());
        AgentConfirmation confirmation = secure.request(request(task.taskId()));
        String token = secure.approvalToken(confirmation);

        assertThatThrownBy(() -> secure.approve(
                task.taskId(), confirmation.confirmationId(), "other-conversation", token))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> secure.approve(
                task.taskId(), confirmation.confirmationId(), task.conversationId(), "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(runtime.taskService().findTask(task.taskId()).orElseThrow().state())
                .isEqualTo(AgentTaskState.WAITING_CONFIRMATION);
        assertThat(secure.approve(
                task.taskId(), confirmation.confirmationId(), task.conversationId(), token)
                .confirmation().status()).isEqualTo(AgentConfirmationStatus.ACCEPTED);
    }

    /**
     * 验证任务完成后的重复确认直接返回终态，不再加载旧 Checkpoint 或重新执行工具。
     *
     * @param tempDir 临时数据目录
     */
    @Test
    void repeatedApprovalShouldReturnCompletedTask(@TempDir Path tempDir) {
        TestRuntime runtime = runtime(tempDir);
        AgentTask task = runningTask(runtime.taskService());
        AgentConfirmation confirmation = runtime.coordinator().request(request(task.taskId()));
        AgentConfirmation accepted = runtime.coordinator()
                .approve(task.taskId(), confirmation.confirmationId()).confirmation();
        AgentConfirmation consumed = consumed(accepted);
        runtime.confirmationStore().update(consumed, AgentConfirmationStatus.ACCEPTED);
        runtime.taskService().transitionTask(task.taskId(), AgentTaskState.COMPLETED, "任务完成");

        AgentConfirmationCoordinator.AgentConfirmationApproval repeated =
                runtime.coordinator().approve(task.taskId(), confirmation.confirmationId());

        assertThat(repeated.recovery()).isNull();
        assertThat(repeated.terminalTask().state()).isEqualTo(AgentTaskState.COMPLETED);
    }

    /**
     * 验证服务启动扫描会从持久 Checkpoint 自动恢复已经消费的确认任务。
     *
     * @param tempDir 临时数据目录
     */
    @Test
    void startupRecoveryShouldResumeConsumedConfirmation(@TempDir Path tempDir) {
        TestRuntime runtime = runtime(tempDir);
        AgentTask task = runningTask(runtime.taskService());
        AgentConfirmation confirmation = runtime.coordinator().request(request(task.taskId()));
        AgentConfirmation accepted = runtime.coordinator()
                .approve(task.taskId(), confirmation.confirmationId()).confirmation();
        AgentConfirmation consumed = consumed(accepted);
        runtime.confirmationStore().update(consumed, AgentConfirmationStatus.ACCEPTED);
        RecordingConversation conversation = new RecordingConversation();
        AgentRuntimeContinuationService continuation = new AgentRuntimeContinuationService(
                runtime.sequencer(), conversation, runtime.confirmationStore(), null,
                runtime.taskService(), runtime.checkpointService(), null);

        continuation.recoverPersistedContinuations();

        assertThat(conversation.recovery.task().taskId()).isEqualTo(task.taskId());
        assertThat(conversation.confirmation).isEqualTo(consumed);
    }

    /**
     * 将已接受确认转换为已消费状态，模拟服务重启发生在续跑执行期间。
     *
     * @param accepted 已接受确认
     * @return 已消费确认
     */
    private AgentConfirmation consumed(AgentConfirmation accepted) {
        return new AgentConfirmation(
                accepted.confirmationId(), accepted.taskId(), accepted.binding(),
                AgentConfirmationStatus.CONSUMED, accepted.createdAt(), accepted.expiresAt(),
                accepted.decidedAt(), accepted.decisionReason());
    }

    /**
     * 记录启动恢复调用的显式对话服务 Fake，避免依赖字节码代理。
     *
     * <p>by AI.Coding</p>
     */
    private static final class RecordingConversation extends AiConversationService {

        private AgentTaskRecovery recovery;
        private AgentConfirmation confirmation;

        /**
         * 创建不访问 Provider 的记录服务。
         */
        private RecordingConversation() {
            super(null, null, null, null, null, null, null, null);
        }

        /**
         * 记录恢复参数，并执行清理回调模拟任务进入后续生命周期。
         *
         * @param recovery 恢复上下文
         * @param confirmation 已消费确认
         * @param emitter 后台事件流
         * @param terminalCallback 终态清理回调
         */
        @Override
        public void continueAfterConfirmation(
                AgentTaskRecovery recovery,
                AgentConfirmation confirmation,
                SseEmitter emitter,
                Runnable terminalCallback) {
            this.recovery = recovery;
            this.confirmation = confirmation;
            terminalCallback.run();
        }
    }

    /**
     * 验证拒绝后任务失败且不会调用后续执行入口。
     *
     * @param tempDir 临时数据目录
     */
    @Test
    void rejectShouldStopDependentExecution(@TempDir Path tempDir) {
        TestRuntime runtime = runtime(tempDir);
        AgentTask task = runningTask(runtime.taskService());
        AgentConfirmation confirmation = runtime.coordinator().request(request(task.taskId()));

        runtime.coordinator().reject(task.taskId(), confirmation.confirmationId(), "用户拒绝卸载");

        assertThat(runtime.taskService().findTask(task.taskId()).orElseThrow().state())
                .isEqualTo(AgentTaskState.FAILED);
        assertThat(runtime.confirmationStore().find(task.taskId(), confirmation.confirmationId()).orElseThrow().status())
                .isEqualTo(AgentConfirmationStatus.REJECTED);
    }

    /**
     * 创建测试 Runtime。
     *
     * @param root 临时数据目录
     * @return 测试 Runtime
     */
    private TestRuntime runtime(Path root) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiAgentDataRoot(root.toString());
        properties.setAiConfigRoot(root.resolve("config").toString());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileAgentTaskStore taskStore = new FileAgentTaskStore(properties, new AgentTaskFileCodec(mapper));
        AgentTaskService taskService = new AgentTaskService(taskStore, new AgentTaskStateMachine());
        FileAgentCheckpointStore checkpointStore = new FileAgentCheckpointStore(
                properties, new AgentCheckpointFileCodec(mapper));
        AgentCheckpointService checkpointService = new AgentCheckpointService(taskStore, checkpointStore);
        FileAgentEventStore eventStore = new FileAgentEventStore(properties, new AgentEventFileCodec(mapper));
        AgentEventSequencer sequencer = new AgentEventSequencer(eventStore, event -> { });
        FileAgentConfirmationStore confirmationStore = new FileAgentConfirmationStore(
                properties, new AgentConfirmationFileCodec(mapper));
        AgentConfirmationCoordinator coordinator = new AgentConfirmationCoordinator(
                taskService, checkpointService, confirmationStore, sequencer, eventStore);
        return new TestRuntime(
                taskService, checkpointService, confirmationStore, coordinator,
                sequencer, eventStore, properties);
    }

    /**
     * 创建运行中任务。
     *
     * @param service 任务服务
     * @return 运行中任务
     */
    private AgentTask runningTask(AgentTaskService service) {
        AgentTask task = service.createTask(new CreateAgentTaskCommand("conversation-confirm", "执行敏感任务"));
        task = service.transitionTask(task.taskId(), AgentTaskState.PLANNING, "开始规划");
        return service.transitionTask(task.taskId(), AgentTaskState.RUNNING, "开始执行");
    }

    /**
     * 创建确认请求。
     *
     * @param taskId 任务标识
     * @return 确认请求
     */
    private AgentConfirmationRequest request(String taskId) {
        return new AgentConfirmationRequest(
                taskId, "step-uninstall", "tool-call-uninstall", "app.uninstall",
                "sha256:arguments", AgentConfirmationRiskLevel.MEDIUM,
                "将卸载目标应用", Duration.ofMinutes(2));
    }

    /**
     * 测试 Runtime 依赖集合。
     *
     * @param taskService 任务服务
     * @param checkpointService Checkpoint 服务
     * @param confirmationStore 确认 Store
     * @param coordinator 确认协调器
     * @param sequencer 事件序列器
     */
    private record TestRuntime(
            AgentTaskService taskService,
            AgentCheckpointService checkpointService,
            FileAgentConfirmationStore confirmationStore,
            AgentConfirmationCoordinator coordinator,
            AgentEventSequencer sequencer,
            FileAgentEventStore eventStore,
            DevBridgeProperties properties) {
    }
}
