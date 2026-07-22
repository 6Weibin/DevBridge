package com.devbridge.server.ai.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devbridge.server.ai.agent.event.AgentEventFileCodec;
import com.devbridge.server.ai.agent.event.AgentEventSequencer;
import com.devbridge.server.ai.agent.event.AgentEventSubscriptionService;
import com.devbridge.server.ai.agent.event.FileAgentEventStore;
import com.devbridge.server.ai.agent.runtime.AgentTaskApplicationService;
import com.devbridge.server.ai.agent.runtime.AgentCancellationHandleType;
import com.devbridge.server.ai.agent.runtime.AgentTaskCancellationCoordinator;
import com.devbridge.server.ai.agent.runtime.AgentTaskService;
import com.devbridge.server.ai.agent.runtime.AgentTaskStateMachine;
import com.devbridge.server.ai.agent.runtime.CreateAgentTaskCommand;
import com.devbridge.server.ai.agent.store.InMemoryAgentTaskStore;
import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointFileCodec;
import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointService;
import com.devbridge.server.ai.agent.checkpoint.AgentTaskRecovery;
import com.devbridge.server.ai.agent.checkpoint.FileAgentCheckpointStore;
import com.devbridge.server.ai.conversation.AiConversationService;
import com.devbridge.server.api.ApiExceptionHandler;
import com.devbridge.server.config.DevBridgeExecutorProperties;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Agent Task API 测试，覆盖创建、查询、取消、事件游标和 SSE。
 *
 * <p>by AI.Coding</p>
 */
class AgentTaskControllerTest {

    /**
     * 验证客户端只提交会话和目标即可创建后端任务。
     *
     * @param tempDir 临时事件目录
     * @throws Exception API 调用失败时抛出
     */
    @Test
    void createShouldReturnBackendTaskAndCreatedEvent(@TempDir Path tempDir) throws Exception {
        TestApi api = api(tempDir);

        MvcResult result = api.mockMvc().perform(post("/api/ai/agent/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conversation-api\",\"goal\":\"查询设备状态\",\"idempotencyKey\":\"turn-api-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").isNotEmpty())
                .andExpect(jsonPath("$.state").value("CREATED"))
                .andReturn();
        String taskId = api.mapper().readTree(result.getResponse().getContentAsString()).get("taskId").asText();

        api.mockMvc().perform(get("/api/ai/agent/tasks/{taskId}/events", taskId).param("after", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("TASK_CREATED"))
                .andExpect(jsonPath("$[0].eventSequence").value("1"));
    }

    /** 相同幂等请求只创建一次，复用键但修改目标时返回冲突。 */
    @Test
    void createShouldBeIdempotentAndRejectChangedGoal(@TempDir Path tempDir) throws Exception {
        TestApi api = api(tempDir);
        String body = "{\"conversationId\":\"conversation-api\",\"goal\":\"查询设备状态\","
                + "\"idempotencyKey\":\"turn-stable\"}";

        String first = api.mockMvc().perform(post("/api/ai/agent/tasks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String repeated = api.mockMvc().perform(post("/api/ai/agent/tasks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String taskId = api.mapper().readTree(first).path("taskId").asText();

        org.assertj.core.api.Assertions.assertThat(api.mapper().readTree(repeated).path("taskId").asText())
                .isEqualTo(taskId);
        api.mockMvc().perform(get("/api/ai/agent/tasks/{taskId}/events", taskId))
                .andExpect(jsonPath("$.length()").value(1));
        api.mockMvc().perform(post("/api/ai/agent/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("查询设备状态", "卸载应用")))
                .andExpect(status().isConflict());
    }

    /** 运行中任务暂停后由同一会话恢复，并自动启动原任务 SSE 续跑。 */
    @Test
    void pauseAndResumeShouldPreserveTaskIdentity(@TempDir Path tempDir) throws Exception {
        TestApi api = api(tempDir);
        var task = api.application().startTask(new CreateAgentTaskCommand(
                "conversation-pause", "检查设备", "turn-pause"));

        api.mockMvc().perform(post("/api/ai/agent/tasks/{taskId}/pause", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PAUSED"));
        api.mockMvc().perform(post("/api/ai/agent/tasks/{taskId}/resume", task.taskId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conversation-pause\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
        org.assertj.core.api.Assertions.assertThat(
                api.taskService().findTask(task.taskId()).orElseThrow().state().name()).isEqualTo("RUNNING");
        assertThat(api.conversationService().continuations).hasValue(1);
    }

    /** 等待项只接受原会话和原 inputKey，成功提交后自动恢复运行。 */
    @Test
    void inputShouldResumeWaitingTaskWithProtectedValue(@TempDir Path tempDir) throws Exception {
        TestApi api = api(tempDir);
        var task = api.application().startTask(new CreateAgentTaskCommand(
                "conversation-input", "卸载指定应用", "turn-input"));
        api.application().waitForInput(task.taskId(), "packageName", "缺少应用包名");

        api.mockMvc().perform(post("/api/ai/agent/tasks/{taskId}/input", task.taskId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conversation-input\",\"inputKey\":\"packageName\","
                                + "\"value\":\"com.example.demo\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        var recovery = api.checkpoints().loadRecovery(task.taskId()).orElseThrow();
        assertThat(recovery.task().state().name()).isEqualTo("RUNNING");
        assertThat(api.checkpoints().restore(
                recovery.checkpoint().recoveryState().protectedInputValue(), String.class))
                .isEqualTo("com.example.demo");
        assertThat(api.conversationService().continuations).hasValue(1);
    }

    /** 过期扫描持有的旧版本不能把刚提交输入并恢复的任务改成失败。 */
    @Test
    void staleInputTimeoutShouldNotFailResumedTask(@TempDir Path tempDir) {
        TestApi api = api(tempDir);
        var task = api.application().startTask(new CreateAgentTaskCommand(
                "conversation-input-race", "等待输入", "turn-input-race"));
        var waiting = api.application().waitForInput(task.taskId(), "value", "等待用户输入");

        api.application().acceptInput(task.taskId(), "conversation-input-race", "value", "已提交");

        assertThat(api.application().expireWaitingInput(task.taskId(), waiting.version())).isEmpty();
        assertThat(api.taskService().findTask(task.taskId()).orElseThrow().state().name()).isEqualTo("RUNNING");
    }

    /** Runtime 总开关关闭时控制面明确返回不可用。 */
    @Test
    void createShouldRejectWhenRuntimeDisabled(@TempDir Path tempDir) throws Exception {
        TestApi api = api(tempDir, false);

        api.mockMvc().perform(post("/api/ai/agent/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conversation-api\",\"goal\":\"测试任务\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_AGENT_RUNTIME_DISABLED"));
    }

    /**
     * 验证任务查询和分页接口返回当前后端状态。
     *
     * @param tempDir 临时事件目录
     * @throws Exception API 调用失败时抛出
     */
    @Test
    void queryShouldReturnTaskAndPage(@TempDir Path tempDir) throws Exception {
        TestApi api = api(tempDir);
        String taskId = createTask(api);

        api.mockMvc().perform(get("/api/ai/agent/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId));
        api.mockMvc().perform(get("/api/ai/agent/tasks").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    /**
     * 验证取消接口幂等，重复取消不会产生非法终态回退。
     *
     * @param tempDir 临时事件目录
     * @throws Exception API 调用失败时抛出
     */
    @Test
    void cancelShouldBeIdempotent(@TempDir Path tempDir) throws Exception {
        TestApi api = api(tempDir);
        String taskId = createTask(api);
        AtomicInteger canceledHandles = new AtomicInteger();
        api.cancellations().find(taskId).orElseThrow().register(
                AgentCancellationHandleType.TOOL,
                "tool-api",
                canceledHandles::incrementAndGet);
        api.cancellations().find(taskId).orElseThrow().register(
                AgentCancellationHandleType.MODEL,
                "provider-cancel-error",
                () -> api.application().failTask(taskId, "Provider 主动取消回调"));

        api.mockMvc().perform(post("/api/ai/agent/tasks/{taskId}/cancel", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELED"));
        api.mockMvc().perform(post("/api/ai/agent/tasks/{taskId}/cancel", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELED"));
        org.assertj.core.api.Assertions.assertThat(canceledHandles).hasValue(1);
        org.assertj.core.api.Assertions.assertThat(api.cancellations().find(taskId)).isEmpty();
    }

    /** 取消句柄完成前任务保持非终态，避免后台进程仍运行但页面已显示取消成功。 */
    @Test
    void cancelShouldCommitTerminalStateAfterCleanup(@TempDir Path tempDir) throws Exception {
        TestApi api = api(tempDir);
        var task = api.application().startTask(new CreateAgentTaskCommand(
                "conversation-cancel", "执行长任务", "turn-cancel"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        api.cancellations().find(task.taskId()).orElseThrow().register(
                AgentCancellationHandleType.PROCESS, "process-cancel", () -> {
                    started.countDown();
                    try {
                        release.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });

        CompletableFuture<?> cancellation = CompletableFuture.runAsync(
                () -> api.application().cancelTask(task.taskId()));
        org.assertj.core.api.Assertions.assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                api.taskService().findTask(task.taskId()).orElseThrow().state().name()).isEqualTo("RUNNING");
        release.countDown();
        cancellation.get(3, TimeUnit.SECONDS);
        org.assertj.core.api.Assertions.assertThat(
                api.taskService().findTask(task.taskId()).orElseThrow().state().name()).isEqualTo("CANCELED");
    }

    /**
     * 验证 SSE 接口接受任务游标并进入异步订阅。
     *
     * @param tempDir 临时事件目录
     * @throws Exception API 调用失败时抛出
     */
    @Test
    void streamShouldSupportLastEventId(@TempDir Path tempDir) throws Exception {
        TestApi api = api(tempDir);
        String taskId = createTask(api);

        api.mockMvc().perform(get("/api/ai/agent/tasks/{taskId}/events/stream", taskId)
                        .header("Last-Event-ID", taskId + ":0")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());
        api.subscriptions().closeTask(taskId);
        api.mockMvc().perform(get("/api/ai/agent/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CREATED"));
    }

    /**
     * 创建一个任务并返回任务标识。
     *
     * @param api 测试 API
     * @return 任务标识
     * @throws Exception API 调用失败时抛出
     */
    private String createTask(TestApi api) throws Exception {
        MvcResult result = api.mockMvc().perform(post("/api/ai/agent/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conversation-api\",\"goal\":\"测试任务\",\"idempotencyKey\":\""
                                + java.util.UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return api.mapper().readTree(result.getResponse().getContentAsString()).get("taskId").asText();
    }

    /**
     * 创建测试 API 依赖。
     *
     * @param root 临时事件目录
     * @return 测试 API
     */
    private TestApi api(Path root) {
        return api(root, true);
    }

    /** 创建可控制 Agent Runtime 开关的测试 API。 */
    private TestApi api(Path root, boolean runtimeEnabled) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiAgentDataRoot(root.toString());
        properties.getAiFeatures().setAgentRuntimeEnabled(runtimeEnabled);
        FileAgentEventStore eventStore = new FileAgentEventStore(properties, new AgentEventFileCodec(mapper));
        AgentEventSubscriptionService subscriptions = new AgentEventSubscriptionService(
                eventStore,
                Runnable::run,
                new DevBridgeExecutorProperties());
        AgentEventSequencer sequencer = new AgentEventSequencer(eventStore, subscriptions);
        InMemoryAgentTaskStore taskStore = new InMemoryAgentTaskStore();
        AgentTaskService taskService = new AgentTaskService(taskStore, new AgentTaskStateMachine());
        AgentCheckpointService checkpoints = new AgentCheckpointService(
                taskStore,
                new FileAgentCheckpointStore(properties, new AgentCheckpointFileCodec(mapper)));
        AgentTaskCancellationCoordinator cancellations = new AgentTaskCancellationCoordinator();
        AgentTaskApplicationService application = new AgentTaskApplicationService(
                taskService, sequencer, cancellations, eventStore, checkpoints);
        RecordingConversationService conversationService = new RecordingConversationService();
        AgentTaskController controller = new AgentTaskController(
                application, taskService, eventStore, subscriptions,
                checkpoints, null, properties, conversationService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        return new TestApi(
                mockMvc, mapper, subscriptions, cancellations, application, taskService, checkpoints,
                conversationService);
    }

    /**
     * 测试 API 依赖集合。
     *
     * @param mockMvc MVC 测试客户端
     * @param mapper JSON 序列化器
     * @param subscriptions SSE 订阅服务
     * @param cancellations 任务取消协调器
     */
    private record TestApi(
            MockMvc mockMvc,
            ObjectMapper mapper,
            AgentEventSubscriptionService subscriptions,
            AgentTaskCancellationCoordinator cancellations,
            AgentTaskApplicationService application,
            AgentTaskService taskService,
            AgentCheckpointService checkpoints,
            RecordingConversationService conversationService) {
    }

    /** 记录暂停续跑调用的轻量 Fake，不访问 Provider。 */
    private static final class RecordingConversationService extends AiConversationService {

        private final AtomicInteger continuations = new AtomicInteger();

        /** 创建不依赖外部服务的记录实例。 */
        private RecordingConversationService() {
            super(null, null, null, null, null, null, null, null);
        }

        /** 记录自动续跑并返回可供 MVC 启动异步响应的 SSE。 */
        @Override
        public org.springframework.web.servlet.mvc.method.annotation.SseEmitter continueAfterPause(
                AgentTaskRecovery recovery) {
            continuations.incrementAndGet();
            return new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
        }

        /** 记录补充输入后的自动续跑，不访问真实 Provider。 */
        @Override
        public org.springframework.web.servlet.mvc.method.annotation.SseEmitter continueAfterInput(
                AgentTaskApplicationService.AgentInputAcceptance acceptance) {
            continuations.incrementAndGet();
            return new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
        }
    }
}
