package com.devbridge.server.ai.tool.gateway;

import com.devbridge.server.ai.agent.checkpoint.AgentToolCallCheckpoint;
import com.devbridge.server.ai.agent.checkpoint.AgentToolCallCheckpointStatus;
import com.devbridge.server.ai.agent.runtime.AgentCancellationHandleType;
import com.devbridge.server.ai.agent.runtime.AgentCancellationRegistration;
import com.devbridge.server.ai.agent.runtime.AgentResourceLease;
import com.devbridge.server.ai.agent.runtime.AgentResourceLockManager;
import com.devbridge.server.ai.agent.runtime.AgentResourceRequest;
import com.devbridge.server.ai.agent.runtime.AgentStepIdempotencyService;
import com.devbridge.server.ai.agent.runtime.AgentTaskCancellationCoordinator;
import com.devbridge.server.ai.agent.runtime.AgentToolExecutionDecision;
import com.devbridge.server.ai.agent.runtime.AgentToolExecutionRequest;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.model.BusinessException;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallStatus;
import com.devbridge.server.ai.tool.gateway.ToolContract.Definition;
import com.devbridge.server.ai.tool.gateway.ToolContract.Diagnostics;
import com.devbridge.server.ai.tool.gateway.ToolContract.Error;
import com.devbridge.server.ai.tool.gateway.ToolContract.ErrorCategory;
import com.devbridge.server.ai.tool.gateway.ToolContract.Exit;
import com.devbridge.server.ai.tool.gateway.ToolContract.Metrics;
import com.devbridge.server.ai.tool.gateway.ToolContract.ResultPayload;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskDecision;
import com.devbridge.server.ai.tool.gateway.ToolContract.SideEffect;
import com.devbridge.server.ai.tool.gateway.ToolContract.Timing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 统一工具执行流水线，负责幂等预留、资源锁、Adapter 执行、脱敏和完成状态持久化。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class ToolExecutionPipeline {

    private static final Duration RESOURCE_WAIT_TIMEOUT = Duration.ofSeconds(5);

    private final AgentStepIdempotencyService idempotencyService;
    private final AgentResourceLockManager resourceLockManager;
    private final SensitiveDataMasker masker;
    private final AgentTaskCancellationCoordinator cancellationCoordinator;

    /**
     * 注入幂等、资源锁和脱敏依赖。
     *
     * @param idempotencyService Agent 幂等服务
     * @param resourceLockManager 资源锁管理器
     * @param masker 敏感数据脱敏器
     */
    @Autowired
    public ToolExecutionPipeline(
            AgentStepIdempotencyService idempotencyService,
            AgentResourceLockManager resourceLockManager,
            SensitiveDataMasker masker,
            AgentTaskCancellationCoordinator cancellationCoordinator) {
        this.idempotencyService = idempotencyService;
        this.resourceLockManager = resourceLockManager;
        this.masker = masker;
        this.cancellationCoordinator = cancellationCoordinator;
    }

    /**
     * 创建兼容测试的执行流水线。
     *
     * @param idempotencyService Agent 幂等服务
     * @param resourceLockManager 资源锁管理器
     * @param masker 敏感数据脱敏器
     */
    public ToolExecutionPipeline(
            AgentStepIdempotencyService idempotencyService,
            AgentResourceLockManager resourceLockManager,
            SensitiveDataMasker masker) {
        this(idempotencyService, resourceLockManager, masker, new AgentTaskCancellationCoordinator());
    }

    /**
     * 在风险确认前加密保存原始工具请求，确保批准后不依赖模型重建参数。
     *
     * @param request 原始中立工具请求
     */
    public void rememberRequest(CallRequest request) {
        if (!StringUtils.hasText(request.identity().taskId())) {
            return;
        }
        idempotencyService.rememberRequest(new AgentToolExecutionRequest(
                request.identity().taskId(), request.identity().stepId(), request.identity().toolCallId(),
                "", request.argumentDigest(), false, idempotencyService.protect(request)));
    }

    /**
     * 执行已经通过策略的工具请求。
     *
     * @param registration 工具注册信息
     * @param request 工具请求
     * @param decision 风险决策
     * @return 最终工具结果
     */
    public CallResult execute(
            ToolRegistry.Registration registration,
            CallRequest request,
            RiskDecision decision) {
        Definition definition = registration.definition();
        if (decision.action() != ToolContract.RiskAction.ALLOW) {
            return policyResult(request, decision);
        }
        AgentToolExecutionDecision idempotency = reserve(request, definition);
        if (idempotency != null && !idempotency.execute()) {
            return duplicateResult(request, decision, idempotency.checkpoint());
        }
        List<AgentResourceRequest> resources = registration.adapter().resources(request, definition);
        AgentCancellationRegistration cancellation = registerCancellation(registration, request, definition);
        try (AgentResourceLease ignored = acquire(request, definition, resources)) {
            CallResult result = registration.adapter().execute(request, definition, decision);
            CallResult protectedResult = protectCredentials(result);
            complete(request, protectedResult);
            return protectedResult;
        } catch (RuntimeException ex) {
            CallResult failure = failure(request, decision, ex);
            complete(request, failure);
            return failure;
        } finally {
            if (cancellation != null) {
                cancellation.close();
            }
        }
    }

    /**
     * 为任务级调用执行幂等预留；兼容旧链路无 taskId 时跳过持久预留。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @return 幂等决策或 null
     */
    private AgentToolExecutionDecision reserve(CallRequest request, Definition definition) {
        if (!StringUtils.hasText(request.identity().taskId())) {
            return null;
        }
        String key = normalizedIdempotencyKey(request, definition);
        return idempotencyService.reserve(new AgentToolExecutionRequest(
                request.identity().taskId(),
                request.identity().stepId(),
                request.identity().toolCallId(),
                key,
                request.argumentDigest(),
                definition.metadata().accessMode() != ToolContract.AccessMode.READ,
                idempotencyService.protect(request)));
    }

    /**
     * 规范化幂等键，显式要求键的工具不允许缺失。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @return 有效幂等键
     */
    private String normalizedIdempotencyKey(CallRequest request, Definition definition) {
        if (StringUtils.hasText(request.idempotencyKey())) {
            return request.idempotencyKey().trim();
        }
        if (definition.metadata().idempotency().keyRequired()) {
            throw new IllegalArgumentException("工具必须提供幂等键: " + definition.identity().toolId());
        }
        return request.identity().toolCallId();
    }

    /**
     * 获取 Adapter 声明的全部资源锁；无资源时不创建租约。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @param resources 资源请求
     * @return 资源租约或空租约
     */
    private AgentResourceLease acquire(
            CallRequest request,
            Definition definition,
            List<AgentResourceRequest> resources) {
        if (resources == null || resources.isEmpty()) {
            return null;
        }
        String owner = StringUtils.hasText(request.identity().taskId())
                ? request.identity().taskId()
                : "legacy-" + request.identity().toolCallId();
        long maxTimeout = Math.max(
                definition.metadata().executionProfile().defaultTimeoutMs(),
                definition.metadata().executionProfile().maxTimeoutMs());
        return resourceLockManager.acquire(
                owner,
                resources,
                RESOURCE_WAIT_TIMEOUT,
                Duration.ofMillis(Math.max(1000L, maxTimeout)));
    }

    /**
     * 持久化任务级工具终态。
     *
     * @param request 工具请求
     * @param result 工具结果
     */
    private void complete(CallRequest request, CallResult result) {
        if (!StringUtils.hasText(request.identity().taskId())) {
            return;
        }
        idempotencyService.complete(
                request.identity().taskId(),
                request.identity().toolCallId(),
                checkpointStatus(result.status()),
                idempotencyService.protect(result),
                result.diagnostics().sideEffect().verified());
    }

    /**
     * 将通用工具状态映射为 Checkpoint 状态。
     *
     * @param status 工具状态
     * @return Checkpoint 状态
     */
    private AgentToolCallCheckpointStatus checkpointStatus(CallStatus status) {
        return switch (status) {
            case SUCCEEDED -> AgentToolCallCheckpointStatus.SUCCEEDED;
            case CANCELED -> AgentToolCallCheckpointStatus.CANCELED;
            case UNKNOWN -> AgentToolCallCheckpointStatus.UNKNOWN;
            default -> AgentToolCallCheckpointStatus.FAILED;
        };
    }

    /**
     * 构造无需执行的策略结果。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @return 等待确认或阻断结果
     */
    private CallResult policyResult(CallRequest request, RiskDecision decision) {
        CallStatus status = decision.action() == ToolContract.RiskAction.CONFIRM
                ? CallStatus.WAITING_CONFIRMATION
                : CallStatus.BLOCKED;
        String summary = status == CallStatus.WAITING_CONFIRMATION ? "工具调用等待用户确认" : "工具调用已被本地策略阻断";
        return result(request, decision, status, null, summary, null, false);
    }

    /**
     * 构造重复调用结果，避免再次执行已经保留终态的工具。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param checkpoint 已有工具快照
     * @return 复用结果
     */
    private CallResult duplicateResult(
            CallRequest request,
            RiskDecision decision,
            AgentToolCallCheckpoint checkpoint) {
        CallResult stored = StringUtils.hasText(checkpoint.resultReference())
                && !checkpoint.resultReference().startsWith("tool-result:")
                ? idempotencyService.restore(checkpoint.resultReference(), CallResult.class)
                : null;
        if (stored != null) {
            return stored;
        }
        CallStatus status = switch (checkpoint.status()) {
            case SUCCEEDED -> CallStatus.SUCCEEDED;
            case CANCELED -> CallStatus.CANCELED;
            case UNKNOWN -> CallStatus.UNKNOWN;
            default -> CallStatus.FAILED;
        };
        return result(request, decision, status, null, "工具调用已存在，未重复执行", null, checkpoint.sideEffectVerified());
    }

    /**
     * 将支持取消的工具绑定到当前 Agent Task 取消作用域。
     *
     * @param registration 工具注册信息
     * @param request 工具请求
     * @param definition 工具定义
     * @return 活动注册，无任务绑定或工具不支持取消时返回 null
     */
    private AgentCancellationRegistration registerCancellation(
            ToolRegistry.Registration registration,
            CallRequest request,
            Definition definition) {
        if (!StringUtils.hasText(request.identity().taskId())
                || !definition.metadata().executionProfile().supportsCancellation()) {
            return null;
        }
        return cancellationCoordinator.open(request.identity().taskId()).register(
                AgentCancellationHandleType.TOOL,
                request.identity().toolCallId(),
                () -> registration.adapter().cancel(request, definition));
    }

    /**
     * 保留 Adapter 返回的业务原文，仅清理认证凭据。
     *
     * @param result Adapter 原始结果
     * @return 已执行凭据保护的结果
     */
    private CallResult protectCredentials(CallResult result) {
        ResultPayload payload = result.payload();
        Error error = result.diagnostics().error();
        ResultPayload sanitizedPayload = new ResultPayload(
                protectJsonCredentials(payload.output()),
                masker.protectCredentials(payload.summary()),
                payload.artifacts());
        Error sanitizedError = error == null ? null : new Error(
                error.code(), error.category(), masker.protectCredentials(error.message()),
                masker.protectCredentials(error.detail()), error.retryable(), error.resultUncertain());
        Diagnostics diagnostics = new Diagnostics(
                sanitizedError, result.diagnostics().exit(), result.diagnostics().metrics(), result.diagnostics().sideEffect());
        return new CallResult(
                result.schemaVersion(), result.tool(), result.toolCallId(), result.status(),
                result.riskDecision(), result.timing(), sanitizedPayload, diagnostics);
    }

    /**
     * 递归保留 JSON 业务字段并清理认证凭据。
     *
     * @param value 原始 JSON
     * @return 已执行凭据保护的 JSON
     */
    private JsonNode protectJsonCredentials(JsonNode value) {
        if (value == null || value.isNull()) {
            return value;
        }
        if (value.isTextual()) {
            return com.fasterxml.jackson.databind.node.TextNode.valueOf(masker.protectCredentials(value.asText()));
        }
        JsonNode copy = value.deepCopy();
        if (copy instanceof ObjectNode object) {
            object.fields().forEachRemaining(entry -> object.set(entry.getKey(), protectJsonCredentials(entry.getValue())));
        } else if (copy instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                array.set(index, protectJsonCredentials(array.get(index)));
            }
        }
        return copy;
    }

    /**
     * 构造 Adapter 执行异常结果。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param error 原始异常
     * @return 失败结果
     */
    private CallResult failure(CallRequest request, RiskDecision decision, RuntimeException error) {
        String code = "TOOL_EXECUTION_FAILED";
        String message = "工具执行失败";
        String diagnostic = error.getMessage();
        if (error instanceof BusinessException business) {
            // 业务异常已经定义了稳定错误语义，不能在 Gateway 中降级成无法诊断的通用错误。
            code = business.getErrorCode();
            message = business.getMessage();
            diagnostic = business.getDetail();
        }
        Error detail = new Error(
                code,
                ErrorCategory.EXECUTION,
                masker.protectCredentials(message),
                masker.protectCredentials(diagnostic),
                false,
                false);
        return result(request, decision, CallStatus.FAILED, null, detail.message(), detail, false);
    }

    /**
     * 构造统一结果对象。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param status 结果状态
     * @param output 输出 JSON
     * @param summary 摘要
     * @param error 错误
     * @param sideEffectVerified 副作用是否验证
     * @return 工具结果
     */
    private CallResult result(
            CallRequest request,
            RiskDecision decision,
            CallStatus status,
            JsonNode output,
            String summary,
            Error error,
            boolean sideEffectVerified) {
        Instant now = Instant.now();
        return new CallResult(
                ToolContract.SCHEMA_VERSION,
                request.tool(),
                request.identity().toolCallId(),
                status,
                decision,
                new Timing(null, now, 0),
                new ResultPayload(output, summary, List.of()),
                new Diagnostics(
                        error,
                        new Exit(null, false),
                        new Metrics(0, 0, 0, 0),
                        new SideEffect(false, sideEffectVerified, false)));
    }
}
