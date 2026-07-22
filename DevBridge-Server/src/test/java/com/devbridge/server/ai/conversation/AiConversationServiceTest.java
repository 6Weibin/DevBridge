package com.devbridge.server.ai.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.agent.event.AgentEvent;
import com.devbridge.server.ai.agent.event.AgentEventRequest;
import com.devbridge.server.ai.agent.event.AgentEventSequencer;
import com.devbridge.server.ai.agent.event.AgentEventType;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.runtime.AgentTaskApplicationService;
import com.devbridge.server.ai.agent.runtime.AgentTaskCancellationCoordinator;
import com.devbridge.server.ai.agent.runtime.AgentTaskService;
import com.devbridge.server.ai.agent.runtime.AgentTaskStateMachine;
import com.devbridge.server.ai.agent.store.InMemoryAgentTaskStore;
import com.devbridge.server.ai.agent.checkpoint.AgentCheckpoint;
import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointService;
import com.devbridge.server.ai.agent.checkpoint.AgentRecoveryState;
import com.devbridge.server.ai.agent.checkpoint.AgentTaskRecovery;
import com.devbridge.server.ai.agent.checkpoint.AgentToolCallCheckpoint;
import com.devbridge.server.ai.agent.checkpoint.AgentToolCallCheckpointStatus;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmation;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationBinding;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationRiskLevel;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationStatus;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.config.AiConfigCrypto;
import com.devbridge.server.ai.config.AiConfigService;
import com.devbridge.server.ai.config.AiProviderType;
import com.devbridge.server.ai.config.AiRuntimeConfig;
import com.devbridge.server.ai.mcp.execution.AiMcpToolEventPublisher;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import com.devbridge.server.ai.provider.AiModelListResponse;
import com.devbridge.server.ai.provider.AiProviderGateway;
import com.devbridge.server.ai.provider.AiProviderRequest;
import com.devbridge.server.ai.provider.AiProviderResponse;
import com.devbridge.server.ai.provider.AiProviderStreamHandle;
import com.devbridge.server.ai.provider.AiProviderStreamListener;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.ai.tool.AiToolScope;
import com.devbridge.server.ai.tool.AiToolRegistry;
import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 普通对话服务测试，覆盖工具调用后的兜底回复。
 *
 * <p>by AI.Coding</p>
 */
class AiConversationServiceTest {

    /**
     * 验证流式对话超时足够覆盖工具调用和长回复，避免 Spring 在仍有内容输出时按 70 秒总时长截断 SSE。
     */
    @Test
    void chatStreamTimeoutShouldAllowLongToolResponses() {
        assertThat(AiConversationService.CHAT_STREAM_TIMEOUT_MILLIS).isGreaterThanOrEqualTo(300_000L);
    }

    /**
     * 验证多轮对话会把最近历史放入 Prompt，conversationId 只做绑定 ID 时不能丢失语义上下文。
     */
    @Test
    void userPromptShouldIncludeRecentConversationHistory() {
        AiConversationService service = new AiConversationService(null, null, null, null, null, null, null, null);
        AiChatRequest request = new AiChatRequest(
                "继续下一步",
                new AiDeviceContext("android", "serial-1", "HMA-AL00", "12", "connected"),
                "conversation-1",
                List.of(
                        new AiChatHistoryMessage("user", "先查询已安装应用"),
                        new AiChatHistoryMessage("assistant", "已整理应用列表"),
                        new AiChatHistoryMessage("system", "不应该进入上下文")));

        String prompt = service.userPrompt(request);

        assertThat(prompt).contains("最近上下文");
        assertThat(prompt).contains("用户：先查询已安装应用");
        assertThat(prompt).contains("AI：已整理应用列表");
        assertThat(prompt).contains("当前用户问题：继续下一步");
        assertThat(prompt).doesNotContain("不应该进入上下文");
    }

    /**
     * 验证应用包列表兜底时只展示原始输出，避免后端硬编码应用识别逻辑。
     */
    @Test
    void toolFallbackAnswerShouldKeepPackageListAsRawOutput() {
        AiConversationService service = new AiConversationService(null, null, null, null, null, null, null, null);
        AdbMcpToolResult result = AdbMcpToolResult.success(
                "package:com.android.settings\npackage:/data/app/app.apk=com.tencent.mm\n",
                "",
                0,
                20,
                false,
                AdbRiskLevel.LOW);

        String answer = service.toolFallbackAnswer(List.of(result));

        assertThat(answer).contains("模型未返回最终分析");
        assertThat(answer).contains("```text");
        assertThat(answer).contains("com.android.settings");
        assertThat(answer).contains("com.tencent.mm");
        assertThat(answer).doesNotContain("应用判断");
        assertThat(answer).doesNotContain("Android 系统组件");
        assertThat(answer).doesNotContain("腾讯系应用");
    }

    /**
     * 验证普通工具输出仍能回落为代码块，保留用户排查所需原始信息。
     */
    @Test
    void toolFallbackAnswerShouldKeepGenericOutput() {
        AiConversationService service = new AiConversationService(null, null, null, null, null, null, null, null);
        AdbMcpToolResult result = AdbMcpToolResult.success(
                "device-1\tdevice\n",
                "",
                0,
                10,
                false,
                AdbRiskLevel.LOW);

        String answer = service.toolFallbackAnswer(List.of(result));

        assertThat(answer).contains("工具已执行");
        assertThat(answer).contains("```text");
        assertThat(answer).contains("device-1");
    }

    /**
     * 验证模型达到输出上限时会生成明确提示，避免用户把 max_tokens 截断误判为正常完整回复。
     */
    @Test
    void finishReasonNoticeShouldExplainLengthTruncation() {
        AiConversationService service = new AiConversationService(null, null, null, null, null, null, null, null);

        String notice = service.finishReasonNotice("LENGTH");

        assertThat(notice).contains("最大输出长度限制");
        assertThat(service.finishReasonNotice("STOP")).isEmpty();
    }

    /**
     * 验证普通设备问题不会暴露本机 Shell 工具，降低模型误操作宿主机风险。
     */
    @Test
    void resolveToolScopeShouldKeepAdbScopeForDeviceQuestion() {
        AiConversationService service = new AiConversationService(null, null, null, null, null, null, null, null);
        AiChatRequest request = new AiChatRequest(
                "查询当前连接设备安装的应用",
                null,
                "conversation-1",
                List.of());

        assertThat(service.resolveToolScope(request)).isEqualTo(AiToolScope.ADB_DEVICE_MANAGEMENT);
    }

    /**
     * 验证用户明确要求本机命令时才开启 Local Shell MCP 工具范围。
     */
    @Test
    void resolveToolScopeShouldUseLocalDevelopmentForLocalCommandQuestion() {
        AiConversationService service = new AiConversationService(null, null, null, null, null, null, null, null);
        AiChatRequest request = new AiChatRequest(
                "在本机终端执行 git status 并分析结果",
                null,
                "conversation-1",
                List.of());

        assertThat(service.resolveToolScope(request)).isEqualTo(AiToolScope.LOCAL_DEVELOPMENT);
    }

    /**
     * 验证现有同步 Chat API 创建 Agent Task，并把任务标识带入 Provider 上下文后完成任务。
     */
    @Test
    void chatShouldRunInsidePersistentAgentTask() {
        ConversationRuntime runtime = runtime(false);
        AiChatRequest request = new AiChatRequest(
                "查询设备状态", new AiDeviceContext(
                        "android", "serial-secret", "Pixel", "15", "connected"), "conversation-agent",
                List.of(new AiChatHistoryMessage("user", "上一轮先检查设备连接")),
                "", "", new AiChatRequest.SummaryContext("较早历史摘要：设备曾经离线", 2, 20),
                new AiChatRequest.RagContext("本地知识库证据", List.of("doc-1")));

        AiChatResponse response = runtime.service().chat(request);

        String taskId = String.valueOf(runtime.provider().lastRequest.toolContext().get("taskId"));
        assertThat(response.answer()).isEqualTo("完成");
        assertThat(taskId).isNotBlank();
        assertThat(runtime.provider().lastRequest.egressContext().taskId()).isEqualTo(taskId);
        assertThat(runtime.provider().lastRequest.egressContext().items())
                .extracting(item -> item.dataType().name())
                .contains("USER_MESSAGE", "DEVICE_CONTEXT", "FILE_CONTENT");
        assertThat(runtime.provider().lastRequest.egressContext().items())
                .filteredOn(item -> item.dataType().name().equals("FILE_CONTENT"))
                .allMatch(item -> item.classification().name().equals("CONFIRMATION_REQUIRED"));
        assertThat(runtime.provider().lastRequest.userPrompt()).contains("上一轮先检查设备连接");
        assertThat(runtime.provider().lastRequest.userPrompt()).contains("设备曾经离线");
        assertThat(runtime.events().request(AgentEventType.MODEL_CALL_STARTED).payload())
                .containsEntry("historySource", "REQUEST_FALLBACK")
                .containsEntry("historyMessages", 1)
                .containsEntry("summaryVersion", 2L)
                .containsEntry("summaryIncluded", true)
                .containsKey("historyTokenBudget");
        assertThat(runtime.taskService().findTask(taskId).orElseThrow().state())
                .isEqualTo(AgentTaskState.COMPLETED);
        assertThat(runtime.events().types).containsSubsequence(
                com.devbridge.server.ai.agent.event.AgentEventType.MODEL_CALL_STARTED,
                com.devbridge.server.ai.agent.event.AgentEventType.OUTPUT_STARTED,
                com.devbridge.server.ai.agent.event.AgentEventType.MODEL_CALL_COMPLETED,
                com.devbridge.server.ai.agent.event.AgentEventType.OUTPUT_COMPLETED);
    }

    /** Runtime 关闭后普通聊天仍调用 Provider，但不能创建 Task 或暴露工具。 */
    @Test
    void chatShouldUseToolFreeLegacyPathWhenRuntimeDisabled() throws Exception {
        ConversationRuntime runtime = runtime(false);
        java.lang.reflect.Field enabled = AiConversationService.class
                .getDeclaredField("agentRuntimeEnabled");
        enabled.setAccessible(true);
        enabled.set(runtime.service(), false);

        AiChatResponse response = runtime.service().chat(new AiChatRequest(
                "普通问答", null, "conversation-disabled", List.of()));

        assertThat(response.answer()).isEqualTo("完成");
        assertThat(runtime.taskService().listTasks(0, 20).total()).isZero();
        assertThat(runtime.provider().lastRequest.toolCallbacks()).isEmpty();
        assertThat(runtime.provider().lastRequest.toolContext()).containsEntry("taskId", "");
    }

    /**
     * 验证 Provider 异常会落为任务失败，不留下永久 RUNNING 的孤立任务。
     */
    @Test
    void chatShouldFailAgentTaskWhenProviderFails() {
        ConversationRuntime runtime = runtime(true);
        AiChatRequest request = new AiChatRequest(
                "查询设备状态", null, "conversation-failed", List.of());

        assertThatThrownBy(() -> runtime.service().chat(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider failed");

        String taskId = String.valueOf(runtime.provider().lastRequest.toolContext().get("taskId"));
        assertThat(runtime.taskService().findTask(taskId).orElseThrow().state())
                .isEqualTo(AgentTaskState.FAILED);
        assertThat(runtime.events().types)
                .contains(com.devbridge.server.ai.agent.event.AgentEventType.MODEL_CALL_FAILED);
    }

    /**
     * 验证模型失败事件自身落盘失败时仍保留 Provider 原始异常，并完成任务失败收尾。
     */
    @Test
    void chatShouldPreserveProviderFailureWhenFailureEventCannotPersist() {
        NoopEventSequencer events = new NoopEventSequencer();
        events.failOn(AgentEventType.MODEL_CALL_FAILED);
        ConversationRuntime runtime = runtime(true, false, events);

        assertThatThrownBy(() -> runtime.service().chat(new AiChatRequest(
                "查询设备状态", null, "conversation-event-failed", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider failed");

        String taskId = String.valueOf(runtime.provider().lastRequest.toolContext().get("taskId"));
        assertThat(runtime.taskService().findTask(taskId).orElseThrow().state())
                .isEqualTo(AgentTaskState.FAILED);
    }

    /**
     * 验证 Provider 已成功返回后，控制面持久化失败不会追加虚假的模型调用失败事件。
     */
    @Test
    void chatShouldNotReportProviderFailureWhenCompletionEventCannotPersist() {
        NoopEventSequencer events = new NoopEventSequencer();
        events.failOn(AgentEventType.OUTPUT_COMPLETED);
        ConversationRuntime runtime = runtime(false, false, events);

        assertThatThrownBy(() -> runtime.service().chat(new AiChatRequest(
                "查询设备状态", null, "conversation-completion-failed", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event failed");

        String taskId = String.valueOf(runtime.provider().lastRequest.toolContext().get("taskId"));
        assertThat(runtime.taskService().findTask(taskId).orElseThrow().state())
                .isEqualTo(AgentTaskState.FAILED);
        assertThat(runtime.events().types).doesNotContain(AgentEventType.MODEL_CALL_FAILED);
    }

    /**
     * 验证流式 Provider 正常完成时任务进入完成态。
     */
    @Test
    void streamChatShouldCompleteAgentTask() {
        ConversationRuntime runtime = runtime(false);

        runtime.service().streamChat(new AiChatRequest(
                "流式查询设备状态", null, "conversation-stream", List.of()));

        String taskId = String.valueOf(runtime.provider().lastRequest.toolContext().get("taskId"));
        assertThat(runtime.taskService().findTask(taskId).orElseThrow().state())
                .isEqualTo(AgentTaskState.COMPLETED);
    }

    /**
     * 验证工具调用前说明只进入执行过程，不会混入断线恢复使用的持久化最终回复。
     */
    @Test
    void streamChatShouldPersistOnlyContentAfterTool() {
        ConversationRuntime runtime = runtime(false, false, true, new NoopEventSequencer());

        runtime.service().streamChat(new AiChatRequest(
                "查询设备后给出结论", null, "conversation-tool-result", List.of()));

        String taskId = String.valueOf(runtime.provider().lastRequest.toolContext().get("taskId"));
        AgentTask completed = runtime.taskService().findTask(taskId).orElseThrow();
        assertThat(runtime.checkpoints().restore(completed.protectedResult(), String.class))
                .isEqualTo("工具后的最终回复");
    }

    /**
     * 验证流式 Provider 错误回调会结束任务，避免重启后恢复一个实际已中断的 RUNNING 任务。
     */
    @Test
    void streamChatShouldFailAgentTaskOnProviderError() {
        ConversationRuntime runtime = runtime(true);

        runtime.service().streamChat(new AiChatRequest(
                "流式查询设备状态", null, "conversation-stream-failed", List.of()));

        String taskId = String.valueOf(runtime.provider().lastRequest.toolContext().get("taskId"));
        assertThat(runtime.taskService().findTask(taskId).orElseThrow().state())
                .isEqualTo(AgentTaskState.FAILED);
    }

    /**
     * 验证敏感工具进入等待确认时会闭合当前模型和输出事件，并暂停同一个任务。
     */
    @Test
    void streamChatShouldCloseEventsWhenWaitingForConfirmation() {
        ConversationRuntime runtime = runtime(false, true, new NoopEventSequencer());

        runtime.service().streamChat(new AiChatRequest(
                "执行敏感设备操作", null, "conversation-confirmation", List.of()));

        String taskId = String.valueOf(runtime.provider().lastRequest.toolContext().get("taskId"));
        assertThat(runtime.taskService().findTask(taskId).orElseThrow().state())
                .isEqualTo(AgentTaskState.WAITING_CONFIRMATION);
        assertThat(runtime.events().types).containsSubsequence(
                AgentEventType.MODEL_CALL_STARTED,
                AgentEventType.OUTPUT_STARTED,
                AgentEventType.MODEL_CALL_COMPLETED,
                AgentEventType.OUTPUT_COMPLETED);
        assertThat(runtime.events().request(AgentEventType.MODEL_CALL_COMPLETED).payload())
                .containsEntry("finishReason", "WAITING_CONFIRMATION");
        assertThat(runtime.events().request(AgentEventType.OUTPUT_COMPLETED).payload())
                .containsEntry("finishReason", "WAITING_CONFIRMATION");
        assertThat(runtime.provider().handleCanceled).isTrue();
        String modelCallId = String.valueOf(runtime.provider().lastRequest.toolContext().get("modelCallId"));
        var replacement = runtime.application().registerCancellation(
                taskId, com.devbridge.server.ai.agent.runtime.AgentCancellationHandleType.MODEL,
                modelCallId, () -> { });
        assertThat(replacement.active()).isTrue();
        replacement.close();
    }

    /**
     * 验证确认批准后由后端恢复原请求并先执行工具，再进入模型总结。
     */
    @Test
    void continueAfterConfirmationShouldRestoreOriginalConversationContext() {
        ConversationRuntime runtime = runtime(false);
        AgentTask task = runtime.taskService().createTask(
                new com.devbridge.server.ai.agent.runtime.CreateAgentTaskCommand(
                        "conversation-resume", "卸载测试应用后继续分析"));
        task = runtime.taskService().transitionTask(task.taskId(), AgentTaskState.PLANNING, "开始规划");
        task = runtime.taskService().transitionTask(task.taskId(), AgentTaskState.RUNNING, "继续执行");
        ToolContract.CallRequest original = originalToolRequest(task.taskId());
        AgentToolCallCheckpoint toolCall = new AgentToolCallCheckpoint(
                "tool-call-uninstall", "step-uninstall", AgentToolCallCheckpointStatus.REQUESTED,
                null, "sha256:test", null, false, runtime.checkpoints().protect(original));
        AgentRecoveryState state = new AgentRecoveryState(
                "step-uninstall", List.of(), Map.of(), null, null,
                new AgentRecoveryState.AgentContinuationContext(
                        "卸载测试应用后继续分析", "conversation-resume",
                        new AgentRecoveryState.AgentDeviceSnapshot(
                                "android", "serial-1", "Pixel", "14", "connected"),
                        List.of(new AgentRecoveryState.AgentHistorySnapshot("user", "先检查应用状态")),
                        new AgentRecoveryState.AgentSummarySnapshot(
                                "较早任务摘要：用户要求保留卸载前证据", 3, 40)));
        state = new AgentRecoveryState(
                state.currentStepId(), state.completedStepIds(), Map.of(toolCall.toolCallId(), toolCall),
                state.pendingConfirmationId(), state.pendingInputKey(), state.continuationContext());
        AgentCheckpoint checkpoint = new AgentCheckpoint(
                "checkpoint-resume", task.taskId(), task.version(), 3,
                task.state(), state, Instant.now());
        AgentConfirmationBinding binding = new AgentConfirmationBinding(
                "step-uninstall", "tool-call-uninstall", "device.app.uninstall", "sha256:test",
                AgentConfirmationRiskLevel.MEDIUM, "卸载测试应用");
        AgentConfirmation confirmation = new AgentConfirmation(
                "confirmation-resume", task.taskId(), binding, AgentConfirmationStatus.ACCEPTED,
                Instant.now(), Instant.now().plusSeconds(120), Instant.now(), "用户已确认");

        runtime.service().continueAfterConfirmation(
                new AgentTaskRecovery(task, checkpoint), confirmation);

        assertThat(runtime.provider().lastRequest.userPrompt())
                .contains("卸载测试应用后继续分析")
                .contains("Runtime 已确定性执行原工具")
                .contains("用户要求保留卸载前证据")
                .contains("卸载完成");
        assertThat(runtime.provider().lastRequest.toolContext().get("confirmationToken"))
                .isEqualTo("");
        assertThat(runtime.provider().lastRequest.toolContext().get("deviceSerial"))
                .isEqualTo("serial-1");
        assertThat(runtime.taskService().findTask(task.taskId()).orElseThrow().state())
                .isEqualTo(AgentTaskState.COMPLETED);
        AgentTask completed = runtime.taskService().findTask(task.taskId()).orElseThrow();
        assertThat(runtime.checkpoints().restore(completed.protectedResult(), String.class))
                .isEqualTo("完成");
    }

    /**
     * 验证数据外发确认续跑会恢复原工具调用标识，避免再次执行后产生新摘要。
     */
    @Test
    void continueAfterDataEgressConfirmationShouldExposeCompletedToolReplay() {
        ConversationRuntime runtime = runtime(false);
        AgentTask task = runtime.taskService().createTask(
                new com.devbridge.server.ai.agent.runtime.CreateAgentTaskCommand(
                        "conversation-egress", "检查设备网络"));
        task = runtime.taskService().transitionTask(task.taskId(), AgentTaskState.PLANNING, "开始规划");
        task = runtime.taskService().transitionTask(task.taskId(), AgentTaskState.RUNNING, "确认后续跑");
        ToolContract.CallRequest original = originalToolRequest(task.taskId());
        AgentToolCallCheckpoint toolCall = new AgentToolCallCheckpoint(
                "tool-call-uninstall", "step-uninstall", AgentToolCallCheckpointStatus.SUCCEEDED,
                "tool-call-uninstall", "sha256:test", "protected-result", true,
                runtime.checkpoints().protect(original));
        AgentRecoveryState state = new AgentRecoveryState(
                "step-uninstall", List.of("step-uninstall"), Map.of(toolCall.toolCallId(), toolCall),
                "confirmation-egress", null,
                new AgentRecoveryState.AgentContinuationContext(
                        "检查设备网络", "conversation-egress", null, List.of()));
        AgentCheckpoint checkpoint = runtime.checkpoints().saveCheckpoint(task.taskId(), 10L, state);
        AgentConfirmation confirmation = new AgentConfirmation(
                "confirmation-egress", task.taskId(),
                new AgentConfirmationBinding(
                        "step-uninstall", "model-call-1", "model-data-egress", "egress-digest",
                        AgentConfirmationRiskLevel.MEDIUM, "发送工具结果"),
                AgentConfirmationStatus.ACCEPTED, Instant.now(), Instant.now().plusSeconds(120),
                Instant.now(), "用户已确认");

        runtime.service().continueAfterDataEgressConfirmation(
                new AgentTaskRecovery(task, checkpoint), confirmation, new SseEmitter(), null);

        Object replayValue = runtime.provider().lastRequest.toolContext()
                .get(AiToolRegistry.DATA_EGRESS_REPLAY_CALLS_CONTEXT_KEY);
        assertThat(replayValue).isEqualTo(Map.of(
                "device.app.uninstall", Map.of("sha256:test", "tool-call-uninstall")));
    }

    /**
     * 构造确认续跑测试使用的原始中立工具请求。
     *
     * @param taskId 任务标识
     * @return 原始工具请求
     */
    private ToolContract.CallRequest originalToolRequest(String taskId) {
        return new ToolContract.CallRequest(
                ToolContract.SCHEMA_VERSION,
                new ToolContract.CallIdentity(
                        "conversation-resume", taskId, "turn-original", "step-uninstall",
                        "tool-call-uninstall", Instant.now()),
                new ToolContract.ToolReference("device.app.uninstall", ToolContract.SCHEMA_VERSION),
                new ObjectMapper().createObjectNode().put("packageName", "com.example.test"),
                "sha256:test",
                "tool-call-uninstall",
                ToolContract.Caller.AGENT,
                new ToolContract.ExecutionContext(
                        ToolContract.Platform.ANDROID, "serial-1", "", "", List.of()));
    }

    /**
     * 创建不访问网络和文件系统的 Chat 主链路测试运行时。
     *
     * @param providerFailure Provider 是否抛出异常
     * @return 测试运行时
     */
    private ConversationRuntime runtime(boolean providerFailure) {
        return runtime(providerFailure, false, new NoopEventSequencer());
    }

    /**
     * 创建可配置 Provider 失败和确认暂停场景的 Chat 测试运行时。
     *
     * @param providerFailure Provider 是否抛出异常
     * @param requestConfirmation 是否模拟工具等待确认
     * @param events 事件记录器
     * @return 测试运行时
     */
    private ConversationRuntime runtime(
            boolean providerFailure,
            boolean requestConfirmation,
            NoopEventSequencer events) {
        return runtime(providerFailure, requestConfirmation, false, events);
    }

    /**
     * 创建可模拟普通工具结果的 Chat 测试运行时。
     *
     * @param providerFailure Provider 是否抛出异常
     * @param requestConfirmation 是否模拟工具等待确认
     * @param publishToolResult 是否模拟工具完成后继续回复
     * @param events 事件记录器
     * @return 测试运行时
     */
    private ConversationRuntime runtime(
            boolean providerFailure,
            boolean requestConfirmation,
            boolean publishToolResult,
            NoopEventSequencer events) {
        AgentTaskService taskService = new AgentTaskService(
                new InMemoryAgentTaskStore(), new AgentTaskStateMachine());
        AgentTaskApplicationService application = new AgentTaskApplicationService(
                taskService, events, new AgentTaskCancellationCoordinator());
        FakeCheckpointService checkpoints = new FakeCheckpointService(taskService);
        AiMcpToolEventPublisher toolEvents = new AiMcpToolEventPublisher();
        FakeProviderGateway provider = new FakeProviderGateway(
                providerFailure, requestConfirmation, publishToolResult, toolEvents);
        AiConversationService service = new AiConversationService(
                new FakeConfigService(), provider, new EmptyToolRegistry(),
                toolEvents, application, events, checkpoints, contextBuilder());
        return new ConversationRuntime(service, application, taskService, provider, events, checkpoints);
    }

    /**
     * 创建不访问文件系统的 Working Memory 构造器，主链路测试使用请求历史兼容分支。
     *
     * @return Context Builder
     */
    private AiConversationContextBuilder contextBuilder() {
        return new AiConversationContextBuilder(
                null, new SensitiveDataMasker(), new DevBridgeProperties());
    }

    /**
     * Chat 主链路测试依赖集合。
     *
     * <p>by AI.Coding</p>
     *
     * @param service 对话服务
     * @param application Agent 任务应用服务
     * @param taskService 任务查询服务
     * @param provider Provider Fake
     * @param events 事件记录器
     * @param checkpoints Checkpoint Fake
     */
    private record ConversationRuntime(
            AiConversationService service,
            AgentTaskApplicationService application,
            AgentTaskService taskService,
            FakeProviderGateway provider,
            NoopEventSequencer events,
            FakeCheckpointService checkpoints) {
    }

    /**
     * 返回固定运行配置的显式配置服务 Fake。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeConfigService extends AiConfigService {

        /**
         * 创建不读取真实配置文件的服务。
         */
        FakeConfigService() {
            super(new DevBridgeProperties(), new AiConfigCrypto(), new ObjectMapper());
        }

        /**
         * 返回固定测试 Provider 配置。
         *
         * @return 运行配置
         */
        @Override
        public AiRuntimeConfig requireConfigured() {
            return new AiRuntimeConfig(
                    AiProviderType.OPENAI, "https://example.invalid", "test-key", "test-model");
        }
    }

    /**
     * 不暴露工具的显式注册表 Fake。
     *
     * <p>by AI.Coding</p>
     */
    private static class EmptyToolRegistry extends AiToolRegistry {

        /**
         * 创建空工具注册表。
         */
        EmptyToolRegistry() {
            super(null, null, null, null, null);
        }

        /**
         * 返回空工具列表。
         *
         * @param scope 工具范围
         * @param devicePlatform 设备平台
         * @return 空列表
         */
        @Override
        public List<ToolCallback> toolCallbacks(
                AiToolScope scope,
                com.devbridge.server.ai.tool.gateway.ToolContract.Platform devicePlatform) {
            return List.of();
        }

        /**
         * 返回固定真实执行结果，验证对话层不会要求模型重新选择工具。
         *
         * @param request 已恢复原工具请求
         * @return 固定执行结果
         */
        @Override
        public ConfirmedToolExecution executeConfirmed(ToolContract.CallRequest request) {
            AdbMcpToolResult result = AdbMcpToolResult.success(
                    "卸载完成", "", 0, 10, false, AdbRiskLevel.MEDIUM);
            return new ConfirmedToolExecution(result, "<tool-result>卸载完成</tool-result>");
        }
    }

    /**
     * 记录请求并按测试场景返回或抛错的 Provider Fake。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeProviderGateway implements AiProviderGateway {

        private final boolean fail;
        private final boolean requestConfirmation;
        private final boolean publishToolResult;
        private final AiMcpToolEventPublisher toolEvents;
        private AiProviderRequest lastRequest;
        private boolean handleCanceled;

        /**
         * 创建 Provider Fake。
         *
         * @param fail 是否失败
         * @param requestConfirmation 是否发布等待确认工具结果
         * @param publishToolResult 是否发布普通工具结果
         * @param toolEvents 工具事件发布器
         */
        FakeProviderGateway(
                boolean fail,
                boolean requestConfirmation,
                boolean publishToolResult,
                AiMcpToolEventPublisher toolEvents) {
            this.fail = fail;
            this.requestConfirmation = requestConfirmation;
            this.publishToolResult = publishToolResult;
            this.toolEvents = toolEvents;
        }

        /**
         * 当前测试不使用模型列表。
         *
         * @param config 配置
         * @return 空模型列表
         */
        @Override
        public AiModelListResponse listModels(AiRuntimeConfig config) {
            return new AiModelListResponse("openai", List.of());
        }

        /**
         * 记录请求并返回固定回复或模拟 Provider 异常。
         *
         * @param request Provider 请求
         * @return 固定回复
         */
        @Override
        public AiProviderResponse chat(AiProviderRequest request) {
            lastRequest = request;
            if (fail) {
                throw new IllegalStateException("provider failed");
            }
            return new AiProviderResponse("完成", "openai", "test-model", 10);
        }

        /**
         * 记录流式请求，并按场景模拟成功、失败或等待确认。
         *
         * @param request Provider 请求
         * @param listener 监听器
         * @return 空句柄
         */
        @Override
        public AiProviderStreamHandle stream(
                AiProviderRequest request, AiProviderStreamListener listener) {
            lastRequest = request;
            if (fail) {
                listener.onError(new BusinessException(
                        "TEST_PROVIDER_FAILED", "Provider 流式失败",
                        HttpStatus.BAD_GATEWAY, "test"));
                return () -> {
                };
            }
            if (requestConfirmation) {
                String subscriptionId = request.toolContext().get("taskId") + ":"
                        + request.toolContext().get("turnId");
                toolEvents.publish(subscriptionId, AdbMcpToolResult.confirmationRequired(
                        "agent-confirmation:test", "需要用户确认", AdbRiskLevel.MEDIUM));
                return () -> handleCanceled = true;
            }
            if (publishToolResult) {
                listener.onContent("工具调用前说明");
                String subscriptionId = request.toolContext().get("taskId") + ":"
                        + request.toolContext().get("turnId");
                toolEvents.publish(subscriptionId, AdbMcpToolResult.success(
                        "设备状态正常", "", 0, 10, false, AdbRiskLevel.LOW));
                listener.onContent("工具后的最终回复");
                listener.onComplete("STOP");
                return () -> { };
            }
            listener.onContent("完成");
            listener.onComplete("STOP");
            return () -> {
            };
        }
    }

    /**
     * 忽略事件持久化的测试序列器，任务状态仍使用真实服务和 Store。
     *
     * <p>by AI.Coding</p>
     */
    private static class NoopEventSequencer extends AgentEventSequencer {

        private final List<com.devbridge.server.ai.agent.event.AgentEventType> types = new java.util.ArrayList<>();
        private final List<AgentEventRequest> requests = new java.util.ArrayList<>();
        private AgentEventType failureType;

        /**
         * 创建无外部依赖的序列器。
         */
        NoopEventSequencer() {
            super(null, null);
        }

        /**
         * 忽略事件持久化。
         *
         * @param taskId 任务标识
         * @param request 事件请求
         * @return 无事件
         */
        @Override
        public AgentEvent publish(String taskId, AgentEventRequest request) {
            types.add(request.eventType());
            requests.add(request);
            if (request.eventType() == failureType) {
                throw new IllegalStateException("event failed: " + failureType);
            }
            return null;
        }

        /**
         * 返回当前测试已记录事件数量作为稳定序号。
         *
         * @param taskId 任务标识
         * @return 当前序号
         */
        @Override
        public long lastSequence(String taskId) {
            return types.size();
        }

        /**
         * 配置指定事件类型模拟持久化失败。
         *
         * @param eventType 失败事件类型
         */
        void failOn(AgentEventType eventType) {
            this.failureType = eventType;
        }

        /**
         * 获取指定类型的最后一个事件请求。
         *
         * @param eventType 事件类型
         * @return 事件请求
         */
        AgentEventRequest request(AgentEventType eventType) {
            return requests.stream()
                    .filter(item -> item.eventType() == eventType)
                    .reduce((first, second) -> second)
                    .orElseThrow();
        }
    }

    /**
     * 使用内存保存恢复状态的显式 Checkpoint Fake。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeCheckpointService extends AgentCheckpointService {

        private final AgentTaskService taskService;
        private final Map<String, AgentCheckpoint> checkpoints = new java.util.HashMap<>();

        /**
         * 创建内存 Checkpoint Fake。
         *
         * @param taskService 任务服务
         */
        FakeCheckpointService(AgentTaskService taskService) {
            super(null, null);
            this.taskService = taskService;
        }

        /**
         * 保存与任务当前版本一致的恢复状态。
         *
         * @param taskId 任务标识
         * @param eventSequence 事件序号
         * @param recoveryState 恢复状态
         * @return Checkpoint
         */
        @Override
        public AgentCheckpoint saveCheckpoint(
                String taskId, long eventSequence, AgentRecoveryState recoveryState) {
            AgentTask task = taskService.findTask(taskId).orElseThrow();
            AgentCheckpoint checkpoint = new AgentCheckpoint(
                    "checkpoint-test-" + checkpoints.size(), taskId, task.version(), eventSequence,
                    task.state(), recoveryState, Instant.now());
            checkpoints.put(taskId, checkpoint);
            return checkpoint;
        }

        /**
         * 读取当前任务的内存恢复点。
         *
         * @param taskId 任务标识
         * @return 恢复上下文
         */
        @Override
        public Optional<AgentTaskRecovery> loadRecovery(String taskId) {
            AgentCheckpoint checkpoint = checkpoints.get(taskId);
            return checkpoint == null ? Optional.empty() : Optional.of(new AgentTaskRecovery(
                    taskService.findTask(taskId).orElseThrow(), checkpoint));
        }
    }
}
