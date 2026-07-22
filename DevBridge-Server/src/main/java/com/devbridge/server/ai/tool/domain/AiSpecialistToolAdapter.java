package com.devbridge.server.ai.tool.domain;

import com.devbridge.server.ai.agent.runtime.AiAgentRegistry;
import com.devbridge.server.ai.agent.runtime.AiSpecialistAgentService;
import com.devbridge.server.ai.agent.runtime.AiVerificationAgentService;
import com.devbridge.server.ai.agent.runtime.AgentResourceRequest;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.ai.tool.gateway.ToolAdapter;
import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.AccessMode;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallStatus;
import com.devbridge.server.ai.tool.gateway.ToolContract.Definition;
import com.devbridge.server.ai.tool.gateway.ToolContract.Deprecation;
import com.devbridge.server.ai.tool.gateway.ToolContract.Diagnostics;
import com.devbridge.server.ai.tool.gateway.ToolContract.Error;
import com.devbridge.server.ai.tool.gateway.ToolContract.ErrorCategory;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionProfile;
import com.devbridge.server.ai.tool.gateway.ToolContract.Exit;
import com.devbridge.server.ai.tool.gateway.ToolContract.Idempotency;
import com.devbridge.server.ai.tool.gateway.ToolContract.IdempotencyMode;
import com.devbridge.server.ai.tool.gateway.ToolContract.Identity;
import com.devbridge.server.ai.tool.gateway.ToolContract.Metadata;
import com.devbridge.server.ai.tool.gateway.ToolContract.Metrics;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.ResultPayload;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskAction;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskDecision;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskLevel;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskProfile;
import com.devbridge.server.ai.tool.gateway.ToolContract.SideEffect;
import com.devbridge.server.ai.tool.gateway.ToolContract.Source;
import com.devbridge.server.ai.tool.gateway.ToolContract.SourceKind;
import com.devbridge.server.ai.tool.gateway.ToolContract.Timing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 四个专业 Agent 的高层工具适配器，统一 Schema 和结果，不暴露底层工具 ID。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AiSpecialistToolAdapter implements ToolAdapter {

    private static final Map<String, String> AGENTS = agents();

    private final AiSpecialistAgentService service;
    private final AiAgentRegistry registry;
    private final ObjectMapper objectMapper;
    private final AiVerificationAgentService verificationService;
    private final DevBridgeProperties properties;

    /** 注入专业 Agent 服务、Registry 和 JSON 工具。 */
    public AiSpecialistToolAdapter(
            AiSpecialistAgentService service,
            AiAgentRegistry registry,
            ObjectMapper objectMapper,
            AiVerificationAgentService verificationService,
            DevBridgeProperties properties) {
        this.service = service;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.verificationService = verificationService;
        this.properties = properties;
    }

    /** 返回 Device、Log、App 和 Local 四个专业 Agent 工具。 */
    @Override
    public List<Definition> definitions() {
        if (!properties.getAiFeatures().isMultiAgentEnabled()) {
            return List.of();
        }
        return AGENTS.entrySet().stream()
                .map(entry -> definition(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** 按实际 Operation 计算风险，中高风险在专业 Agent 入口确认。 */
    @Override
    public RiskDecision assess(CallRequest request, Definition definition) {
        String agentId = requireAgentId(request.tool().toolId());
        if (AiAgentRegistry.VERIFICATION_AGENT.equals(agentId)) {
            return new RiskDecision(
                    RiskLevel.LOW, RiskAction.ALLOW, "verification-agent-policy",
                    "READ_ONLY_VERIFICATION", "Verification Agent 只执行只读证据检查",
                    "", Instant.now());
        }
        RiskLevel level = service.risk(agentId, request.arguments());
        boolean confirmation = level != RiskLevel.LOW;
        return new RiskDecision(
                level, confirmation ? RiskAction.CONFIRM : RiskAction.ALLOW,
                "specialist-agent-policy",
                confirmation ? "SPECIALIST_OPERATION_CONFIRM" : "SPECIALIST_OPERATION_ALLOWED",
                confirmation ? service.confirmationSummary(agentId, request.arguments())
                        : "专业 Agent 低风险领域操作",
                "", Instant.now());
    }

    /** 底层领域工具自行申请资源，专业 Agent 父调用不重复持锁。 */
    @Override
    public List<AgentResourceRequest> resources(CallRequest request, Definition definition) {
        return List.of();
    }

    /** 执行专业 Agent 并把 Worker 结构化结果映射为统一工具结果。 */
    @Override
    public CallResult execute(CallRequest request, Definition definition, RiskDecision decision) {
        Instant started = Instant.now();
        String agentId = requireAgentId(request.tool().toolId());
        JsonNode output = AiAgentRegistry.VERIFICATION_AGENT.equals(agentId)
                ? verificationService.verify(request) : service.execute(request, agentId);
        CallStatus status = AiAgentRegistry.VERIFICATION_AGENT.equals(agentId)
                ? CallStatus.SUCCEEDED : status(output.path("status").asText("FAILED"));
        Instant finished = Instant.now();
        Error error = status == CallStatus.SUCCEEDED ? null : new Error(
                "SPECIALIST_AGENT_FAILED", ErrorCategory.EXECUTION,
                "专业 Agent 执行失败", output.path("error").asText(""), false, false);
        return new CallResult(
                ToolContract.SCHEMA_VERSION, request.tool(), request.identity().toolCallId(), status,
                decision, new Timing(started, finished, finished.toEpochMilli() - started.toEpochMilli()),
                new ResultPayload(output, output.path("summary").asText("专业 Agent 执行完成"), List.of()),
                new Diagnostics(error, new Exit(status == CallStatus.SUCCEEDED ? 0 : 1, false),
                        new Metrics(0, output.toString().length(), 0, 0),
                        new SideEffect(decision.level() != RiskLevel.LOW,
                                status == CallStatus.SUCCEEDED, false)));
    }

    /** 子工具已注册到同一任务取消域，无需维护第二份取消句柄。 */
    @Override
    public void cancel(CallRequest request, Definition definition) {
        // 同一 taskId 下的领域工具由 AgentTaskCancellationCoordinator 统一取消。
    }

    /** 创建单个专业 Agent 高层工具定义。 */
    private Definition definition(String toolId, String agentId) {
        var agent = registry.find(agentId)
                .orElseThrow(() -> new IllegalStateException("专业 Agent 未注册: " + agentId));
        AccessMode accessMode = AiAgentRegistry.DEVICE_AGENT.equals(agentId)
                || AiAgentRegistry.VERIFICATION_AGENT.equals(agentId)
                ? AccessMode.READ : AccessMode.CONTROL;
        Metadata metadata = new Metadata(
                new Source(SourceKind.BUILT_IN, "specialist-agent", "1.0.0", "", "IN_PROCESS"),
                List.copyOf(agent.toolPolicy().entryCapabilities()),
                List.of(Platform.PLATFORM_INDEPENDENT), accessMode,
                new Idempotency(IdempotencyMode.KEYED, true, ""),
                new RiskProfile(RiskLevel.LOW, true),
                new ExecutionProfile(60_000, 600_000, 128 * 1024, true, false,
                        List.of("device", "workspace")));
        return new Definition(
                ToolContract.SCHEMA_VERSION,
                new Identity(toolId, agent.identity().displayName(), agent.identity().instruction()),
                metadata, inputSchema(agentId), outputSchema(agentId), true, new Deprecation(false, ""));
    }

    /** 创建严格 Operation 输入 Schema。 */
    private ObjectNode inputSchema(String agentId) {
        if (AiAgentRegistry.VERIFICATION_AGENT.equals(agentId)) {
            return verificationInputSchema();
        }
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode operation = objectMapper.createObjectNode().put("type", "string");
        operation.set("enum", objectMapper.valueToTree(service.operations(agentId)));
        properties.set("operation", operation);
        properties.set("arguments", objectMapper.createObjectNode()
                .put("type", "object").put("additionalProperties", true));
        schema.set("properties", properties);
        schema.set("required", objectMapper.createArrayNode().add("operation").add("arguments"));
        return schema;
    }

    /** 创建供 Supervisor 汇总的统一输出 Schema。 */
    private ObjectNode outputSchema(String agentId) {
        if (AiAgentRegistry.VERIFICATION_AGENT.equals(agentId)) {
            ObjectNode schema = objectMapper.createObjectNode()
                    .put("type", "object").put("additionalProperties", false);
            ObjectNode properties = objectMapper.createObjectNode();
            for (String field : List.of("agentId", "status", "summary")) {
                properties.set(field, objectMapper.createObjectNode().put("type", "string"));
            }
            properties.set("checks", objectMapper.createObjectNode().put("type", "array"));
            properties.set("claims", objectMapper.createObjectNode().put("type", "array"));
            schema.set("properties", properties);
            schema.set("required", objectMapper.createArrayNode()
                    .add("agentId").add("status").add("summary").add("checks").add("claims"));
            return schema;
        }
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = objectMapper.createObjectNode();
        for (String field : List.of("agentId", "operation", "toolId", "status", "summary", "error")) {
            properties.set(field, objectMapper.createObjectNode().put("type", "string"));
        }
        properties.set("evidence", objectMapper.createObjectNode().put("type", "object"));
        schema.set("properties", properties);
        schema.set("required", objectMapper.createArrayNode()
                .add("agentId").add("operation").add("toolId").add("status").add("summary").add("error").add("evidence"));
        return schema;
    }

    /** 创建 Verification Agent 的只读检查和结论引用 Schema。 */
    private ObjectNode verificationInputSchema() {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode check = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode checkProperties = objectMapper.createObjectNode();
        for (String field : List.of("checkId", "agentId", "operation")) {
            checkProperties.set(field, objectMapper.createObjectNode().put("type", "string").put("minLength", 1));
        }
        checkProperties.set("arguments", objectMapper.createObjectNode().put("type", "object"));
        check.set("properties", checkProperties);
        check.set("required", objectMapper.createArrayNode().add("checkId").add("agentId").add("operation").add("arguments"));
        ObjectNode claim = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode claimProperties = objectMapper.createObjectNode();
        claimProperties.set("claimId", objectMapper.createObjectNode().put("type", "string").put("minLength", 1));
        claimProperties.set("claim", objectMapper.createObjectNode().put("type", "string").put("minLength", 1));
        claimProperties.set("evidenceRefs", objectMapper.createObjectNode().put("type", "array")
                .put("minItems", 1).put("maxItems", 8)
                .set("items", objectMapper.createObjectNode().put("type", "string")));
        claim.set("properties", claimProperties);
        claim.set("required", objectMapper.createArrayNode().add("claimId").add("claim").add("evidenceRefs"));
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("checks", objectMapper.createObjectNode().put("type", "array")
                .put("minItems", 1).put("maxItems", 8).set("items", check));
        properties.set("claims", objectMapper.createObjectNode().put("type", "array")
                .put("minItems", 1).put("maxItems", 8).set("items", claim));
        schema.set("properties", properties);
        schema.set("required", objectMapper.createArrayNode().add("checks").add("claims"));
        return schema;
    }

    /** 将 Worker 状态文本转换为中立工具状态。 */
    private CallStatus status(String value) {
        try {
            return CallStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return CallStatus.FAILED;
        }
    }

    /** 获取高层工具绑定的 Agent ID。 */
    private String requireAgentId(String toolId) {
        String agentId = AGENTS.get(toolId);
        if (agentId == null) {
            throw new IllegalArgumentException("未知专业 Agent 工具: " + toolId);
        }
        return agentId;
    }

    /** 创建四个高层工具和 Agent 的稳定绑定。 */
    private static Map<String, String> agents() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("agent.device.execute", AiAgentRegistry.DEVICE_AGENT);
        values.put("agent.log.execute", AiAgentRegistry.DOMAIN_LOG_AGENT);
        values.put("agent.app.execute", AiAgentRegistry.APP_AGENT);
        values.put("agent.local.execute", AiAgentRegistry.LOCAL_AGENT);
        values.put("agent.verification.execute", AiAgentRegistry.VERIFICATION_AGENT);
        return Map.copyOf(values);
    }
}
