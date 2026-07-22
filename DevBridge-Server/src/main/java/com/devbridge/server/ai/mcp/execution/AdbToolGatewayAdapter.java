package com.devbridge.server.ai.mcp.execution;

import com.devbridge.server.ai.agent.runtime.AgentResourceKey;
import com.devbridge.server.ai.agent.runtime.AgentResourceMode;
import com.devbridge.server.ai.agent.runtime.AgentResourceRequest;
import com.devbridge.server.ai.agent.runtime.AgentResourceType;
import com.devbridge.server.ai.mcp.catalog.AdbToolCatalog;
import com.devbridge.server.ai.mcp.model.AdbCommandPlan;
import com.devbridge.server.ai.mcp.model.AdbMcpToolDefinition;
import com.devbridge.server.ai.mcp.model.AdbMcpToolRequest;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import com.devbridge.server.ai.mcp.model.AdbRiskAssessment;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import com.devbridge.server.ai.mcp.model.AdbToolStatus;
import com.devbridge.server.ai.mcp.risk.AdbRiskClassifier;
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
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskProfile;
import com.devbridge.server.ai.tool.gateway.ToolContract.SideEffect;
import com.devbridge.server.ai.tool.gateway.ToolContract.Source;
import com.devbridge.server.ai.tool.gateway.ToolContract.SourceKind;
import com.devbridge.server.ai.tool.gateway.ToolContract.Timing;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ADB 到中立 Tool Gateway 的适配器，复用现有规划、风险和执行实现。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AdbToolGatewayAdapter implements ToolAdapter {

    private static final String TOOL_PREFIX = "android.adb.";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AdbToolCatalog catalog;
    private final AdbCommandPlanner planner;
    private final AdbRiskClassifier riskClassifier;
    private final AdbMcpToolService toolService;
    private final ObjectMapper objectMapper;

    /**
     * 注入 ADB 兼容实现。
     *
     * @param catalog ADB 工具目录
     * @param planner ADB 命令规划器
     * @param riskClassifier ADB 风险分类器
     * @param toolService ADB 工具服务
     * @param objectMapper JSON 工具
     */
    public AdbToolGatewayAdapter(
            AdbToolCatalog catalog,
            AdbCommandPlanner planner,
            AdbRiskClassifier riskClassifier,
            AdbMcpToolService toolService,
            ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.planner = planner;
        this.riskClassifier = riskClassifier;
        this.toolService = toolService;
        this.objectMapper = objectMapper;
    }

    /**
     * 将全部 ADB 定义转换为中立工具定义。
     *
     * @return 中立工具定义
     */
    @Override
    public List<Definition> definitions() {
        return catalog.listTools().stream().map(this::definition).toList();
    }

    /**
     * 使用现有 ADB Planner 和风险分类器计算动态风险。
     *
     * @param request 中立请求
     * @param definition 中立定义
     * @return 中立风险决策
     */
    @Override
    public RiskDecision assess(CallRequest request, Definition definition) {
        AdbMcpToolDefinition legacyDefinition = legacyDefinition(request.tool().toolId());
        AdbCommandPlan plan = planner.plan(legacyRequest(request), legacyDefinition);
        AdbRiskAssessment assessment = riskClassifier.assess(plan);
        RiskAction action = assessment.confirmationRequired() ? RiskAction.CONFIRM : RiskAction.ALLOW;
        return new RiskDecision(
                riskLevel(assessment.riskLevel()),
                action,
                "adb-risk-classifier",
                assessment.confirmationRequired() ? "ADB_SENSITIVE_OPERATION" : "ADB_LOW_RISK",
                assessment.reasons().isEmpty() ? assessment.impact() : String.join("；", assessment.reasons()),
                "",
                Instant.now());
    }

    /**
     * 设备工具按访问模式申请共享或独占设备锁。
     *
     * @param request 中立请求
     * @param definition 中立定义
     * @return 资源锁请求
     */
    @Override
    public List<AgentResourceRequest> resources(CallRequest request, Definition definition) {
        AdbMcpToolDefinition legacy = legacyDefinition(request.tool().toolId());
        if (!legacy.requiresDevice()) {
            return List.of();
        }
        AgentResourceMode mode = definition.metadata().accessMode() == AccessMode.READ
                ? AgentResourceMode.SHARED
                : AgentResourceMode.EXCLUSIVE;
        return List.of(new AgentResourceRequest(
                new AgentResourceKey(AgentResourceType.DEVICE, request.executionContext().deviceId()),
                mode));
    }

    /**
     * 调用 ADB 包内批准执行入口，并转换为中立结果。
     *
     * @param request 中立请求
     * @param definition 中立定义
     * @param decision 风险决策
     * @return 中立结果
     */
    @Override
    public CallResult execute(CallRequest request, Definition definition, RiskDecision decision) {
        Instant started = Instant.now();
        AdbMcpToolResult result = toolService.callApprovedByGateway(legacyRequest(request));
        return result(request, decision, result, started);
    }

    /**
     * 将 Agent Task 取消传播到正在运行的 ADB 命令。
     *
     * @param request 原工具请求
     * @param definition 工具定义
     */
    @Override
    public void cancel(CallRequest request, Definition definition) {
        toolService.cancel(request.identity().toolCallId());
    }

    /**
     * 转换单个 ADB 定义。
     *
     * @param legacy ADB 定义
     * @return 中立定义
     */
    private Definition definition(AdbMcpToolDefinition legacy) {
        AccessMode accessMode = accessMode(legacy.name());
        ObjectNode inputSchema = legacy.inputSchema().deepCopy();
        inputSchema.put("additionalProperties", false);
        Metadata metadata = new Metadata(
                new Source(SourceKind.LOCAL_ADAPTER, "adb", "1.0.0", "", "IN_PROCESS"),
                capabilities(legacy.name()),
                List.of(Platform.ANDROID),
                accessMode,
                idempotency(accessMode),
                new RiskProfile(riskLevel(legacy.defaultRiskLevel()), true),
                new ExecutionProfile(
                        legacy.timeout().toMillis(),
                        legacy.timeout().toMillis(),
                        (long) legacy.outputLimit().maxStdoutCharacters()
                                + legacy.outputLimit().maxStderrCharacters(),
                        true,
                        "adb_debugging".equals(legacy.name()),
                        legacy.requiresDevice() ? List.of("device") : List.of("host")));
        return new Definition(
                ToolContract.SCHEMA_VERSION,
                new Identity(toolId(legacy.name()), displayName(legacy.name()), legacy.description()),
                metadata,
                inputSchema,
                legacy.outputSchema(),
                true,
                new Deprecation(false, ""));
    }

    /**
     * 将中立请求转换为现有 ADB 请求。
     *
     * @param request 中立请求
     * @return ADB 请求
     */
    private AdbMcpToolRequest legacyRequest(CallRequest request) {
        Map<String, Object> arguments = objectMapper.convertValue(request.arguments(), MAP_TYPE);
        return new AdbMcpToolRequest(
                legacyName(request.tool().toolId()),
                request.identity().conversationId(),
                request.executionContext().deviceId(),
                arguments,
                "",
                request.identity().toolCallId());
    }

    /**
     * 将 ADB 结果转换为中立结果，旧确认令牌和 MCP 标题不再向上泄漏。
     *
     * @param request 中立请求
     * @param decision 风险决策
     * @param legacy ADB 结果
     * @param started 开始时间
     * @return 中立结果
     */
    private CallResult result(
            CallRequest request,
            RiskDecision decision,
            AdbMcpToolResult legacy,
            Instant started) {
        CallStatus status = status(legacy.status());
        ObjectNode output = objectMapper.createObjectNode();
        output.put("stdout", legacy.stdout());
        output.put("stderr", legacy.stderr());
        output.put("truncated", legacy.truncated());
        output.put("commandSummary", legacy.commandSummary());
        Error error = status == CallStatus.SUCCEEDED ? null : new Error(
                legacy.errorCode(), ErrorCategory.EXECUTION, legacy.message(), legacy.stderr(), false, false);
        return new CallResult(
                ToolContract.SCHEMA_VERSION,
                request.tool(),
                request.identity().toolCallId(),
                status,
                decision,
                new Timing(started, Instant.now(), legacy.durationMillis()),
                new ResultPayload(output, legacy.message(), List.of()),
                new Diagnostics(
                        error,
                        new Exit(legacy.exitCode(), legacy.timedOut()),
                        new Metrics(0, output.toString().length(), 0, 0),
                        new SideEffect(status == CallStatus.SUCCEEDED && accessMode(legacyName(request.tool().toolId())) != AccessMode.READ,
                                status == CallStatus.SUCCEEDED, false)));
    }

    /**
     * 按中立工具 ID 获取旧 ADB 定义。
     *
     * @param toolId 中立工具 ID
     * @return ADB 定义
     */
    private AdbMcpToolDefinition legacyDefinition(String toolId) {
        return catalog.requireTool(legacyName(toolId));
    }

    /**
     * 生成中立工具 ID。
     *
     * @param legacyName 旧工具名
     * @return 中立工具 ID
     */
    private String toolId(String legacyName) {
        return TOOL_PREFIX + legacyName.substring("adb_".length()).replace('_', '.');
    }

    /**
     * 从中立工具 ID 恢复旧工具名。
     *
     * @param toolId 中立工具 ID
     * @return 旧工具名
     */
    private String legacyName(String toolId) {
        if (toolId == null || !toolId.startsWith(TOOL_PREFIX)) {
            throw new IllegalArgumentException("不是 ADB 中立工具 ID: " + toolId);
        }
        return "adb_" + toolId.substring(TOOL_PREFIX.length()).replace('.', '_');
    }

    /**
     * 映射用户可见名称，不包含 MCP 协议术语。
     *
     * @param legacyName 旧工具名
     * @return 显示名称
     */
    private String displayName(String legacyName) {
        return switch (legacyName) {
            case "adb_devices" -> "Android 设备查询";
            case "adb_shell" -> "Android Shell";
            case "adb_app_install" -> "Android 应用安装";
            case "adb_app_uninstall" -> "Android 应用卸载";
            case "adb_debugging" -> "Android 调试与日志";
            default -> "ADB " + legacyName.substring("adb_".length()).replace('_', ' ');
        };
    }

    /**
     * 根据旧工具定义访问模式。
     *
     * @param legacyName 旧工具名
     * @return 访问模式
     */
    private AccessMode accessMode(String legacyName) {
        return switch (legacyName) {
            case "adb_devices", "adb_help", "adb_version", "adb_scripting" -> AccessMode.READ;
            case "adb_shell", "adb_debugging", "adb_raw" -> AccessMode.MIXED;
            case "adb_app_install", "adb_app_uninstall", "adb_device_control", "adb_server" -> AccessMode.CONTROL;
            default -> AccessMode.WRITE;
        };
    }

    /**
     * 生成路由能力标签。
     *
     * @param legacyName 旧工具名
     * @return 能力标签
     */
    private List<String> capabilities(String legacyName) {
        return switch (legacyName) {
            case "adb_devices", "adb_scripting" -> List.of("device.read");
            case "adb_debugging" -> List.of("device.log.read", "device.debug.read");
            case "adb_app_install", "adb_app_uninstall" -> List.of("device.app.write");
            case "adb_file_transfer" -> List.of("device.file.transfer");
            case "adb_shell" -> List.of("device.shell.execute");
            default -> List.of("device.adb." + legacyName.substring("adb_".length()).replace('_', '.'));
        };
    }

    /**
     * 根据访问模式定义幂等属性。
     *
     * @param accessMode 访问模式
     * @return 幂等定义
     */
    private Idempotency idempotency(AccessMode accessMode) {
        return accessMode == AccessMode.READ
                ? new Idempotency(IdempotencyMode.NATURAL, false, "")
                : new Idempotency(IdempotencyMode.VERIFY_REQUIRED, true, "device.state.verify");
    }

    /**
     * 映射风险等级。
     *
     * @param level ADB 风险
     * @return 中立风险
     */
    private ToolContract.RiskLevel riskLevel(AdbRiskLevel level) {
        return ToolContract.RiskLevel.valueOf(level.name());
    }

    /**
     * 映射执行状态。
     *
     * @param status ADB 状态
     * @return 中立状态
     */
    private CallStatus status(AdbToolStatus status) {
        return switch (status) {
            case SUCCESS -> CallStatus.SUCCEEDED;
            case CANCELED -> CallStatus.CANCELED;
            case CONFIRMATION_REQUIRED -> CallStatus.WAITING_CONFIRMATION;
            case FAILED -> CallStatus.FAILED;
        };
    }
}
