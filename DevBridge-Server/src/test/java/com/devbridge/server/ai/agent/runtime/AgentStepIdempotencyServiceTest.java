package com.devbridge.server.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointFileCodec;
import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointService;
import com.devbridge.server.ai.agent.checkpoint.AgentToolCallCheckpointStatus;
import com.devbridge.server.ai.agent.checkpoint.FileAgentCheckpointStore;
import com.devbridge.server.ai.agent.event.AgentEventFileCodec;
import com.devbridge.server.ai.agent.event.FileAgentEventStore;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.store.AgentTaskFileCodec;
import com.devbridge.server.ai.agent.store.FileAgentTaskStore;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agent 步骤幂等服务测试，覆盖工具预留、冲突和重启复用。
 *
 * <p>by AI.Coding</p>
 */
class AgentStepIdempotencyServiceTest {

    /**
     * 验证首次请求可以执行，重复请求直接复用预留记录。
     *
     * @param tempDir 临时数据目录
     */
    @Test
    void reserveShouldDeduplicateSameToolCallAndKey(@TempDir Path tempDir) {
        TestRuntime runtime = runtime(tempDir);
        AgentTask task = runningTask(runtime.taskService());
        AgentToolExecutionRequest request = request(task.taskId(), "digest-1");

        AgentToolExecutionDecision first = runtime.idempotencyService().reserve(request);
        AgentToolExecutionDecision duplicate = runtime.idempotencyService().reserve(request);

        assertThat(first.execute()).isTrue();
        assertThat(duplicate.execute()).isFalse();
        assertThat(duplicate.checkpoint().toolCallId()).isEqualTo("tool-call-1");
    }

    /**
     * 验证相同幂等键对应不同参数摘要时拒绝执行。
     *
     * @param tempDir 临时数据目录
     */
    @Test
    void reserveShouldRejectChangedArguments(@TempDir Path tempDir) {
        TestRuntime runtime = runtime(tempDir);
        AgentTask task = runningTask(runtime.taskService());
        runtime.idempotencyService().reserve(request(task.taskId(), "digest-1"));

        assertThatThrownBy(() -> runtime.idempotencyService().reserve(request(task.taskId(), "digest-2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("参数摘要");
    }

    /**
     * 验证工具成功结果在后端重启后仍直接复用，不再次执行。
     *
     * @param tempDir 临时数据目录
     */
    @Test
    void completedResultShouldBeReusedAfterRestart(@TempDir Path tempDir) {
        TestRuntime first = runtime(tempDir);
        AgentTask task = runningTask(first.taskService());
        AgentToolExecutionRequest request = request(task.taskId(), "digest-success");
        first.idempotencyService().reserve(request);
        first.idempotencyService().complete(
                task.taskId(), "tool-call-1", AgentToolCallCheckpointStatus.SUCCEEDED,
                "result:100", true);

        AgentToolExecutionDecision duplicate = runtime(tempDir).idempotencyService().reserve(request);

        assertThat(duplicate.execute()).isFalse();
        assertThat(duplicate.checkpoint().status()).isEqualTo(AgentToolCallCheckpointStatus.SUCCEEDED);
        assertThat(duplicate.checkpoint().resultReference()).isEqualTo("result:100");
    }

    /**
     * 验证结果未知的写操作不会被当成可再次执行。
     *
     * @param tempDir 临时数据目录
     */
    @Test
    void unknownResultShouldNotBeRetried(@TempDir Path tempDir) {
        TestRuntime runtime = runtime(tempDir);
        AgentTask task = runningTask(runtime.taskService());
        AgentToolExecutionRequest request = request(task.taskId(), "digest-unknown");
        runtime.idempotencyService().reserve(request);
        runtime.idempotencyService().complete(
                task.taskId(), "tool-call-1", AgentToolCallCheckpointStatus.UNKNOWN,
                "result:unknown", false);

        AgentToolExecutionDecision duplicate = runtime.idempotencyService().reserve(request);

        assertThat(duplicate.execute()).isFalse();
        assertThat(duplicate.checkpoint().status()).isEqualTo(AgentToolCallCheckpointStatus.UNKNOWN);
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
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileAgentTaskStore taskStore = new FileAgentTaskStore(properties, new AgentTaskFileCodec(mapper));
        AgentTaskService taskService = new AgentTaskService(taskStore, new AgentTaskStateMachine());
        AgentCheckpointService checkpointService = new AgentCheckpointService(
                taskStore, new FileAgentCheckpointStore(properties, new AgentCheckpointFileCodec(mapper)));
        FileAgentEventStore eventStore = new FileAgentEventStore(properties, new AgentEventFileCodec(mapper));
        AgentStepIdempotencyService idempotencyService = new AgentStepIdempotencyService(
                taskService, checkpointService, eventStore);
        return new TestRuntime(taskService, idempotencyService);
    }

    /**
     * 创建运行中任务。
     *
     * @param service 任务服务
     * @return 运行中任务
     */
    private AgentTask runningTask(AgentTaskService service) {
        AgentTask task = service.createTask(new CreateAgentTaskCommand("conversation-idempotency", "执行工具"));
        task = service.transitionTask(task.taskId(), AgentTaskState.PLANNING, "开始规划");
        return service.transitionTask(task.taskId(), AgentTaskState.RUNNING, "开始执行");
    }

    /**
     * 创建工具执行请求。
     *
     * @param taskId 任务标识
     * @param digest 参数摘要
     * @return 工具执行请求
     */
    private AgentToolExecutionRequest request(String taskId, String digest) {
        return new AgentToolExecutionRequest(
                taskId, "step-1", "tool-call-1", "idempotency-1", digest, true);
    }

    /**
     * 测试 Runtime 依赖集合。
     *
     * @param taskService 任务服务
     * @param idempotencyService 幂等服务
     */
    private record TestRuntime(
            AgentTaskService taskService, AgentStepIdempotencyService idempotencyService) {
    }
}
