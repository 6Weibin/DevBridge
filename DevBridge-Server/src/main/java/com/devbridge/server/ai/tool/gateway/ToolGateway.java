package com.devbridge.server.ai.tool.gateway;

import com.devbridge.server.ai.agent.event.AgentEventContext;
import com.devbridge.server.ai.agent.event.AgentEventRequest;
import com.devbridge.server.ai.agent.event.AgentEventScope;
import com.devbridge.server.ai.agent.event.AgentEventSequencer;
import com.devbridge.server.ai.agent.event.AgentEventType;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.runtime.AgentTaskService;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityMetadata;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityQuery;
import com.devbridge.server.ai.tool.gateway.ToolContract.Definition;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskDecision;
import com.devbridge.server.ai.tool.audit.ToolAuditStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 统一 Tool Gateway，对外提供工具发现和单一调用入口。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class ToolGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolGateway.class);

    private final ToolRegistry registry;
    private final ToolPolicyPipeline policyPipeline;
    private final ToolExecutionPipeline executionPipeline;
    private final AgentTaskService taskService;
    private final AgentEventSequencer eventSequencer;
    private final ToolAuditStore auditStore;

    /**
     * 注入注册表、策略、执行和事件依赖。
     *
     * @param registry 工具注册表
     * @param policyPipeline 策略流水线
     * @param executionPipeline 执行流水线
     * @param taskService Agent 任务服务
     * @param eventSequencer Agent 事件序列器
     * @param auditStore 结构化工具审计 Store
     */
    public ToolGateway(
            ToolRegistry registry,
            ToolPolicyPipeline policyPipeline,
            ToolExecutionPipeline executionPipeline,
            AgentTaskService taskService,
            AgentEventSequencer eventSequencer,
            ToolAuditStore auditStore) {
        this.registry = registry;
        this.policyPipeline = policyPipeline;
        this.executionPipeline = executionPipeline;
        this.taskService = taskService;
        this.eventSequencer = eventSequencer;
        this.auditStore = auditStore;
    }

    /**
     * 返回当前启用且可发现的工具定义。
     *
     * @return 工具定义
     */
    public List<Definition> listTools() {
        return registry.definitions();
    }

    /**
     * 查询符合平台、能力和访问模式约束的工具能力元数据。
     *
     * @param query 查询条件
     * @return 能力元数据
     */
    public List<CapabilityMetadata> listCapabilities(CapabilityQuery query) {
        return registry.capabilities(query);
    }

    /**
     * 通过固定策略和执行流水线调用工具。
     *
     * @param request 工具请求
     * @return 工具结果
     */
    public CallResult call(CallRequest request) {
        CallRequest executableRequest = null;
        try {
            executableRequest = registry.prepare(request);
            ToolRegistry.Registration registration = registry.require(executableRequest.tool().toolId());
            auditRequested(executableRequest);
            publish(executableRequest, AgentEventType.TOOL_REQUESTED, Map.of(
                    "toolId", executableRequest.tool().toolId(),
                    "schemaVersion", executableRequest.tool().schemaVersion()));
            // 风险策略可能把任务切换为等待确认，必须在此之前保存原始请求以支持确定性恢复。
            executionPipeline.rememberRequest(executableRequest);
            ToolPolicyPipeline.PolicyOutcome policy = policyPipeline.evaluate(registration, executableRequest);
            publishPolicy(executableRequest, policy.decision());
            if (policy.decision().action() == ToolContract.RiskAction.ALLOW) {
                // 预算恢复以真实执行开始事件计数，等待确认和策略阻断不得消耗执行预算。
                publish(executableRequest, AgentEventType.TOOL_STARTED, Map.of(
                        "toolId", executableRequest.tool().toolId()));
            }
            CallResult result = executionPipeline.execute(registration, executableRequest, policy.decision());
            publishResult(executableRequest, result);
            auditResult(executableRequest, result);
            return result;
        } catch (RuntimeException ex) {
            auditRejected(executableRequest == null ? request : executableRequest, ex);
            throw ex;
        }
    }

    /**
     * 持久化工具请求审计；测试兼容实例未注入 Store 时跳过。
     *
     * @param request 工具请求
     */
    private void auditRequested(CallRequest request) {
        if (auditStore != null) {
            auditStore.recordRequested(request);
        }
    }

    /**
     * 持久化工具终态审计。
     *
     * @param request 工具请求
     * @param result 工具结果
     */
    private void auditResult(CallRequest request, CallResult result) {
        if (auditStore != null) {
            try {
                auditStore.recordResult(request, result);
            } catch (RuntimeException ex) {
                // 工具可能已经产生副作用，终态审计失败不能诱导上层重试同一写操作。
                LOGGER.error("工具终态审计写入失败, toolCallId={}", request.identity().toolCallId());
            }
        }
    }

    /**
     * 持久化执行前拒绝审计，避免异常分支形成观测盲区。
     *
     * @param request 工具请求
     * @param error 拒绝异常
     */
    private void auditRejected(CallRequest request, RuntimeException error) {
        if (auditStore != null && request != null) {
            try {
                auditStore.recordRejected(request, error);
            } catch (RuntimeException auditError) {
                // 保留原始拒绝异常，审计错误只进入本地诊断日志。
                LOGGER.error("工具拒绝审计写入失败, toolCallId={}",
                        request.identity() == null ? "" : request.identity().toolCallId());
            }
        }
    }

    /**
     * 发布工具策略事件。
     *
     * @param request 工具请求
     * @param decision 风险决策
     */
    private void publishPolicy(CallRequest request, RiskDecision decision) {
        publish(request, AgentEventType.TOOL_POLICY_DECIDED, Map.of(
                "toolId", request.tool().toolId(),
                "riskLevel", decision.level().name(),
                "action", decision.action().name(),
                "reasonCode", decision.reasonCode(),
                "confirmationId", safe(decision.confirmationId())));
    }

    /**
     * 根据最终结果发布对应工具事件。
     *
     * @param request 工具请求
     * @param result 工具结果
     */
    private void publishResult(CallRequest request, CallResult result) {
        AgentEventType type = switch (result.status()) {
            case SUCCEEDED -> AgentEventType.TOOL_COMPLETED;
            case WAITING_CONFIRMATION -> AgentEventType.TOOL_CONFIRMATION_REQUIRED;
            case BLOCKED -> AgentEventType.TOOL_BLOCKED;
            case CANCELED -> AgentEventType.TOOL_CANCELED;
            default -> AgentEventType.TOOL_FAILED;
        };
        publish(request, type, Map.of(
                "toolId", request.tool().toolId(),
                "status", result.status().name(),
                "summary", result.payload().summary()));
    }

    /**
     * 将工具事件写入任务级有序事件流；兼容旧链路无 taskId 时跳过。
     *
     * @param request 工具请求
     * @param eventType 事件类型
     * @param payload 事件载荷
     */
    private void publish(CallRequest request, AgentEventType eventType, Map<String, Object> payload) {
        if (!StringUtils.hasText(request.identity().taskId())) {
            return;
        }
        AgentTask task = taskService.findTask(request.identity().taskId()).orElse(null);
        if (task == null) {
            return;
        }
        AgentEventContext context = new AgentEventContext(
                task.conversationId(), request.identity().turnId(), request.identity().stepId(),
                request.identity().toolCallId(), null, null, task.version());
        eventSequencer.publish(task.taskId(), new AgentEventRequest(
                eventType,
                AgentEventScope.TOOL_CALL,
                context,
                payload,
                Instant.now(),
                "tool-gateway"));
    }

    /**
     * 将空文本规范化为空字符串。
     *
     * @param value 原始文本
     * @return 非空文本
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }
}
