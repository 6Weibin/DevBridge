package com.devbridge.server.ai.mcp.execution;

import com.devbridge.server.ai.mcp.audit.AdbToolAuditRecorder;
import com.devbridge.server.ai.mcp.catalog.AdbToolCatalog;
import com.devbridge.server.ai.mcp.confirmation.AdbConfirmationService;
import com.devbridge.server.ai.mcp.model.AdbCommandPlan;
import com.devbridge.server.ai.mcp.model.AdbConfirmationCheck;
import com.devbridge.server.ai.mcp.model.AdbConfirmationChallenge;
import com.devbridge.server.ai.mcp.model.AdbConfirmationRequest;
import com.devbridge.server.ai.mcp.model.AdbConfirmationStatus;
import com.devbridge.server.ai.mcp.model.AdbMcpToolDefinition;
import com.devbridge.server.ai.mcp.model.AdbMcpToolRequest;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import com.devbridge.server.ai.mcp.model.AdbRiskAssessment;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import com.devbridge.server.ai.mcp.model.AdbToolAuditEvent;
import com.devbridge.server.ai.mcp.model.AdbToolStatus;
import com.devbridge.server.ai.mcp.risk.AdbRiskClassifier;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.BusinessException;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ADB MCP 工具服务，提供目录、调用、确认和取消的统一内部入口。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AdbMcpToolService {

    private final AdbToolCatalog catalog;
    private final AdbCommandPlanner planner;
    private final AdbDeviceValidator deviceValidator;
    private final AdbRiskClassifier riskClassifier;
    private final AdbConfirmationService confirmationService;
    private final AdbCommandExecutor commandExecutor;
    private final AdbToolAuditRecorder auditRecorder;
    private final DevBridgeProperties properties;

    /**
     * 注入 ADB MCP 核心依赖。
     */
    public AdbMcpToolService(
            AdbToolCatalog catalog,
            AdbCommandPlanner planner,
            AdbDeviceValidator deviceValidator,
            AdbRiskClassifier riskClassifier,
            AdbConfirmationService confirmationService,
            AdbCommandExecutor commandExecutor,
            AdbToolAuditRecorder auditRecorder,
            DevBridgeProperties properties) {
        this.catalog = catalog;
        this.planner = planner;
        this.deviceValidator = deviceValidator;
        this.riskClassifier = riskClassifier;
        this.confirmationService = confirmationService;
        this.commandExecutor = commandExecutor;
        this.auditRecorder = auditRecorder;
        this.properties = properties;
    }

    /**
     * 返回 ADB MCP 工具目录。
     *
     * @return 工具目录
     */
    public List<AdbMcpToolDefinition> listTools() {
        ensureEnabled();
        return catalog.listTools();
    }

    /**
     * 调用 ADB MCP 工具。
     *
     * @param request 工具请求
     * @return 工具结果
     */
    public AdbMcpToolResult call(AdbMcpToolRequest request) {
        ensureEnabled();
        validateRequest(request);
        AdbMcpToolDefinition definition = catalog.requireTool(request.toolName());
        AdbCommandPlan plan = planner.plan(request, definition);
        deviceValidator.validate(plan);
        AdbRiskAssessment assessment = riskClassifier.assess(plan);
        if (assessment.confirmationRequired() && !StringUtils.hasText(request.confirmationToken())) {
            return confirmationResult(plan, assessment);
        }
        if (StringUtils.hasText(request.confirmationToken())) {
            verifyConfirmation(request.confirmationToken(), plan, assessment.riskLevel());
        }
        return executeAndAudit(plan, assessment.riskLevel(), AdbConfirmationStatus.APPROVED);
    }

    /**
     * 执行已经通过统一 Tool Gateway 确认和策略校验的 ADB 请求。
     *
     * <p>该入口仅供同包 ADB Gateway Adapter 使用，仍会重新规划、校验设备和计算风险，防止确认后上下文变化。</p>
     *
     * @param request ADB 工具请求
     * @return ADB 兼容结果
     */
    AdbMcpToolResult callApprovedByGateway(AdbMcpToolRequest request) {
        ensureEnabled();
        validateRequest(request);
        AdbMcpToolDefinition definition = catalog.requireTool(request.toolName());
        AdbCommandPlan plan = planner.plan(request, definition);
        deviceValidator.validate(plan);
        AdbRiskAssessment assessment = riskClassifier.assess(plan);
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
        AdbCommandPlan plan = confirmationService.consumePlan(token, conversationId);
        deviceValidator.validate(plan);
        AdbRiskAssessment assessment = riskClassifier.assess(plan);
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
        return AdbMcpToolResult.canceled("已取消敏感 ADB 操作。").withToolMetadata("ADB MCP", "");
    }

    /**
     * 取消运行中工具。
     *
     * @param requestId 请求 ID
     * @return 取消结果
     */
    public AdbMcpToolResult cancel(String requestId) {
        ensureEnabled();
        return commandExecutor.cancel(requestId).withToolMetadata("ADB MCP", "cancel " + requestId);
    }

    /**
     * 以 SSE 形式调用工具；第一阶段复用非流式执行结果，事件契约保持稳定。
     *
     * @param request 工具请求
     * @return SSE Emitter
     */
    public SseEmitter streamCall(AdbMcpToolRequest request) {
        try {
            StreamPlan streamPlan = streamPlan(request);
            if (streamPlan.result() != null) {
                SseEmitter emitter = new SseEmitter(requestTimeoutMillis(request));
                send(emitter, eventName(streamPlan.result()), streamPlan.result());
                emitter.complete();
                return emitter;
            }
            return commandExecutor.executeStreaming(streamPlan.plan(), streamPlan.assessment().riskLevel());
        } catch (BusinessException ex) {
            SseEmitter emitter = new SseEmitter(60_000L);
            send(emitter, "tool-error", AdbMcpToolResult.failed(ex.getErrorCode(), ex.getMessage(), ex.getDetail(), null, AdbRiskLevel.LOW)
                    .withToolMetadata("ADB MCP", ""));
            emitter.complete();
            return emitter;
        }
    }

    /**
     * 为流式调用准备命令计划；敏感操作仍先返回确认结果。
     *
     * @param request 工具请求
     * @return 流式计划
     */
    private StreamPlan streamPlan(AdbMcpToolRequest request) {
        ensureEnabled();
        validateRequest(request);
        AdbMcpToolDefinition definition = catalog.requireTool(request.toolName());
        AdbCommandPlan plan = planner.plan(request, definition);
        deviceValidator.validate(plan);
        AdbRiskAssessment assessment = riskClassifier.assess(plan);
        if (assessment.confirmationRequired() && !StringUtils.hasText(request.confirmationToken())) {
            return new StreamPlan(plan, assessment, confirmationResult(plan, assessment));
        }
        if (StringUtils.hasText(request.confirmationToken())) {
            verifyConfirmation(request.confirmationToken(), plan, assessment.riskLevel());
        }
        return new StreamPlan(plan, assessment, null);
    }

    /**
     * 创建确认结果，确保真实命令不在确认前执行。
     *
     * @param plan 命令计划
     * @param assessment 风险评估
     * @return 待确认结果
     */
    private AdbMcpToolResult confirmationResult(AdbCommandPlan plan, AdbRiskAssessment assessment) {
        AdbConfirmationChallenge challenge = confirmationService.create(new AdbConfirmationRequest(
                plan,
                assessment,
                properties.getAiMcpAdb().getConfirmationTtl()));
        String message = "敏感 ADB 操作需要确认。命令：" + challenge.commandSummary()
                + "；设备：" + challenge.deviceSerialMasked()
                + "；风险：" + challenge.reason()
                + "；影响：" + challenge.impact()
                + "；有效期：" + challenge.expiresAt();
        audit(plan, assessment.riskLevel(), AdbConfirmationStatus.PENDING, 0, null, true, "pending confirmation");
        return AdbMcpToolResult.confirmationRequired(challenge.token(), message, assessment.riskLevel())
                .withToolMetadata("ADB MCP", "adb " + challenge.commandSummary());
    }

    /**
     * 校验模型二次调用携带的确认令牌。
     *
     * @param token 确认令牌
     * @param plan 命令计划
     * @param riskLevel 风险级别
     */
    private void verifyConfirmation(String token, AdbCommandPlan plan, AdbRiskLevel riskLevel) {
        confirmationService.verifyAndConsume(token, new AdbConfirmationCheck(
                plan.conversationId(),
                plan.deviceSerial(),
                confirmationService.argsHash(plan),
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
            AdbCommandPlan plan,
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
            AdbCommandPlan plan,
            AdbRiskLevel riskLevel,
            AdbConfirmationStatus confirmationStatus,
            long durationMillis,
            Integer exitCode,
            boolean success,
            String error) {
        auditRecorder.record(new AdbToolAuditEvent(
                plan.toolName(),
                plan.deviceSerial(),
                plan.argumentSummary(),
                riskLevel,
                confirmationStatus,
                durationMillis,
                exitCode,
                success,
                error));
    }

    /**
     * 校验 ADB MCP 开关。
     */
    private void ensureEnabled() {
        if (!properties.getAiMcpAdb().isEnabled()) {
            throw new BusinessException("AI_MCP_ADB_DISABLED", "ADB MCP 工具未启用", HttpStatus.CONFLICT, "");
        }
    }

    /**
     * 校验工具请求基础字段。
     *
     * @param request 工具请求
     */
    private void validateRequest(AdbMcpToolRequest request) {
        if (request == null || !StringUtils.hasText(request.toolName())) {
            throw new BusinessException("ADB_COMMAND_EMPTY", "ADB MCP 工具名不能为空", HttpStatus.BAD_REQUEST, "");
        }
        if (!StringUtils.hasText(request.conversationId())) {
            throw new BusinessException("ADB_CONVERSATION_EMPTY", "ADB MCP 对话 ID 不能为空", HttpStatus.BAD_REQUEST, "");
        }
    }

    /**
     * 计算流式调用超时时间。
     *
     * @param request 工具请求
     * @return 超时毫秒
     */
    private long requestTimeoutMillis(AdbMcpToolRequest request) {
        return request == null ? 60_000L : catalog.requireTool(request.toolName()).timeout().toMillis() + 5_000L;
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
    private record StreamPlan(AdbCommandPlan plan, AdbRiskAssessment assessment, AdbMcpToolResult result) {
    }
}
