package com.devbridge.server.ai.agent.runtime;

import com.devbridge.server.ai.agent.event.AgentEventContext;
import com.devbridge.server.ai.agent.event.AgentEventRequest;
import com.devbridge.server.ai.agent.event.AgentEventScope;
import com.devbridge.server.ai.agent.event.AgentEventSequencer;
import com.devbridge.server.ai.agent.event.AgentEventType;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.runtime.AiAgentRegistry.AgentDefinition;
import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallStatus;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityMetadata;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityQuery;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskLevel;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.devbridge.server.ai.tool.gateway.ToolContract.WorkflowAuthorization;
import com.devbridge.server.ai.tool.gateway.ToolGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Supervisor Agent，按有界结构化计划委派后端注册 Worker 并汇总确定性结果。
 *
 * <p>Supervisor 不直接持有设备或本机工具权限，所有 Worker 调用仍进入统一 Tool Gateway。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AiSupervisorAgentService {

    private static final int MAX_STEPS = 8;

    private final ObjectProvider<ToolGateway> toolGateway;
    private final ObjectMapper objectMapper;
    private final AiAgentRegistry agentRegistry;
    private final AgentTaskService taskService;
    private final AgentEventSequencer eventSequencer;

    /**
     * 注入 Registry、Tool Gateway 和现有任务事件控制面。
     *
     * @param toolGateway 延迟获取 Tool Gateway，避免工具注册循环依赖
     * @param objectMapper JSON 工具
     * @param agentRegistry Agent 注册表
     * @param taskService Agent Task 服务
     * @param eventSequencer 有序事件服务
     */
    public AiSupervisorAgentService(
            ObjectProvider<ToolGateway> toolGateway,
            ObjectMapper objectMapper,
            AiAgentRegistry agentRegistry,
            AgentTaskService taskService,
            AgentEventSequencer eventSequencer) {
        this.toolGateway = toolGateway;
        this.objectMapper = objectMapper;
        this.agentRegistry = agentRegistry;
        this.taskService = taskService;
        this.eventSequencer = eventSequencer;
    }

    /**
     * 执行结构化委派计划并生成 Worker 结果汇总。
     *
     * @param parent Supervisor 父工具请求
     * @return 结构化执行报告
     */
    public JsonNode execute(CallRequest parent) {
        List<SupervisorStep> steps = parseSteps(parent.arguments());
        publish(parent, AgentEventType.PLAN_CREATED, parent.identity().stepId(),
                parent.identity().toolCallId(), Map.of("steps", steps.size()));
        ArrayNode results = objectMapper.createArrayNode();
        int succeeded = 0;
        for (SupervisorStep step : steps) {
            CallResult result = executeStep(parent, step);
            results.add(workerResult(step, result));
            if (result.status() == CallStatus.SUCCEEDED) {
                succeeded++;
            } else if (!step.continueOnFailure()) {
                break;
            }
        }
        String status = succeeded == steps.size()
                ? "SUCCEEDED" : succeeded > 0 ? "PARTIAL" : "FAILED";
        return objectMapper.createObjectNode()
                .put("agent", "supervisor-agent")
                .put("status", status)
                .put("failure", failureSummary(results))
                .put("totalSteps", steps.size())
                .put("executedSteps", results.size())
                .put("succeededSteps", succeeded)
                .set("workerResults", results);
    }

    /**
     * 返回计划中全部 Worker 的最高风险，供父工具入口执行一次确认。
     *
     * @param arguments Supervisor 参数
     * @return 最高风险
     */
    public RiskLevel maximumRisk(JsonNode arguments) {
        RiskLevel maximum = RiskLevel.LOW;
        for (SupervisorStep step : parseSteps(arguments)) {
            AgentDefinition definition = requireAgent(step.agentId());
            if (riskOrder(definition.toolPolicy().maximumRisk()) > riskOrder(maximum)) {
                maximum = definition.toolPolicy().maximumRisk();
            }
        }
        return maximum;
    }

    /** 返回当前可委派 Agent ID。 */
    public List<String> agentIds() {
        return agentRegistry.definitions().stream()
                .map(value -> value.identity().agentId())
                .toList();
    }

    /** 执行单个 Worker，并发布步骤开始和终态事件。 */
    private CallResult executeStep(CallRequest parent, SupervisorStep step) {
        AgentDefinition agent = requireAgent(step.agentId());
        CapabilityMetadata tool = resolveTool(agent);
        CallRequest child = childRequest(parent, step, agent, tool);
        publish(parent, AgentEventType.STEP_STARTED, child.identity().stepId(), child.identity().toolCallId(),
                Map.of("agentId", step.agentId(), "toolId", tool.toolId()));
        CallResult result = requireGateway().call(child);
        AgentEventType type = result.status() == CallStatus.SUCCEEDED
                ? AgentEventType.STEP_COMPLETED : AgentEventType.STEP_FAILED;
        publish(parent, type, child.identity().stepId(), child.identity().toolCallId(),
                Map.of("agentId", step.agentId(), "toolId", tool.toolId(),
                        "status", result.status().name(), "summary", resultSummary(result)));
        return result;
    }

    /** 将 Registry 中的 Worker 映射为唯一高层工具。 */
    private CapabilityMetadata resolveTool(AgentDefinition agent) {
        List<CapabilityMetadata> matches = requireGateway().listCapabilities(new CapabilityQuery(
                null, new ArrayList<>(agent.toolPolicy().entryCapabilities()), null, false));
        List<CapabilityMetadata> authorized = agentRegistry.authorizedTools(agent.identity().agentId(), matches);
        if (authorized.size() != 1) {
            throw new IllegalStateException(
                    "Worker Agent 必须且只能映射一个授权工具: " + agent.identity().agentId());
        }
        return authorized.get(0);
    }

    /** 构造绑定父确认和稳定幂等标识的 Worker 子请求。 */
    private CallRequest childRequest(
            CallRequest parent,
            SupervisorStep step,
            AgentDefinition agent,
            CapabilityMetadata tool) {
        Platform platform = targetPlatform(step.arguments(), parent.executionContext().platform());
        if (!agent.platforms().contains(platform)) {
            throw new IllegalArgumentException(
                    "Worker Agent 不支持目标平台: " + step.agentId() + "/" + platform);
        }
        String suffix = shortHash(parent.identity().toolCallId() + ":" + step.stepId()
                + ":" + step.agentId() + ":" + step.arguments());
        WorkflowAuthorization authorization = new WorkflowAuthorization(
                parent.tool().toolId(), parent.identity().stepId(),
                parent.identity().toolCallId(), parent.argumentDigest());
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity(
                        parent.identity().conversationId(), parent.identity().taskId(), parent.identity().turnId(),
                        "supervisor-step-" + step.stepId() + "-" + suffix,
                        "supervisor-call-" + step.stepId() + "-" + suffix, Instant.now()),
                new ToolReference(tool.toolId(), tool.schemaVersion()), step.arguments().deepCopy(),
                digest(step.arguments()), "supervisor:" + parent.identity().toolCallId() + ":" + step.stepId(),
                Caller.WORKFLOW,
                new ExecutionContext(
                        platform, step.arguments().path("serial").asText(parent.executionContext().deviceId()),
                        step.arguments().path("workspace").asText(parent.executionContext().workspace()),
                        parent.executionContext().confirmationId(), List.of("agent:" + step.agentId()), authorization));
    }

    /** 解析并校验最多 8 个顺序步骤。 */
    private List<SupervisorStep> parseSteps(JsonNode arguments) {
        JsonNode values = arguments == null ? null : arguments.path("steps");
        if (values == null || !values.isArray() || values.isEmpty() || values.size() > MAX_STEPS) {
            throw new IllegalArgumentException("Supervisor steps 数量必须在 1 至 8 之间");
        }
        List<SupervisorStep> steps = new ArrayList<>();
        Set<String> stepIds = new HashSet<>();
        for (JsonNode value : values) {
            String stepId = value.path("stepId").asText("").trim();
            String agentId = value.path("agentId").asText("").trim();
            JsonNode stepArguments = value.path("arguments");
            if (!stepId.matches("[A-Za-z0-9_-]{1,64}") || !stepIds.add(stepId)
                    || !StringUtils.hasText(agentId) || !stepArguments.isObject()) {
                throw new IllegalArgumentException("Supervisor 步骤标识、Agent 或参数不合法");
            }
            requireAgent(agentId);
            steps.add(new SupervisorStep(
                    stepId, agentId, stepArguments.deepCopy(), value.path("continueOnFailure").asBoolean(false)));
        }
        return List.copyOf(steps);
    }

    /** 创建单个 Worker 的结构化结果。 */
    private ObjectNode workerResult(SupervisorStep step, CallResult result) {
        ObjectNode value = objectMapper.createObjectNode()
                .put("stepId", step.stepId())
                .put("agentId", step.agentId())
                .put("toolId", result.tool().toolId())
                .put("status", result.status().name())
                .put("summary", resultSummary(result));
        value.set("output", result.payload().output() == null
                ? objectMapper.createObjectNode() : result.payload().output().deepCopy());
        return value;
    }

    /** 合并 Worker 业务摘要和错误详情并限制事件体积。 */
    private String resultSummary(CallResult result) {
        String summary = result.payload().summary() == null ? "" : result.payload().summary();
        String detail = result.diagnostics().error() == null
                ? "" : result.diagnostics().error().detail();
        String value = StringUtils.hasText(detail) ? summary + ": " + detail : summary;
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    /** 返回首个失败 Worker 的有界摘要。 */
    private String failureSummary(ArrayNode results) {
        for (JsonNode result : results) {
            if (!CallStatus.SUCCEEDED.name().equals(result.path("status").asText(""))) {
                String summary = result.path("summary").asText("");
                return summary.length() <= 1_000 ? summary : summary.substring(0, 1_000);
            }
        }
        return "";
    }

    /** 获取已注册 Worker Agent。 */
    private AgentDefinition requireAgent(String agentId) {
        return agentRegistry.find(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Supervisor Worker Agent 不存在: " + agentId));
    }

    /** 获取统一 Tool Gateway。 */
    private ToolGateway requireGateway() {
        ToolGateway gateway = toolGateway.getIfAvailable();
        if (gateway == null) {
            throw new IllegalStateException("统一 Tool Gateway 尚未就绪");
        }
        return gateway;
    }

    /** 发布计划和 Worker 步骤事件；无持久任务的兼容调用跳过。 */
    private void publish(
            CallRequest parent,
            AgentEventType type,
            String stepId,
            String toolCallId,
            Map<String, Object> payload) {
        if (!StringUtils.hasText(parent.identity().taskId())
                || taskService == null || eventSequencer == null) {
            return;
        }
        AgentTask task = taskService.findTask(parent.identity().taskId()).orElse(null);
        if (task == null) {
            return;
        }
        AgentEventContext context = new AgentEventContext(
                task.conversationId(), parent.identity().turnId(), stepId, toolCallId,
                null, null, task.version());
        eventSequencer.publish(task.taskId(), new AgentEventRequest(
                type, AgentEventScope.STEP, context, payload, Instant.now(), "supervisor-agent"));
    }

    /** 从 Worker 参数推导设备平台。 */
    private Platform targetPlatform(JsonNode arguments, Platform fallback) {
        String value = arguments.path("platform").asText("").toLowerCase(Locale.ROOT);
        return switch (value) {
            case "ios" -> Platform.IOS;
            case "harmony", "harmonyos", "harmony_os" -> Platform.HARMONY_OS;
            case "macos", "mac" -> Platform.MACOS;
            case "windows", "win" -> Platform.WINDOWS;
            case "linux" -> Platform.LINUX;
            case "android" -> Platform.ANDROID;
            default -> fallback == null || fallback == Platform.PLATFORM_INDEPENDENT
                    ? Platform.ANDROID : fallback;
        };
    }

    /** 计算风险等级顺序。 */
    private int riskOrder(RiskLevel level) {
        return switch (level) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case UNCLASSIFIED -> 3;
        };
    }

    /** 计算参数 SHA-256 摘要。 */
    private String digest(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    /** 返回短稳定标识。 */
    private String shortHash(String value) {
        return digest(objectMapper.getNodeFactory().textNode(value)).substring(0, 12);
    }

    /** Supervisor 结构化步骤。by AI.Coding */
    private record SupervisorStep(
            String stepId,
            String agentId,
            JsonNode arguments,
            boolean continueOnFailure) {
    }
}
