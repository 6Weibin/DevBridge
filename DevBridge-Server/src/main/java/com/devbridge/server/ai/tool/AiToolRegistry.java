package com.devbridge.server.ai.tool;

import com.devbridge.server.ai.agent.confirmation.AgentConfirmationStatus;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationStore;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmation;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationTokenService;
import com.devbridge.server.ai.mcp.execution.AiMcpToolEventPublisher;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import com.devbridge.server.ai.mcp.model.AdbToolStatus;
import com.devbridge.server.ai.security.untrusted.AiUntrustedContent.Envelope;
import com.devbridge.server.ai.security.untrusted.AiUntrustedContent.SourceType;
import com.devbridge.server.ai.security.untrusted.AiUntrustedContentService;
import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallStatus;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.Definition;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskLevel;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.devbridge.server.ai.tool.gateway.ToolGateway;
import com.devbridge.server.ai.tool.gateway.ToolCapabilityRouter;
import com.devbridge.server.ai.tool.gateway.ToolCapabilityRouter.Domain;
import com.devbridge.server.ai.tool.gateway.ToolCapabilityRouter.ExecutionMode;
import com.devbridge.server.ai.tool.gateway.ToolCapabilityRouter.ModelCapability;
import com.devbridge.server.ai.tool.gateway.ToolCapabilityRouter.RouteRequest;
import com.devbridge.server.ai.tool.gateway.ToolCapabilityRouter.RouteTarget;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.EnumSet;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AI 工具注册表，将统一 Tool Gateway 映射为 Spring AI ToolCallback。
 *
 * <p>模型调用不再分别进入 ADB 和 Local Shell 旧服务，前端工具卡片格式仅作为兼容输出保留。</p>
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AiToolRegistry {

    private static final String AGENT_CONFIRMATION_PREFIX = "agent-confirmation:";
    /** 数据外发确认续跑中的工具结果重放上下文键。 */
    public static final String DATA_EGRESS_REPLAY_CALLS_CONTEXT_KEY = "dataEgressReplayToolCalls";

    private final ToolGateway toolGateway;
    private final AiMcpToolEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final AiUntrustedContentService untrustedContentService;
    private final AgentConfirmationStore confirmationStore;
    private final AgentConfirmationTokenService confirmationTokenService;
    private final ToolCapabilityRouter capabilityRouter;

    /**
     * 注入统一工具入口和现有聊天兼容依赖。
     *
     * @param toolGateway 统一工具入口
     * @param eventPublisher 现有 Chat SSE 工具事件发布器
     * @param objectMapper JSON 工具
     * @param untrustedContentService 不可信工具输出隔离服务
     * @param confirmationStore Agent 确认 Store
     */
    @Autowired
    public AiToolRegistry(
            ToolGateway toolGateway,
            AiMcpToolEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            AiUntrustedContentService untrustedContentService,
            AgentConfirmationStore confirmationStore,
            AgentConfirmationTokenService confirmationTokenService,
            ToolCapabilityRouter capabilityRouter) {
        this.toolGateway = toolGateway;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.untrustedContentService = untrustedContentService;
        this.confirmationStore = confirmationStore;
        this.confirmationTokenService = confirmationTokenService;
        this.capabilityRouter = capabilityRouter;
    }

    /** 创建兼容测试的工具注册表。 */
    public AiToolRegistry(
            ToolGateway toolGateway,
            AiMcpToolEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            AiUntrustedContentService untrustedContentService,
            AgentConfirmationStore confirmationStore) {
        this(toolGateway, eventPublisher, objectMapper, untrustedContentService, confirmationStore, null, null);
    }

    /**
     * 根据当前业务范围和设备平台返回最小可用工具集合。
     *
     * @param scope 工具范围
     * @param devicePlatform 当前设备平台
     * @return Spring AI 工具回调
     */
    public List<ToolCallback> toolCallbacks(AiToolScope scope, Platform devicePlatform) {
        return directToolCallbacks(scope, devicePlatform, null);
    }

    /**
     * 使用结构化能力 Router 生成当前模型和平台的最小工具集合。
     *
     * @param scope 当前业务范围
     * @param devicePlatform 设备平台
     * @param model 当前模型能力
     * @return 最小 Spring AI 工具回调集合
     */
    public List<ToolCallback> toolCallbacks(
            AiToolScope scope,
            Platform devicePlatform,
            com.devbridge.server.ai.config.AiModelCapabilityRegistry.ModelCapability model) {
        return toolCallbacks(scope, devicePlatform, model, true);
    }

    /** 使用请求级开关控制是否向模型暴露联网工具。 */
    public List<ToolCallback> toolCallbacks(
            AiToolScope scope,
            Platform devicePlatform,
            com.devbridge.server.ai.config.AiModelCapabilityRegistry.ModelCapability model,
            boolean webSearchEnabled) {
        if (toolGateway == null) {
            // 测试 Fake 可能只覆盖旧签名，动态分派可以保持既有测试和扩展兼容。
            return toolCallbacks(scope, devicePlatform);
        }
        return directToolCallbacks(scope, devicePlatform, model, webSearchEnabled);
    }

    /** 生成候选集合并按已装配 Router 收敛。 */
    private List<ToolCallback> directToolCallbacks(
            AiToolScope scope,
            Platform devicePlatform,
            com.devbridge.server.ai.config.AiModelCapabilityRegistry.ModelCapability model) {
        return directToolCallbacks(scope, devicePlatform, model, true);
    }

    /** 生成候选工具时先应用当前请求的联网权限。 */
    private List<ToolCallback> directToolCallbacks(
            AiToolScope scope,
            Platform devicePlatform,
            com.devbridge.server.ai.config.AiModelCapabilityRegistry.ModelCapability model,
            boolean webSearchEnabled) {
        if (scope == AiToolScope.NONE || toolGateway == null) {
            return List.of();
        }
        Platform effectiveDevice = devicePlatform == null ? Platform.ANDROID : devicePlatform;
        List<Definition> candidates = toolGateway.listTools().stream()
                .filter(definition -> visible(definition, scope, effectiveDevice))
                .filter(definition -> webSearchEnabled
                        || !definition.identity().toolId().startsWith("web."))
                .toList();
        Set<String> selected = routedToolIds(candidates, scope, effectiveDevice, model);
        return candidates.stream()
                .filter(definition -> selected.isEmpty() || selected.contains(definition.identity().toolId()))
                .map(GatewayToolCallback::new)
                .map(ToolCallback.class::cast)
                .toList();
    }

    /** 通过现有 Router 对候选能力去重；测试兼容实例未装配 Router 时保留候选集合。 */
    private Set<String> routedToolIds(
            List<Definition> candidates,
            AiToolScope scope,
            Platform platform,
            com.devbridge.server.ai.config.AiModelCapabilityRegistry.ModelCapability model) {
        if (capabilityRouter == null || candidates.isEmpty()) {
            return Set.of();
        }
        List<String> capabilities = candidates.stream()
                .flatMap(value -> value.metadata().capabilities().stream())
                .distinct().toList();
        Domain domain = scope == AiToolScope.LOCAL_DEVELOPMENT
                ? Domain.CROSS_PLATFORM
                : scope == AiToolScope.GENERAL_ASSISTANT ? Domain.GENERAL : Domain.DEVICE_MANAGEMENT;
        RouteRequest request = new RouteRequest(
                domain,
                ExecutionMode.DIRECT_TOOL,
                ToolContract.RiskLevel.HIGH,
                new ModelCapability(
                        model == null ? "" : model.modelId(),
                        model == null || model.toolCalling(),
                        model == null || model.streaming(),
                        model != null && model.multimodal()),
                List.of(new RouteTarget(
                        platform, true, capabilities, EnumSet.allOf(ToolContract.AccessMode.class))),
                List.of());
        ToolCapabilityRouter.RouteDecision decision = capabilityRouter.route(request);
        if (decision.requiresClarification()) {
            return Set.of();
        }
        return decision.selections().stream()
                .flatMap(selection -> selection.tools().stream())
                .map(ToolContract.CapabilityMetadata::toolId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 执行已经由用户批准并从 Checkpoint 恢复的原始工具请求。
     *
     * @param request 绑定原工具、参数和调用标识的中立请求
     * @return 兼容展示结果和不可信证据文本
     */
    public ConfirmedToolExecution executeConfirmed(CallRequest request) {
        CallResult result = toolGateway.call(request);
        AdbMcpToolResult compatible = compatibleResult(request, result);
        return new ConfirmedToolExecution(compatible, wrappedResult(request, compatible));
    }

    /**
     * 判断工具是否属于当前设备或本机业务范围。
     *
     * @param definition 工具定义
     * @param scope 业务范围
     * @param devicePlatform 设备平台
     * @return 可见返回 true
     */
    private boolean visible(Definition definition, AiToolScope scope, Platform devicePlatform) {
        List<Platform> supported = definition.metadata().platforms();
        if (supported.contains(Platform.PLATFORM_INDEPENDENT)) {
            return platformIndependentVisible(definition.identity().toolId(), scope);
        }
        if (supported.contains(devicePlatform)) {
            return true;
        }
        return scope == AiToolScope.LOCAL_DEVELOPMENT && supported.contains(hostPlatform());
    }

    /** 平台无关高层工具仍按业务域隔离，避免设备对话获得本机专业 Agent。 */
    private boolean platformIndependentVisible(String toolId, AiToolScope scope) {
        if (scope == AiToolScope.LOCAL_DEVELOPMENT) {
            return true;
        }
        if (scope == AiToolScope.GENERAL_ASSISTANT) {
            return toolId.startsWith("web.") || toolId.startsWith("device.")
                    || toolId.startsWith("app.") || toolId.startsWith("log.")
                    || toolId.startsWith("workflow.device.") || toolId.startsWith("workflow.log.")
                    || toolId.startsWith("agent.device.") || toolId.startsWith("agent.app.")
                    || toolId.startsWith("agent.log.") || toolId.startsWith("agent.verification.")
                    || toolId.startsWith("agent.input.");
        }
        if (scope == AiToolScope.LOG_ANALYSIS) {
            return toolId.startsWith("log.") || toolId.startsWith("workflow.log.")
                    || toolId.startsWith("agent.log.");
        }
        return toolId.startsWith("device.") || toolId.startsWith("app.")
                || toolId.startsWith("log.") || toolId.startsWith("workflow.device.")
                || toolId.startsWith("workflow.log.") || toolId.startsWith("agent.device.")
                || toolId.startsWith("agent.app.") || toolId.startsWith("agent.log.")
                || toolId.startsWith("agent.verification.") || toolId.startsWith("agent.input.");
    }

    /**
     * 识别当前 JVM 所在桌面平台。
     *
     * @return 主机平台
     */
    private Platform hostPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return Platform.MACOS;
        }
        if (os.contains("win")) {
            return Platform.WINDOWS;
        }
        return Platform.LINUX;
    }

    /**
     * 单个统一 Gateway 工具的 Spring AI 回调。
     *
     * <p>by AI.Coding</p>
     */
    private class GatewayToolCallback implements ToolCallback {

        private final Definition definition;

        /**
         * 保存中立工具定义。
         *
         * @param definition 工具定义
         */
        GatewayToolCallback(Definition definition) {
            this.definition = definition;
        }

        /**
         * 返回符合 Provider 函数命名限制的工具定义。
         *
         * @return Spring AI 工具定义
         */
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(modelToolName(definition.identity().toolId()))
                    .description(definition.identity().description()
                            + " [toolId=" + definition.identity().toolId() + "]")
                    .inputSchema(definition.inputSchema().toString())
                    .build();
        }

        /**
         * 无上下文调用使用空上下文，主要用于框架兼容和测试。
         *
         * @param toolInput 工具参数 JSON
         * @return 工具结果
         */
        @Override
        public String call(String toolInput) {
            return call(toolInput, new ToolContext(Map.of()));
        }

        /**
         * 将模型工具调用转换为中立请求，并发布兼容工具卡片事件。
         *
         * @param toolInput 工具参数 JSON
         * @param toolContext Agent 和设备上下文
         * @return 封装后的不可信工具证据
         */
        @Override
        public String call(String toolInput, ToolContext toolContext) {
            CallRequest request = null;
            try {
                request = request(toolInput, toolContext);
                AdbMcpToolResult budgetFailure = toolBudgetFailure(toolContext.getContext());
                if (budgetFailure != null) {
                    publishToolEvent(request, budgetFailure);
                    return wrappedResult(request, budgetFailure);
                }
                CallResult result = toolGateway.call(request);
                AdbMcpToolResult compatible = compatibleResult(request, result);
                publishToolEvent(request, compatible);
                return wrappedResult(request, compatible);
            } catch (RuntimeException | JsonProcessingException ex) {
                AdbMcpToolResult failed = AdbMcpToolResult.failed(
                        "TOOL_GATEWAY_CALL_FAILED", "工具调用失败", "", null, AdbRiskLevel.HIGH)
                        .withToolMetadata(definition.identity().displayName(), "");
                if (request != null) {
                    publishToolEvent(request, failed);
                }
                return json(failed);
            }
        }

        /**
         * 在进入 Tool Gateway 前执行任务工具调用预算，超限时不执行任何工具副作用。
         */
        private AdbMcpToolResult toolBudgetFailure(Map<String, Object> context) {
            Object deadlineValue = context.get("taskDeadlineMillis");
            if (deadlineValue instanceof Number deadline
                    && System.currentTimeMillis() >= deadline.longValue()) {
                return AdbMcpToolResult.failed(
                        "AI_TASK_TIMEOUT", "任务总执行时间预算已耗尽",
                        "deadline reached", null, AdbRiskLevel.HIGH)
                        .withToolMetadata(definition.identity().displayName(), "");
            }
            Object stepCounterValue = context.get("stepCount");
            Object maxStepsValue = context.get("maxPlanSteps");
            if (stepCounterValue instanceof AtomicInteger stepCounter
                    && maxStepsValue instanceof Number maxSteps
                    && stepCounter.incrementAndGet() > Math.max(1, maxSteps.intValue())) {
                return AdbMcpToolResult.failed(
                        "AI_PLAN_STEP_BUDGET_EXCEEDED",
                        "任务计划步骤预算已耗尽，已停止新的工具执行",
                        "max=" + maxSteps.intValue(), null, AdbRiskLevel.HIGH)
                        .withToolMetadata(definition.identity().displayName(), "");
            }
            Object counterValue = context.get("toolCallCount");
            Object maxValue = context.get("maxToolCalls");
            if (!(counterValue instanceof AtomicInteger counter) || !(maxValue instanceof Number max)) {
                return null;
            }
            int used = counter.incrementAndGet();
            if (used <= Math.max(1, max.intValue())) {
                return null;
            }
            return AdbMcpToolResult.failed(
                    "AI_TOOL_BUDGET_EXCEEDED",
                    "工具调用预算已耗尽，已停止新的工具执行",
                    "used=" + used + ", max=" + max.intValue(),
                    null,
                    AdbRiskLevel.HIGH).withToolMetadata(definition.identity().displayName(), "");
        }

        /**
         * 构造中立工具调用请求，批准后的重试复用原步骤和工具调用标识。
         *
         * @param toolInput 工具参数 JSON
         * @param toolContext Spring AI 上下文
         * @return 中立调用请求
         * @throws JsonProcessingException 参数不是合法 JSON
         */
        private CallRequest request(String toolInput, ToolContext toolContext)
                throws JsonProcessingException {
            JsonNode arguments = objectMapper.readTree(emptyJson(toolInput));
            Map<String, Object> context = toolContext.getContext();
            String taskId = text(context.get("taskId"));
            String argumentDigest = digest(arguments);
            ConfirmationIdentity confirmation = confirmationIdentity(
                    text(context.get("confirmationToken")), taskId,
                    definition.identity().toolId(), argumentDigest);
            String replayToolCallId = dataEgressReplayToolCallId(
                    context, definition.identity().toolId(), argumentDigest);
            String stepId = confirmation == null
                    ? defaultText(context.get("stepId"), "step-" + UUID.randomUUID())
                    : confirmation.stepId();
            String toolCallId = confirmation == null
                    ? defaultText(replayToolCallId, "tool-call-" + UUID.randomUUID())
                    : confirmation.toolCallId();
            String confirmationId = confirmation == null ? "" : confirmation.confirmationId();
            Platform platform = executionPlatform(context, definition);
            return new CallRequest(
                    ToolContract.SCHEMA_VERSION,
                    new CallIdentity(
                            defaultText(context.get("conversationId"), "spring-ai-conversation"),
                            taskId,
                            defaultText(context.get("turnId"), "turn-" + UUID.randomUUID()),
                            stepId,
                            toolCallId,
                            Instant.now()),
                    new ToolReference(definition.identity().toolId(), definition.schemaVersion()),
                    arguments,
                    argumentDigest,
                    toolCallId,
                    Caller.AGENT,
                    new ExecutionContext(
                            platform,
                            text(context.get("deviceSerial")),
                            defaultText(context.get("workspace"), System.getProperty("user.dir", "")),
                            confirmationId,
                            List.of()));
        }

        /**
         * 数据外发确认后恢复同工具同参数的原调用标识，使 Gateway 重放已持久化结果。
         */
        private String dataEgressReplayToolCallId(
                Map<String, Object> context, String toolId, String argumentDigest) {
            Object value = context.get(DATA_EGRESS_REPLAY_CALLS_CONTEXT_KEY);
            if (!(value instanceof Map<?, ?> tools)) {
                return "";
            }
            Object callsValue = tools.get(toolId);
            if (!(callsValue instanceof Map<?, ?> calls)) {
                return "";
            }
            Object toolCallId = calls.get(argumentDigest);
            return toolCallId instanceof String text ? text : "";
        }
    }

    /**
     * 将中立结果转换为现有聊天工具卡片格式。
     *
     * @param request 调用请求
     * @param result 中立结果
     * @return 兼容结果
     */
    private AdbMcpToolResult compatibleResult(CallRequest request, CallResult result) {
        JsonNode output = result.payload().output();
        ToolContract.Error diagnostic = result.diagnostics().error();
        String stdout = output != null && output.has("stdout")
                ? output.path("stdout").asText("")
                : output == null ? "" : output.toPrettyString();
        String stderr = output == null ? "" : output.path("stderr").asText("");
        if (!StringUtils.hasText(stderr) && diagnostic != null) {
            // 工具失败详情必须传给模型和卡片，否则模型只能根据通用错误码猜测原因。
            stderr = diagnostic.detail();
        }
        String confirmationToken = result.status() == CallStatus.WAITING_CONFIRMATION
                ? confirmationToken(request, result)
                : "";
        String errorCode = diagnostic == null
                ? ""
                : diagnostic.code();
        boolean inputRequired = "agent.input.request".equals(request.tool().toolId());
        if (inputRequired) {
            errorCode = "AI_INPUT_REQUIRED";
        }
        return new AdbMcpToolResult(
                legacyStatus(result.status()),
                stdout,
                stderr,
                result.diagnostics().exit().code(),
                result.diagnostics().exit().timedOut(),
                result.timing().durationMs(),
                result.diagnostics().metrics().discardedBytes() > 0,
                legacyRisk(result.riskDecision().level()),
                result.status() == CallStatus.WAITING_CONFIRMATION,
                confirmationToken,
                inputRequired
                        ? output.path("reason").asText("需要用户补充信息")
                        : diagnostic == null ? result.payload().summary() : diagnostic.message(),
                errorCode,
                definitionTitle(result.tool().toolId()),
                output == null ? "" : output.path("commandSummary").asText(""));
    }

    /**
     * 将兼容结果封装为不可信工具证据后返回模型。
     *
     * @param request 调用请求
     * @param result 兼容结果
     * @return 安全封装文本
     */
    private String wrappedResult(CallRequest request, AdbMcpToolResult result) {
        return untrustedContentService.wrap(new Envelope(
                SourceType.TOOL_OUTPUT,
                request.tool().toolId() + ":" + request.identity().toolCallId(),
                "统一工具执行证据",
                "complete result",
                json(result)));
    }

    /**
     * 生成任务调用级工具事件键，兼容无任务标识的旧调用。
     *
     * @param request 工具请求
     * @return 唯一订阅键
     */
    public String eventKey(CallRequest request) {
        String taskId = request.identity().taskId();
        return StringUtils.hasText(taskId)
                ? taskId + ":" + request.identity().turnId()
                : request.identity().conversationId();
    }

    /**
     * 优先发布到任务调用级订阅；旧无任务订阅仅作为兼容回退。
     *
     * @param request 工具请求
     * @param result 工具结果
     */
    private void publishToolEvent(CallRequest request, AdbMcpToolResult result) {
        if (!eventPublisher.publish(eventKey(request), result)) {
            eventPublisher.publish(request.identity().conversationId(), result);
        }
    }

    /**
     * 已确认工具的执行输出，兼容 UI 展示并隔离不可信工具正文。
     *
     * <p>by AI.Coding</p>
     *
     * @param result 前端兼容工具结果
     * @param evidence 提交给模型的不可信证据文本
     */
    public record ConfirmedToolExecution(AdbMcpToolResult result, String evidence) {
    }

    /**
     * 生成可由前端解析但不能替代服务端确认校验的兼容令牌。
     *
     * @param request 调用请求
     * @param result 调用结果
     * @return 确认令牌
     */
    private String confirmationToken(CallRequest request, CallResult result) {
        if (confirmationStore == null || confirmationTokenService == null) {
            return AGENT_CONFIRMATION_PREFIX
                    + request.identity().taskId() + ":"
                    + result.riskDecision().confirmationId() + ":"
                    + request.identity().stepId() + ":"
                    + request.identity().toolCallId();
        }
        AgentConfirmation confirmation = confirmationStore.find(
                request.identity().taskId(), result.riskDecision().confirmationId())
                .orElseThrow(() -> new IllegalStateException("确认记录不存在"));
        return AGENT_CONFIRMATION_PREFIX
                + confirmation.taskId() + ":"
                + confirmation.confirmationId() + ":"
                + confirmationTokenService.issue(confirmation);
    }

    /**
     * 解析确认兼容令牌，格式不合法时按普通新工具调用处理。
     *
     * @param token 兼容令牌
     * @param taskId 当前任务标识
     * @param toolId 当前工具标识
     * @param argumentDigest 当前参数摘要
     * @return 原调用身份或 null
     */
    private ConfirmationIdentity confirmationIdentity(
            String token, String taskId, String toolId, String argumentDigest) {
        if (!token.startsWith(AGENT_CONFIRMATION_PREFIX)) {
            return null;
        }
        String[] parts = token.substring(AGENT_CONFIRMATION_PREFIX.length()).split(":", -1);
        if (List.of(parts).stream().anyMatch(String::isBlank)) {
            return null;
        }
        if (parts.length == 4 && confirmationTokenService == null) {
            ConfirmationIdentity legacy = new ConfirmationIdentity(
                    parts[0], parts[1], parts[2], parts[3]);
            return confirmationStore.find(legacy.taskId(), legacy.confirmationId())
                    .filter(value -> value.status() == AgentConfirmationStatus.ACCEPTED
                            || value.status() == AgentConfirmationStatus.CONSUMED)
                    .filter(value -> toolId.equals(value.binding().toolId()))
                    .filter(value -> argumentDigest.equals(value.binding().argumentDigest()))
                    .filter(value -> legacy.stepId().equals(value.binding().stepId()))
                    .filter(value -> legacy.toolCallId().equals(value.binding().toolCallId()))
                    .map(value -> legacy)
                    .orElse(null);
        }
        if (parts.length != 3) {
            return null;
        }
        String signature = parts[2];
        if (confirmationStore == null || !taskId.equals(parts[0])) {
            return null;
        }
        return confirmationStore.find(parts[0], parts[1])
                .filter(value -> value.status() == AgentConfirmationStatus.ACCEPTED
                        || value.status() == AgentConfirmationStatus.CONSUMED)
                .filter(value -> confirmationTokenService != null
                        && confirmationTokenService.matches(value, signature))
                .filter(value -> toolId.equals(value.binding().toolId()))
                .filter(value -> argumentDigest.equals(value.binding().argumentDigest()))
                .map(value -> new ConfirmationIdentity(
                        value.taskId(), value.confirmationId(),
                        value.binding().stepId(), value.binding().toolCallId()))
                .orElse(null);
    }

    /**
     * 根据工具声明和当前上下文选择真实执行平台。
     *
     * @param context ToolContext
     * @param definition 工具定义
     * @return 执行平台
     */
    private Platform executionPlatform(Map<String, Object> context, Definition definition) {
        Platform requested = platform(text(context.get("devicePlatform")));
        if (definition.metadata().platforms().contains(requested)) {
            return requested;
        }
        Platform host = hostPlatform();
        return definition.metadata().platforms().contains(host)
                ? host
                : definition.metadata().platforms().get(0);
    }

    /**
     * 将前端设备平台转换为中立平台。
     *
     * @param value 平台文本
     * @return 中立平台
     */
    private Platform platform(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "ios" -> Platform.IOS;
            case "harmony", "harmonyos", "harmony_os" -> Platform.HARMONY_OS;
            case "macos", "mac" -> Platform.MACOS;
            case "windows", "win" -> Platform.WINDOWS;
            case "linux" -> Platform.LINUX;
            default -> Platform.ANDROID;
        };
    }

    /**
     * 将中立状态映射为现有卡片状态。
     *
     * @param status 中立状态
     * @return 兼容状态
     */
    private AdbToolStatus legacyStatus(CallStatus status) {
        return switch (status) {
            case SUCCEEDED -> AdbToolStatus.SUCCESS;
            case WAITING_CONFIRMATION -> AdbToolStatus.CONFIRMATION_REQUIRED;
            case CANCELED -> AdbToolStatus.CANCELED;
            default -> AdbToolStatus.FAILED;
        };
    }

    /**
     * 将中立风险映射为现有展示级别。
     *
     * @param risk 风险
     * @return 兼容风险
     */
    private AdbRiskLevel legacyRisk(RiskLevel risk) {
        return switch (risk) {
            case LOW -> AdbRiskLevel.LOW;
            case MEDIUM -> AdbRiskLevel.HIGH;
            case HIGH, UNCLASSIFIED -> AdbRiskLevel.CRITICAL;
        };
    }

    /**
     * 返回工具显示标题，不再附加 MCP 协议术语。
     *
     * @param toolId 工具 ID
     * @return 显示标题
     */
    private String definitionTitle(String toolId) {
        return toolGateway.listTools().stream()
                .filter(value -> value.identity().toolId().equals(toolId))
                .map(value -> value.identity().displayName())
                .findFirst()
                .orElse("工具");
    }

    /**
     * 生成符合常见 Provider 函数名约束的稳定名称。
     *
     * @param toolId 中立工具 ID
     * @return 模型工具名
     */
    private String modelToolName(String toolId) {
        return toolId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /**
     * 计算规范 JSON 参数摘要，必须与 Tool Registry 和确认绑定保持一致。
     *
     * @param arguments 参数 JSON
     * @return SHA-256
     */
    private String digest(JsonNode arguments) {
        try {
            byte[] bytes = arguments.toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    /**
     * 序列化兼容工具结果，失败时返回稳定最小错误 JSON。
     *
     * @param result 工具结果
     * @return JSON
     */
    private String json(AdbMcpToolResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            return "{\"status\":\"FAILED\",\"errorCode\":\"TOOL_RESULT_JSON_INVALID\"}";
        }
    }

    /**
     * 空工具输入按空对象处理。
     *
     * @param input 原输入
     * @return JSON 文本
     */
    private String emptyJson(String input) {
        return StringUtils.hasText(input) ? input : "{}";
    }

    /**
     * 将上下文值转换为文本。
     *
     * @param value 上下文值
     * @return 文本
     */
    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 空文本使用默认值。
     *
     * @param value 上下文值
     * @param fallback 默认值
     * @return 有效文本
     */
    private String defaultText(Object value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }

    /**
     * 确认后的原工具调用身份。
     *
     * <p>by AI.Coding</p>
     *
     * @param taskId 任务标识
     * @param confirmationId 确认标识
     * @param stepId 步骤标识
     * @param toolCallId 工具调用标识
     */
    private record ConfirmationIdentity(
            String taskId,
            String confirmationId,
            String stepId,
            String toolCallId) {
    }
}
