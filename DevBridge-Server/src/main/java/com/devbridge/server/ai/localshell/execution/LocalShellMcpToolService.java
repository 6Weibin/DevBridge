package com.devbridge.server.ai.localshell.execution;

import com.devbridge.server.ai.localshell.audit.LocalShellAuditRecorder;
import com.devbridge.server.ai.localshell.catalog.LocalShellToolCatalog;
import com.devbridge.server.ai.localshell.confirmation.LocalShellConfirmationService;
import com.devbridge.server.ai.localshell.model.LocalShellAuditEvent;
import com.devbridge.server.ai.localshell.model.LocalShellCommandPlan;
import com.devbridge.server.ai.localshell.model.LocalShellConfirmationChallenge;
import com.devbridge.server.ai.localshell.model.LocalShellConfirmationCheck;
import com.devbridge.server.ai.localshell.model.LocalShellConfirmationRequest;
import com.devbridge.server.ai.localshell.model.LocalShellMcpToolDefinition;
import com.devbridge.server.ai.localshell.model.LocalShellMcpToolRequest;
import com.devbridge.server.ai.localshell.model.LocalShellRiskAssessment;
import com.devbridge.server.ai.localshell.policy.LocalShellCommandPlanner;
import com.devbridge.server.ai.localshell.policy.LocalShellRiskClassifier;
import com.devbridge.server.ai.localshell.security.LocalShellOutputSanitizer;
import com.devbridge.server.ai.mcp.model.AdbConfirmationStatus;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import com.devbridge.server.ai.mcp.model.AdbToolStatus;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.BusinessException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Local Shell MCP 工具服务，提供目录、调用、确认和取消的统一内部入口。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class LocalShellMcpToolService {

    private final LocalShellToolCatalog catalog;
    private final LocalShellCommandPlanner planner;
    private final LocalShellRiskClassifier riskClassifier;
    private final LocalShellConfirmationService confirmationService;
    private final LocalShellCommandExecutor commandExecutor;
    private final LocalShellAuditRecorder auditRecorder;
    private final LocalShellOutputSanitizer sanitizer;
    private final DevBridgeProperties properties;

    /**
     * 注入 Local Shell MCP 核心依赖。
     */
    public LocalShellMcpToolService(
            LocalShellToolCatalog catalog,
            LocalShellCommandPlanner planner,
            LocalShellRiskClassifier riskClassifier,
            LocalShellConfirmationService confirmationService,
            LocalShellCommandExecutor commandExecutor,
            LocalShellAuditRecorder auditRecorder,
            LocalShellOutputSanitizer sanitizer,
            DevBridgeProperties properties) {
        this.catalog = catalog;
        this.planner = planner;
        this.riskClassifier = riskClassifier;
        this.confirmationService = confirmationService;
        this.commandExecutor = commandExecutor;
        this.auditRecorder = auditRecorder;
        this.sanitizer = sanitizer;
        this.properties = properties;
    }

    /**
     * 返回 Local Shell MCP 工具目录。
     *
     * @return 工具目录
     */
    public List<LocalShellMcpToolDefinition> listTools() {
        ensureEnabled();
        return catalog.listTools();
    }

    /**
     * 调用 Local Shell MCP 工具。
     *
     * @param request 工具请求
     * @return 工具结果
     */
    public AdbMcpToolResult call(LocalShellMcpToolRequest request) {
        ensureEnabled();
        validateRequest(request);
        AdbMcpToolResult controlResult = controlResult(request);
        if (controlResult != null) {
            return controlResult;
        }
        LocalShellCommandPlan plan = plan(request);
        LocalShellRiskAssessment assessment = riskClassifier.assess(plan);
        AdbMcpToolResult early = earlyResult(request, plan, assessment);
        if (early != null) {
            return early;
        }
        return executeAndAudit(plan, assessment.riskLevel(), AdbConfirmationStatus.APPROVED);
    }

    /**
     * 执行已经通过统一 Tool Gateway 策略和确认的本机工具请求。
     *
     * <p>该入口仅供同包 Local Shell Gateway Adapter 使用，执行前仍重新规划并计算本地风险。</p>
     *
     * @param request Local Shell 请求
     * @return 兼容工具结果
     */
    AdbMcpToolResult callApprovedByGateway(LocalShellMcpToolRequest request) {
        ensureEnabled();
        validateRequest(request);
        AdbMcpToolResult control = controlResult(request);
        if (control != null) {
            return control;
        }
        LocalShellCommandPlan plan = plan(request);
        LocalShellRiskAssessment assessment = riskClassifier.assess(plan);
        if (assessment.denied()) {
            return AdbMcpToolResult.failed(
                            "LOCAL_SHELL_COMMAND_DENIED",
                            String.join("；", assessment.reasons()),
                            "",
                            null,
                            assessment.riskLevel())
                    .withToolMetadata("Local Shell MCP", plan.commandSummary());
        }
        return executeAndAudit(plan, assessment.riskLevel(), AdbConfirmationStatus.APPROVED);
    }

    /**
     * 通过确认令牌直接执行其绑定命令。
     *
     * @param token 确认令牌
     * @param conversationId 对话 ID
     * @return 工具结果
     */
    public AdbMcpToolResult approve(String token, String conversationId) {
        ensureEnabled();
        LocalShellCommandPlan plan = confirmationService.consumePlan(token, conversationId);
        LocalShellRiskAssessment assessment = riskClassifier.assess(plan);
        return executeAndAudit(plan, assessment.riskLevel(), AdbConfirmationStatus.APPROVED);
    }

    /**
     * 取消确认令牌。
     *
     * @param token 确认令牌
     * @param conversationId 对话 ID
     * @return 取消结果
     */
    public AdbMcpToolResult cancelConfirmation(String token, String conversationId) {
        ensureEnabled();
        confirmationService.cancel(token, conversationId);
        return AdbMcpToolResult.canceled("已取消敏感本机命令操作。").withToolMetadata("Local Shell MCP", "");
    }

    /**
     * 取消运行中工具。
     *
     * @param requestId 请求 ID
     * @return 取消结果
     */
    public AdbMcpToolResult cancel(String requestId) {
        ensureEnabled();
        return commandExecutor.cancel(requestId).withToolMetadata("Local Shell MCP", "local_shell_cancel " + requestId);
    }

    /**
     * 以 SSE 形式调用工具；敏感操作仍先返回确认结果。
     *
     * @param request 工具请求
     * @return SSE Emitter
     */
    public SseEmitter streamCall(LocalShellMcpToolRequest request) {
        try {
            StreamPlan streamPlan = streamPlan(request);
            if (streamPlan.result() != null) {
                SseEmitter emitter = new SseEmitter(60_000L);
                send(emitter, eventName(streamPlan.result()), streamPlan.result());
                emitter.complete();
                return emitter;
            }
            return commandExecutor.executeStreaming(streamPlan.plan(), streamPlan.assessment().riskLevel());
        } catch (BusinessException ex) {
            SseEmitter emitter = new SseEmitter(60_000L);
            send(emitter, "tool-error", AdbMcpToolResult.failed(ex.getErrorCode(), ex.getMessage(), ex.getDetail(), null, AdbRiskLevel.LOW)
                    .withToolMetadata("Local Shell MCP", ""));
            emitter.complete();
            return emitter;
        }
    }

    /**
     * 为流式调用准备命令计划。
     *
     * @param request 工具请求
     * @return 流式计划
     */
    private StreamPlan streamPlan(LocalShellMcpToolRequest request) {
        ensureEnabled();
        validateRequest(request);
        AdbMcpToolResult controlResult = controlResult(request);
        if (controlResult != null) {
            return new StreamPlan(null, null, controlResult);
        }
        LocalShellCommandPlan plan = plan(request);
        LocalShellRiskAssessment assessment = riskClassifier.assess(plan);
        return new StreamPlan(plan, assessment, earlyResult(request, plan, assessment));
    }

    /**
     * 处理不需要启动新进程的控制类工具，避免误走通用命令执行链路。
     *
     * @param request 工具请求
     * @return 控制工具结果，非控制工具返回 null
     */
    private AdbMcpToolResult controlResult(LocalShellMcpToolRequest request) {
        catalog.requireTool(request.toolName());
        if ("local_shell_process_status".equals(request.toolName())) {
            return commandExecutor.processStatus();
        }
        if ("local_shell_cancel".equals(request.toolName())) {
            return commandExecutor.cancel(requiredRequestId(request.safeArguments()));
        }
        return null;
    }

    /**
     * 读取取消工具必需的 requestId 参数。
     *
     * @param args 工具参数
     * @return 请求 ID
     */
    private String requiredRequestId(Map<String, Object> args) {
        String requestId = String.valueOf(args.getOrDefault("requestId", ""));
        if (!StringUtils.hasText(requestId)) {
            throw new BusinessException("LOCAL_SHELL_COMMAND_EMPTY", "取消本机命令需要 requestId", HttpStatus.BAD_REQUEST, "");
        }
        return requestId;
    }

    /**
     * 生成命令计划。
     *
     * @param request 工具请求
     * @return 命令计划
     */
    private LocalShellCommandPlan plan(LocalShellMcpToolRequest request) {
        LocalShellMcpToolDefinition definition = catalog.requireTool(request.toolName());
        return planner.plan(request, definition);
    }

    /**
     * 对拒绝、待确认和令牌二次调用做提前处理。
     *
     * @param request 工具请求
     * @param plan 命令计划
     * @param assessment 风险评估
     * @return 提前结果，没有则返回 null
     */
    private AdbMcpToolResult earlyResult(
            LocalShellMcpToolRequest request,
            LocalShellCommandPlan plan,
            LocalShellRiskAssessment assessment) {
        if (assessment.denied()) {
            return AdbMcpToolResult.failed("LOCAL_SHELL_COMMAND_DENIED", String.join("；", assessment.reasons()), "", null, assessment.riskLevel())
                    .withToolMetadata("Local Shell MCP", plan.commandSummary());
        }
        if (assessment.confirmationRequired() && !StringUtils.hasText(request.confirmationToken())) {
            return confirmationResult(plan, assessment);
        }
        if (StringUtils.hasText(request.confirmationToken())) {
            verifyConfirmation(request.confirmationToken(), plan, assessment.riskLevel());
        }
        return null;
    }

    /**
     * 创建确认结果，确保真实命令不在确认前执行。
     *
     * @param plan 命令计划
     * @param assessment 风险评估
     * @return 待确认结果
     */
    private AdbMcpToolResult confirmationResult(LocalShellCommandPlan plan, LocalShellRiskAssessment assessment) {
        LocalShellConfirmationChallenge challenge = confirmationService.create(new LocalShellConfirmationRequest(
                plan,
                assessment,
                properties.getAiMcpLocalShell().getConfirmationTtl()));
        String message = "敏感本机命令需要确认。命令：" + challenge.commandSummary()
                + "；工作目录：" + challenge.workingDirectory()
                + "；风险：" + challenge.reason()
                + "；影响：" + challenge.impact()
                + "；有效期：" + challenge.expiresAt();
        audit(plan, assessment.riskLevel(), AdbConfirmationStatus.PENDING, 0, null, true, "pending confirmation");
        return AdbMcpToolResult.confirmationRequired(challenge.token(), message, assessment.riskLevel())
                .withToolMetadata("Local Shell MCP", challenge.commandSummary());
    }

    /**
     * 校验模型二次调用携带的确认令牌。
     *
     * @param token 确认令牌
     * @param plan 命令计划
     * @param riskLevel 风险级别
     */
    private void verifyConfirmation(String token, LocalShellCommandPlan plan, AdbRiskLevel riskLevel) {
        confirmationService.verifyAndConsume(token, new LocalShellConfirmationCheck(
                plan.conversationId(),
                confirmationService.commandHash(plan),
                confirmationService.workingDirectoryHash(plan),
                riskLevel));
    }

    /**
     * 执行命令并记录审计。
     *
     * @param plan 命令计划
     * @param riskLevel 风险级别
     * @param confirmationStatus 确认状态
     * @return 工具结果
     */
    private AdbMcpToolResult executeAndAudit(
            LocalShellCommandPlan plan,
            AdbRiskLevel riskLevel,
            AdbConfirmationStatus confirmationStatus) {
        AdbMcpToolResult result = commandExecutor.execute(plan, riskLevel);
        audit(plan, riskLevel, confirmationStatus, result.durationMillis(), result.exitCode(), result.status() == AdbToolStatus.SUCCESS, result.stderr());
        return result;
    }

    /**
     * 记录审计摘要。
     *
     * @param plan 命令计划
     * @param riskLevel 风险级别
     * @param confirmationStatus 确认状态
     * @param durationMillis 耗时
     * @param exitCode 退出码
     * @param success 是否成功
     * @param error 错误摘要
     */
    private void audit(
            LocalShellCommandPlan plan,
            AdbRiskLevel riskLevel,
            AdbConfirmationStatus confirmationStatus,
            long durationMillis,
            Integer exitCode,
            boolean success,
            String error) {
        auditRecorder.record(new LocalShellAuditEvent(
                plan.toolName(),
                sanitizer.sanitizeCommand(plan.commandSummary()),
                plan.workingDirectory().toString(),
                riskLevel,
                confirmationStatus,
                durationMillis,
                exitCode,
                success,
                error));
    }

    /**
     * 校验 Local Shell MCP 开关。
     */
    private void ensureEnabled() {
        if (!properties.getAiMcpLocalShell().isEnabled()) {
            throw new BusinessException("LOCAL_SHELL_DISABLED", "Local Shell MCP 工具未启用", HttpStatus.CONFLICT, "");
        }
    }

    /**
     * 校验工具请求基础字段。
     *
     * @param request 工具请求
     */
    private void validateRequest(LocalShellMcpToolRequest request) {
        if (request == null || !StringUtils.hasText(request.toolName())) {
            throw new BusinessException("LOCAL_SHELL_COMMAND_EMPTY", "Local Shell MCP 工具名不能为空", HttpStatus.BAD_REQUEST, "");
        }
        if (!StringUtils.hasText(request.conversationId())) {
            throw new BusinessException("LOCAL_SHELL_CONVERSATION_EMPTY", "Local Shell MCP 对话 ID 不能为空", HttpStatus.BAD_REQUEST, "");
        }
    }

    /**
     * 根据工具结果选择 SSE 事件名。
     *
     * @param result 工具结果
     * @return 事件名
     */
    private String eventName(AdbMcpToolResult result) {
        return result.confirmationRequired() ? "tool-confirmation" : result.errorCode().isBlank() ? "tool-result" : "tool-error";
    }

    /**
     * 发送 SSE 事件；客户端断开时完成 emitter。
     *
     * @param emitter SSE emitter
     * @param eventName 事件名
     * @param data 事件数据
     */
    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException ex) {
            emitter.complete();
        }
    }

    /**
     * 流式调用准备结果。
     *
     * <p>by AI.Coding</p>
     *
     * @param plan 命令计划
     * @param assessment 风险评估
     * @param result 提前返回结果
     */
    private record StreamPlan(LocalShellCommandPlan plan, LocalShellRiskAssessment assessment, AdbMcpToolResult result) {
    }
}
