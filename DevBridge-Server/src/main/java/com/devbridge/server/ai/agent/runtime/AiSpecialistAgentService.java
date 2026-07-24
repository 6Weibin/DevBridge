package com.devbridge.server.ai.agent.runtime;

import com.devbridge.server.ai.agent.runtime.AiAgentRegistry.AgentDefinition;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityMetadata;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityQuery;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskLevel;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.devbridge.server.ai.tool.gateway.ToolContract.WorkflowAuthorization;
import com.devbridge.server.ai.tool.gateway.ToolGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Device、Log、App 和 Local 专业 Agent 的确定性领域工具调度服务。
 *
 * <p>专业 Agent 只接受白名单 Operation，不能从模型输入直接获取任意工具 ID。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AiSpecialistAgentService {

    private static final Map<String, Map<String, String>> OPERATIONS = operations();

    private final ObjectProvider<ToolGateway> toolGateway;
    private final ObjectMapper objectMapper;
    private final AiAgentRegistry agentRegistry;
    private final SensitiveDataMasker masker;

    /**
     * 注入 Tool Gateway、Agent Registry 和脱敏器。
     *
     * @param toolGateway 延迟获取 Tool Gateway，避免工具注册循环依赖
     * @param objectMapper JSON 工具
     * @param agentRegistry Agent 注册表
     * @param masker 敏感参数脱敏器
     */
    public AiSpecialistAgentService(
            ObjectProvider<ToolGateway> toolGateway,
            ObjectMapper objectMapper,
            AiAgentRegistry agentRegistry,
            SensitiveDataMasker masker) {
        this.toolGateway = toolGateway;
        this.objectMapper = objectMapper;
        this.agentRegistry = agentRegistry;
        this.masker = masker;
    }

    /**
     * 执行单个专业 Agent Operation。
     *
     * @param parent 专业 Agent 父工具请求
     * @param agentId Agent ID
     * @return 统一结构化结果
     */
    public JsonNode execute(CallRequest parent, String agentId) {
        SpecialistCall call = parse(parent.arguments(), agentId);
        CapabilityMetadata tool = resolveTool(agentId, call.toolId());
        CallResult result = requireGateway().call(childRequest(parent, agentId, call, tool));
        ObjectNode output = objectMapper.createObjectNode()
                .put("agentId", agentId)
                .put("operation", call.operation())
                .put("toolId", tool.toolId())
                .put("status", result.status().name())
                .put("summary", result.payload().summary())
                .put("error", result.diagnostics().error() == null
                        ? "" : result.diagnostics().error().detail());
        output.set("evidence", result.payload().output() == null
                ? objectMapper.createObjectNode() : result.payload().output().deepCopy());
        return output;
    }

    /**
     * 返回专业 Agent Operation 的入口风险。
     *
     * @param agentId Agent ID
     * @param arguments Agent 参数
     * @return 保守风险等级
     */
    public RiskLevel risk(String agentId, JsonNode arguments) {
        SpecialistCall call = parse(arguments, agentId);
        RiskLevel baseline = resolveTool(agentId, call.toolId()).metadata().riskProfile().minimumLevel();
        if (AiAgentRegistry.LOCAL_AGENT.equals(agentId) && "EXEC".equals(call.operation())) {
            return RiskLevel.HIGH;
        }
        return baseline;
    }

    /** 返回 Agent 可用 Operation。 */
    public List<String> operations(String agentId) {
        Map<String, String> values = OPERATIONS.get(agentId);
        return values == null ? List.of() : List.copyOf(values.keySet());
    }

    /** 返回专业 Agent 指令。 */
    public String instruction(String agentId) {
        return requireAgent(agentId).identity().instruction();
    }

    /** 返回脱敏后的确认摘要。 */
    public String confirmationSummary(String agentId, JsonNode arguments) {
        SpecialistCall call = parse(arguments, agentId);
        String masked = masker.protectCredentials(call.arguments().toString());
        String bounded = masked.length() <= 500 ? masked : masked.substring(0, 500) + "...";
        return requireAgent(agentId).identity().displayName()
                + " 将执行 " + call.operation() + "，参数=" + bounded;
    }

    /** 解析并校验白名单 Operation 和对象参数。 */
    private SpecialistCall parse(JsonNode input, String agentId) {
        requireAgent(agentId);
        String operation = input == null ? "" : input.path("operation").asText("").trim().toUpperCase(Locale.ROOT);
        JsonNode arguments = input == null ? null : input.path("arguments");
        String toolId = OPERATIONS.getOrDefault(agentId, Map.of()).get(operation);
        if (toolId == null || arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("专业 Agent Operation 或 arguments 不合法: " + agentId);
        }
        return new SpecialistCall(operation, toolId, arguments.deepCopy());
    }

    /** 解析工具并再次执行 Registry Worker 权限校验。 */
    private CapabilityMetadata resolveTool(String agentId, String toolId) {
        List<CapabilityMetadata> all = requireGateway().listCapabilities(
                new CapabilityQuery(null, List.of(), null, false));
        return agentRegistry.authorizedWorkerTools(agentId, all).stream()
                .filter(tool -> toolId.equals(tool.toolId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "专业 Agent 无权调用工具: " + agentId + "/" + toolId));
    }

    /** 构造继承最外层确认和稳定幂等键的领域工具请求。 */
    private CallRequest childRequest(
            CallRequest parent,
            String agentId,
            SpecialistCall call,
            CapabilityMetadata tool) {
        Platform platform = targetPlatform(agentId, call.arguments(), parent.executionContext().platform());
        AgentDefinition agent = requireAgent(agentId);
        if (!agent.platforms().contains(platform)) {
            throw new IllegalArgumentException("专业 Agent 不支持目标平台: " + agentId + "/" + platform);
        }
        String suffix = shortHash(parent.identity().toolCallId() + ":" + call.operation() + ":" + call.arguments());
        WorkflowAuthorization authorization = rootAuthorization(parent);
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity(
                        parent.identity().conversationId(), parent.identity().taskId(), parent.identity().turnId(),
                        "specialist-step-" + call.operation().toLowerCase(Locale.ROOT) + "-" + suffix,
                        "specialist-call-" + suffix, Instant.now()),
                new ToolReference(tool.toolId(), tool.schemaVersion()), call.arguments().deepCopy(),
                digest(call.arguments()), "specialist:" + parent.identity().toolCallId() + ":" + call.operation(),
                Caller.WORKFLOW,
                new ExecutionContext(
                        platform, call.arguments().path("serial").asText(parent.executionContext().deviceId()),
                        call.arguments().path("workingDirectory").asText(parent.executionContext().workspace()),
                        parent.executionContext().confirmationId(), List.of("agent:" + agentId), authorization));
    }

    /** 优先透传 Supervisor 根授权，直接调用时绑定当前专业 Agent。 */
    private WorkflowAuthorization rootAuthorization(CallRequest parent) {
        return parent.executionContext().workflowAuthorization() == null
                ? new WorkflowAuthorization(
                        parent.tool().toolId(), parent.identity().stepId(),
                        parent.identity().toolCallId(), parent.argumentDigest())
                : parent.executionContext().workflowAuthorization();
    }

    /** 按专业 Agent 和参数选择目标平台。 */
    private Platform targetPlatform(String agentId, JsonNode arguments, Platform fallback) {
        if (AiAgentRegistry.LOCAL_AGENT.equals(agentId)) {
            return hostPlatform();
        }
        String value = arguments.path("platform").asText("").toLowerCase(Locale.ROOT);
        return switch (value) {
            case "ios" -> Platform.IOS;
            case "harmony", "harmonyos", "harmony_os" -> Platform.HARMONY_OS;
            case "android" -> Platform.ANDROID;
            default -> fallback == null || fallback == Platform.PLATFORM_INDEPENDENT
                    ? Platform.ANDROID : fallback;
        };
    }

    /** 返回当前宿主平台。 */
    private Platform hostPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") ? Platform.MACOS : os.contains("win") ? Platform.WINDOWS : Platform.LINUX;
    }

    /** 获取专业 Agent 定义。 */
    private AgentDefinition requireAgent(String agentId) {
        return agentRegistry.find(agentId)
                .orElseThrow(() -> new IllegalArgumentException("专业 Agent 不存在: " + agentId));
    }

    /** 获取统一 Tool Gateway。 */
    private ToolGateway requireGateway() {
        ToolGateway gateway = toolGateway.getIfAvailable();
        if (gateway == null) {
            throw new IllegalStateException("统一 Tool Gateway 尚未就绪");
        }
        return gateway;
    }

    /** 计算参数摘要。 */
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

    /** 创建四个专业 Agent 的 Operation 白名单。 */
    private static Map<String, Map<String, String>> operations() {
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        values.put(AiAgentRegistry.DEVICE_AGENT, Map.of(
                "LIST", "device.list", "DETAIL", "device.detail.read", "HEALTH", "device.health.read",
                "SCREENSHOT", "device.screenshot.capture", "DIAGNOSE", "device.connection.diagnose"));
        values.put(AiAgentRegistry.DOMAIN_LOG_AGENT, Map.of(
                "START", "log.capture.start", "READ", "log.capture.read", "STOP", "log.capture.stop",
                "STATUS", "log.capture.status", "EXPORT", "log.capture.export"));
        values.put(AiAgentRegistry.APP_AGENT, Map.of(
                "LIST", "app.list", "DETAIL", "app.detail.read", "PERMISSIONS", "app.permissions.read",
                "INSTALL", "app.install", "UNINSTALL", "app.uninstall", "LAUNCH", "app.launch", "STOP", "app.stop"));
        values.put(AiAgentRegistry.LOCAL_AGENT, Map.of(
                "EXEC", "desktop.shell.exec", "PWD", "desktop.shell.pwd", "LIST_DIR", "desktop.shell.list.dir",
                "READ_TEXT", "desktop.shell.read.text", "PROCESS_STATUS", "desktop.shell.process.status",
                "CANCEL", "desktop.shell.cancel"));
        return Map.copyOf(values);
    }

    /** 已解析专业 Agent 调用。by AI.Coding */
    private record SpecialistCall(String operation, String toolId, JsonNode arguments) {
    }
}
