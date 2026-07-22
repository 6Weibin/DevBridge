package com.devbridge.server.ai.tool.gateway;

import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointService;
import com.devbridge.server.ai.agent.checkpoint.AgentToolCallCheckpoint;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmation;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationCoordinator;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationStore;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationTokenService;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.runtime.AgentTaskApplicationService;
import com.devbridge.server.ai.agent.runtime.CreateAgentTaskCommand;
import com.devbridge.server.ai.localshell.model.LocalShellMcpToolRequest;
import com.devbridge.server.ai.mcp.model.AdbMcpToolRequest;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import com.devbridge.server.ai.mcp.model.AdbToolStatus;
import com.devbridge.server.ai.tool.AiToolRegistry;
import com.devbridge.server.ai.tool.AiToolRegistry.ConfirmedToolExecution;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallStatus;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.devbridge.server.model.BusinessException;
import com.devbridge.server.config.ToolExecutorConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;

/**
 * 旧 ADB/Local Shell REST 契约到统一 Tool Gateway 的兼容门面。
 *
 * <p>该类只转换旧 DTO；策略、确认、幂等、资源锁、审计和执行仍由统一 Gateway 负责。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class LegacyToolGatewayFacade {

    private static final String CONFIRMATION_PREFIX = "agent-confirmation:";

    private final ToolGateway gateway;
    private final AgentTaskApplicationService tasks;
    private final AgentCheckpointService checkpoints;
    private final AgentConfirmationStore confirmations;
    private final AgentConfirmationTokenService tokens;
    private final AgentConfirmationCoordinator coordinator;
    private final AiToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, String> runningTasks = new ConcurrentHashMap<>();
    private ExecutorService streamExecutor = ForkJoinPool.commonPool();

    @Value("${devbridge.ai-features.agent-runtime-enabled:true}")
    private boolean agentRuntimeEnabled = true;

    /** 注入现有统一控制面依赖。 */
    public LegacyToolGatewayFacade(
            ToolGateway gateway,
            AgentTaskApplicationService tasks,
            AgentCheckpointService checkpoints,
            AgentConfirmationStore confirmations,
            AgentConfirmationTokenService tokens,
            AgentConfirmationCoordinator coordinator,
            AiToolRegistry toolRegistry,
            ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.tasks = tasks;
        this.checkpoints = checkpoints;
        this.confirmations = confirmations;
        this.tokens = tokens;
        this.coordinator = coordinator;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    /** 注入有界工具线程池；使用方法注入以保持既有构造器参数不超过八个。 */
    @Autowired
    public void setStreamExecutor(
            @Qualifier(ToolExecutorConfiguration.TOOL_EXECUTION_EXECUTOR) ExecutorService streamExecutor) {
        this.streamExecutor = streamExecutor;
    }

    /** 通过统一 Gateway 调用 ADB 兼容工具。 */
    public AdbMcpToolResult callAdb(AdbMcpToolRequest request) {
        return call(new LegacyCall(
                "android.adb." + request.toolName(), request.conversationId(),
                request.deviceSerial(), "", request.safeArguments(), request.requestId(),
                Platform.ANDROID, "ADB"));
    }

    /** 通过统一 Gateway 调用 Local Shell 兼容工具。 */
    public AdbMcpToolResult callLocal(LocalShellMcpToolRequest request) {
        Map<String, Object> arguments = request.safeArguments();
        String workspace = String.valueOf(arguments.getOrDefault("workingDirectory", ""));
        return call(new LegacyCall(
                "desktop.shell." + request.toolName(), request.conversationId(),
                "", workspace, arguments, request.requestId(), hostPlatform(), "Local Shell"));
    }

    /** 先建立 SSE，再在线程池中调用统一 Gateway 执行 ADB 工具。 */
    public SseEmitter streamAdb(AdbMcpToolRequest request) {
        return streamAsync(() -> callAdb(request), "ADB");
    }

    /** 先建立 SSE，再在线程池中调用统一 Gateway 执行本机工具。 */
    public SseEmitter streamLocal(LocalShellMcpToolRequest request) {
        return streamAsync(() -> callLocal(request), "Local Shell");
    }

    /** 以旧 SSE 事件格式返回统一 Gateway 结果。 */
    public SseEmitter stream(AdbMcpToolResult result) {
        SseEmitter emitter = new SseEmitter(60_000L);
        sendStreamResult(emitter, result);
        return emitter;
    }

    /** 异步执行旧工具调用，避免控制器线程在创建 SSE 前被命令阻塞。 */
    private SseEmitter streamAsync(Supplier<AdbMcpToolResult> invocation, String toolTitle) {
        SseEmitter emitter = new SseEmitter(60_000L);
        try {
            streamExecutor.execute(() -> {
                try {
                    sendStreamResult(emitter, invocation.get());
                } catch (RuntimeException ex) {
                    sendStreamResult(emitter, streamFailure(ex, toolTitle));
                }
            });
        } catch (RuntimeException ex) {
            // 有界线程池饱和时仍按旧 SSE 契约返回结构化错误，避免客户端只看到连接中断。
            sendStreamResult(emitter, streamFailure(ex, toolTitle));
        }
        return emitter;
    }

    /** 写入单个兼容事件并完成 SSE。 */
    private void sendStreamResult(SseEmitter emitter, AdbMcpToolResult result) {
        try {
            String event = result.confirmationRequired() ? "tool-confirmation"
                    : result.status() == AdbToolStatus.SUCCESS ? "tool-result" : "tool-error";
            emitter.send(SseEmitter.event().name(event).data(result));
            emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    /** 将异步执行异常转换为旧 REST 可识别的脱敏工具错误。 */
    private AdbMcpToolResult streamFailure(RuntimeException error, String toolTitle) {
        if (error instanceof BusinessException business) {
            return AdbMcpToolResult.failed(
                    business.getErrorCode(), business.getMessage(), business.getDetail(), null, AdbRiskLevel.LOW)
                    .withToolMetadata(toolTitle, "");
        }
        return AdbMcpToolResult.failed(
                "AI_TOOL_STREAM_FAILED", "工具流式执行失败",
                StringUtils.hasText(error.getMessage()) ? error.getMessage() : error.getClass().getSimpleName(),
                null, AdbRiskLevel.LOW)
                .withToolMetadata(toolTitle, "");
    }

    /** 批准旧 REST 返回的 Agent 确认令牌并执行原工具，不启动第二套确认服务。 */
    public AdbMcpToolResult approve(String token, String conversationId) {
        requireRuntimeEnabled();
        TokenIdentity identity = tokenIdentity(token);
        AgentConfirmation confirmation = confirmations.find(identity.taskId(), identity.confirmationId())
                .orElseThrow(() -> new IllegalArgumentException("确认记录不存在"));
        var approval = coordinator.approve(
                identity.taskId(), identity.confirmationId(), conversationId, identity.signature());
        if (approval.terminalTask() != null) {
            clearRunningRequest(confirmation);
            return restoreResult(approval.terminalTask());
        }
        try {
            AgentToolCallCheckpoint saved = approval.recovery().checkpoint().recoveryState().toolCalls()
                    .get(confirmation.binding().toolCallId());
            if (saved == null) {
                throw new IllegalStateException("确认任务缺少原工具请求");
            }
            CallRequest original = checkpoints.restore(saved.protectedRequest(), CallRequest.class);
            ConfirmedToolExecution execution = toolRegistry.executeConfirmed(
                    confirmedRequest(original, confirmation.confirmationId()));
            AdbMcpToolResult result = execution.result();
            tasks.completeTask(identity.taskId(), checkpoints.protect(result));
            clearRunningRequest(confirmation);
            return result;
        } catch (RuntimeException ex) {
            failRunningTask(identity.taskId(), confirmation, ex);
            throw ex;
        }
    }

    /** 拒绝旧 REST 返回的 Agent 确认令牌。 */
    public AdbMcpToolResult reject(String token, String conversationId) {
        requireRuntimeEnabled();
        TokenIdentity identity = tokenIdentity(token);
        AgentConfirmation confirmation = coordinator.reject(identity.taskId(), identity.confirmationId(), conversationId,
                identity.signature(), "用户取消敏感操作");
        clearRunningRequest(confirmation);
        return AdbMcpToolResult.canceled("已取消敏感操作。");
    }

    /** 兼容旧 requestId 取消接口，并将取消传播到统一 Agent Task。 */
    public AdbMcpToolResult cancel(String requestId) {
        requireRuntimeEnabled();
        String taskId = runningTasks.get(requestId);
        if (!StringUtils.hasText(taskId)) {
            return AdbMcpToolResult.failed(
                    "TOOL_NOT_RUNNING", "未找到运行中的工具调用", "", null, AdbRiskLevel.LOW);
        }
        tasks.cancelTask(taskId);
        runningTasks.remove(requestId, taskId);
        return AdbMcpToolResult.canceled("已取消运行中的工具调用。");
    }

    /** 调用统一 Gateway，并将结果持久化到同一个 Agent Task。 */
    private AdbMcpToolResult call(LegacyCall call) {
        requireRuntimeEnabled();
        String requestId = StringUtils.hasText(call.requestId())
                ? call.requestId().trim() : UUID.randomUUID().toString();
        String conversationId = StringUtils.hasText(call.conversationId())
                ? call.conversationId().trim() : "legacy-rest";
        var opened = tasks.startTaskResult(new CreateAgentTaskCommand(
                conversationId, idempotencyGoal(call.toolId(), call.arguments()), requestId));
        AgentTask task = opened.task();
        if (!opened.changed()) {
            return replayExisting(task);
        }
        runningTasks.put(requestId, task.taskId());
        CallRequest request = gatewayRequest(call, task, conversationId, requestId);
        try {
            CallResult result = gateway.call(request);
            AdbMcpToolResult compatible = compatibleResult(task, result, call.title());
            if (result.status() != CallStatus.WAITING_CONFIRMATION) {
                tasks.completeTask(task.taskId(), checkpoints.protect(compatible));
                runningTasks.remove(requestId, task.taskId());
            }
            return compatible;
        } catch (RuntimeException ex) {
            runningTasks.remove(requestId, task.taskId());
            tasks.failTask(task.taskId(), "统一工具调用失败: " + ex.getClass().getSimpleName());
            throw ex;
        }
    }

    /** 清理确认对应的旧 requestId 映射，避免长期运行积累已终止任务。 */
    private void clearRunningRequest(AgentConfirmation confirmation) {
        runningTasks.remove(confirmation.binding().toolCallId(), confirmation.taskId());
    }

    /** 确认续跑异常时明确结束任务并清理兼容映射。 */
    private void failRunningTask(
            String taskId, AgentConfirmation confirmation, RuntimeException error) {
        clearRunningRequest(confirmation);
        tasks.failTask(taskId, "确认后的工具调用失败: " + error.getClass().getSimpleName());
    }

    /** 构造中立 Gateway 请求。 */
    private CallRequest gatewayRequest(
            LegacyCall call, AgentTask task, String conversationId, String requestId) {
        JsonNode arguments = objectMapper.valueToTree(call.arguments());
        String digest = digest(arguments);
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity(conversationId, task.taskId(), "turn-" + requestId,
                        "step-" + requestId, requestId, Instant.now()),
                new ToolReference(call.toolId(), ToolContract.SCHEMA_VERSION),
                arguments, digest, requestId, Caller.USER,
                new ExecutionContext(call.platform(), call.deviceId(), call.workspace(), "", List.of()));
    }

    /** 构造绑定工具和规范参数摘要的任务目标，防止相同 requestId 重放不同命令。 */
    String idempotencyGoal(String toolId, Map<String, Object> arguments) {
        String argumentDigest = digest(objectMapper.valueToTree(arguments == null ? Map.of() : arguments));
        return "调用工具 " + toolId + " 参数摘要 " + argumentDigest;
    }

    /** 将中立结果恢复为旧前端工具卡片 DTO。 */
    private AdbMcpToolResult compatibleResult(
            AgentTask task, CallResult result, String title) {
        JsonNode output = result.payload().output();
        AdbRiskLevel risk = AdbRiskLevel.valueOf(result.riskDecision().level().name());
        if (result.status() == CallStatus.WAITING_CONFIRMATION) {
            AgentConfirmation confirmation = confirmations.find(
                    task.taskId(), result.riskDecision().confirmationId())
                    .orElseThrow(() -> new IllegalStateException("确认记录不存在"));
            String token = CONFIRMATION_PREFIX + task.taskId() + ":"
                    + confirmation.confirmationId() + ":" + tokens.issue(confirmation);
            return AdbMcpToolResult.confirmationRequired(token, result.payload().summary(), risk)
                    .withToolMetadata(title, text(output, "commandSummary"));
        }
        AdbToolStatus status = status(result.status());
        String errorCode = result.diagnostics().error() == null ? "" : result.diagnostics().error().code();
        Integer exitCode = result.diagnostics().exit() == null ? null : result.diagnostics().exit().code();
        boolean timedOut = result.diagnostics().exit() != null && result.diagnostics().exit().timedOut();
        return new AdbMcpToolResult(
                status, text(output, "stdout"), text(output, "stderr"), exitCode, timedOut,
                result.timing().durationMs(), output.path("truncated").asBoolean(false), risk,
                false, "", result.payload().summary(), errorCode, title,
                text(output, "commandSummary"));
    }

    /** 重放幂等 REST 请求的原结果。 */
    private AdbMcpToolResult replayExisting(AgentTask task) {
        if (task.state() == AgentTaskState.COMPLETED) {
            return restoreResult(task);
        }
        if (task.state() == AgentTaskState.WAITING_CONFIRMATION) {
            var recovery = checkpoints.loadRecovery(task.taskId())
                    .orElseThrow(() -> new IllegalStateException("等待确认任务缺少恢复点"));
            String confirmationId = recovery.checkpoint().recoveryState().pendingConfirmationId();
            AgentConfirmation confirmation = confirmations.find(task.taskId(), confirmationId)
                    .orElseThrow(() -> new IllegalStateException("等待确认任务缺少确认记录"));
            String token = CONFIRMATION_PREFIX + task.taskId() + ":" + confirmationId + ":"
                    + tokens.issue(confirmation);
            return AdbMcpToolResult.confirmationRequired(
                    token, confirmation.binding().impactSummary(),
                    AdbRiskLevel.valueOf(confirmation.binding().riskLevel().name()));
        }
        throw new IllegalStateException("相同工具请求仍在执行，当前状态=" + task.state());
    }

    /** 从任务受保护结果恢复旧 DTO。 */
    private AdbMcpToolResult restoreResult(AgentTask task) {
        AdbMcpToolResult result = checkpoints.restore(task.protectedResult(), AdbMcpToolResult.class);
        if (result == null) {
            throw new IllegalStateException("任务结果不存在");
        }
        return result;
    }

    /** 为批准执行绑定原确认 ID。 */
    private CallRequest confirmedRequest(CallRequest request, String confirmationId) {
        ExecutionContext context = request.executionContext();
        return new CallRequest(
                request.schemaVersion(), request.identity(), request.tool(), request.arguments(),
                request.argumentDigest(), request.idempotencyKey(), request.requestedBy(),
                new ExecutionContext(context.platform(), context.deviceId(), context.workspace(),
                        confirmationId, context.resourceHints(), context.workflowAuthorization()));
    }

    /** 解析并校验 Agent 确认令牌结构。 */
    private TokenIdentity tokenIdentity(String token) {
        if (token == null || !token.startsWith(CONFIRMATION_PREFIX)) {
            throw new IllegalArgumentException("确认令牌格式无效");
        }
        String[] values = token.substring(CONFIRMATION_PREFIX.length()).split(":", -1);
        if (values.length != 3 || List.of(values).stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("确认令牌格式无效");
        }
        return new TokenIdentity(values[0], values[1], values[2]);
    }

    /** 计算规范化工具参数摘要。 */
    private String digest(JsonNode arguments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    objectMapper.writeValueAsString(arguments).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | java.io.IOException ex) {
            throw new IllegalStateException("工具参数摘要计算失败", ex);
        }
    }

    /** 把中立状态映射为旧状态。 */
    private AdbToolStatus status(CallStatus status) {
        return switch (status) {
            case SUCCEEDED -> AdbToolStatus.SUCCESS;
            case CANCELED -> AdbToolStatus.CANCELED;
            case TIMED_OUT -> AdbToolStatus.FAILED;
            default -> AdbToolStatus.FAILED;
        };
    }

    /** 安全读取工具输出字段。 */
    private String text(JsonNode output, String field) {
        return output == null ? "" : output.path(field).asText("");
    }

    /** 根据当前操作系统选择本机工具平台。 */
    private Platform hostPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return Platform.WINDOWS;
        if (os.contains("mac")) return Platform.MACOS;
        return Platform.LINUX;
    }

    /** Agent Runtime 关闭时阻止旧 REST 工具入口隐式绕过控制面。 */
    private void requireRuntimeEnabled() {
        if (!agentRuntimeEnabled) {
            throw new BusinessException(
                    "AI_AGENT_RUNTIME_DISABLED", "Agent Runtime 已关闭",
                    HttpStatus.SERVICE_UNAVAILABLE, "AI tool gateway disabled");
        }
    }

    /** 旧 REST 调用归一化参数。by AI.Coding */
    private record LegacyCall(
            String toolId, String conversationId, String deviceId, String workspace,
            Map<String, Object> arguments, String requestId, Platform platform, String title) {
    }

    /** Agent 确认令牌组成。by AI.Coding */
    private record TokenIdentity(String taskId, String confirmationId, String signature) {
    }
}
