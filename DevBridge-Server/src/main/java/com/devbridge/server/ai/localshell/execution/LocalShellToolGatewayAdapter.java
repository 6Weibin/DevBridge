package com.devbridge.server.ai.localshell.execution;

import com.devbridge.server.ai.agent.runtime.AgentResourceKey;
import com.devbridge.server.ai.agent.runtime.AgentResourceMode;
import com.devbridge.server.ai.agent.runtime.AgentResourceRequest;
import com.devbridge.server.ai.agent.runtime.AgentResourceType;
import com.devbridge.server.ai.localshell.catalog.LocalShellToolCatalog;
import com.devbridge.server.ai.localshell.model.LocalShellCommandPlan;
import com.devbridge.server.ai.localshell.model.LocalShellMcpToolDefinition;
import com.devbridge.server.ai.localshell.model.LocalShellMcpToolRequest;
import com.devbridge.server.ai.localshell.model.LocalShellRiskAssessment;
import com.devbridge.server.ai.localshell.policy.LocalShellCommandPlanner;
import com.devbridge.server.ai.localshell.policy.LocalShellRiskClassifier;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import com.devbridge.server.ai.mcp.model.AdbToolStatus;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Local Shell 到中立 Tool Gateway 的适配器，独立表达本机工具来源和结果。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class LocalShellToolGatewayAdapter implements ToolAdapter {

    private static final String TOOL_PREFIX = "desktop.shell.";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final LocalShellToolCatalog catalog;
    private final LocalShellCommandPlanner planner;
    private final LocalShellRiskClassifier riskClassifier;
    private final LocalShellMcpToolService toolService;
    private final ObjectMapper objectMapper;

    /**
     * 注入 Local Shell 兼容实现。
     *
     * @param catalog 工具目录
     * @param planner 命令规划器
     * @param riskClassifier 风险分类器
     * @param toolService 工具服务
     * @param objectMapper JSON 工具
     */
    public LocalShellToolGatewayAdapter(
            LocalShellToolCatalog catalog,
            LocalShellCommandPlanner planner,
            LocalShellRiskClassifier riskClassifier,
            LocalShellMcpToolService toolService,
            ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.planner = planner;
        this.riskClassifier = riskClassifier;
        this.toolService = toolService;
        this.objectMapper = objectMapper;
    }

    /**
     * 将 Local Shell 目录转换为中立定义。
     *
     * @return 中立定义
     */
    @Override
    public List<Definition> definitions() {
        return catalog.listTools().stream().map(this::definition).toList();
    }

    /**
     * 复用现有用户授权和本机风险分类逻辑。
     *
     * @param request 中立请求
     * @param definition 中立定义
     * @return 风险决策
     */
    @Override
    public RiskDecision assess(CallRequest request, Definition definition) {
        LocalShellMcpToolDefinition legacy = legacyDefinition(request.tool().toolId());
        LocalShellCommandPlan plan = planner.plan(legacyRequest(request), legacy);
        LocalShellRiskAssessment assessment = riskClassifier.assess(plan);
        RiskAction action = assessment.denied()
                ? RiskAction.BLOCK
                : assessment.confirmationRequired() ? RiskAction.CONFIRM : RiskAction.ALLOW;
        return new RiskDecision(
                riskLevel(assessment.riskLevel()),
                action,
                "local-shell-risk-classifier",
                assessment.denied() ? "LOCAL_SHELL_DENIED"
                        : assessment.confirmationRequired() ? "LOCAL_SHELL_CONFIRM" : "LOCAL_SHELL_ALLOWED",
                assessment.reasons().isEmpty() ? assessment.impact() : String.join("；", assessment.reasons()),
                "",
                Instant.now());
    }

    /**
     * 本机工具按工作目录申请共享或独占路径锁。
     *
     * @param request 中立请求
     * @param definition 中立定义
     * @return 资源锁请求
     */
    @Override
    public List<AgentResourceRequest> resources(CallRequest request, Definition definition) {
        String workspace = StringUtils.hasText(request.executionContext().workspace())
                ? request.executionContext().workspace()
                : "default-workspace";
        AgentResourceMode mode = definition.metadata().accessMode() == AccessMode.READ
                ? AgentResourceMode.SHARED
                : AgentResourceMode.EXCLUSIVE;
        return List.of(new AgentResourceRequest(
                new AgentResourceKey(AgentResourceType.LOCAL_PATH, workspace),
                mode));
    }

    /**
     * 调用包内批准执行入口并转换中立结果。
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
        return result(request, definition, decision, result, started);
    }

    /**
     * 将 Agent Task 取消传播到正在运行的本机命令。
     *
     * @param request 原工具请求
     * @param definition 工具定义
     */
    @Override
    public void cancel(CallRequest request, Definition definition) {
        toolService.cancel(request.identity().toolCallId());
    }

    /**
     * 转换单个 Local Shell 定义。
     *
     * @param legacy 旧定义
     * @return 中立定义
     */
    private Definition definition(LocalShellMcpToolDefinition legacy) {
        AccessMode accessMode = accessMode(legacy.name());
        ObjectNode inputSchema = legacy.inputSchema().deepCopy();
        inputSchema.put("additionalProperties", false);
        Metadata metadata = new Metadata(
                new Source(SourceKind.LOCAL_ADAPTER, "local-shell", "1.0.0", "", "IN_PROCESS"),
                capabilities(legacy.name()),
                hostPlatforms(),
                accessMode,
                idempotency(accessMode),
                new RiskProfile(staticRiskBaseline(legacy), true),
                new ExecutionProfile(
                        legacy.timeout().toMillis(),
                        legacy.timeout().toMillis(),
                        (long) legacy.outputLimit().maxStdoutCharacters()
                                + legacy.outputLimit().maxStderrCharacters(),
                        true,
                        "local_shell_exec".equals(legacy.name()),
                        List.of("workspace")));
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
     * 转换为旧 Local Shell 请求。
     *
     * @param request 中立请求
     * @return 旧请求
     */
    private LocalShellMcpToolRequest legacyRequest(CallRequest request) {
        Map<String, Object> arguments = objectMapper.convertValue(request.arguments(), MAP_TYPE);
        if (StringUtils.hasText(request.executionContext().workspace()) && !arguments.containsKey("workingDirectory")) {
            arguments.put("workingDirectory", request.executionContext().workspace());
        }
        return new LocalShellMcpToolRequest(
                legacyName(request.tool().toolId()),
                request.identity().conversationId(),
                arguments,
                "",
                request.identity().toolCallId());
    }

    /**
     * 将兼容结果转换为中立结果。
     *
     * @param request 中立请求
     * @param definition 中立定义
     * @param decision 风险决策
     * @param legacy 兼容结果
     * @param started 开始时间
     * @return 中立结果
     */
    private CallResult result(
            CallRequest request,
            Definition definition,
            RiskDecision decision,
            AdbMcpToolResult legacy,
            Instant started) {
        CallStatus status = status(legacy.status());
        ObjectNode output = objectMapper.createObjectNode();
        output.put("stdout", legacy.stdout());
        output.put("stderr", legacy.stderr());
        output.put("truncated", legacy.truncated());
        output.put("commandSummary", legacy.commandSummary());
        output.put("source", "local-shell");
        Error error = status == CallStatus.SUCCEEDED ? null : new Error(
                legacy.errorCode(), errorCategory(legacy), legacy.message(), legacy.stderr(), false, false);
        boolean sideEffect = status == CallStatus.SUCCEEDED && definition.metadata().accessMode() != AccessMode.READ;
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
                        new SideEffect(sideEffect, status == CallStatus.SUCCEEDED, false)));
    }

    /**
     * 查找旧工具定义。
     *
     * @param toolId 中立工具 ID
     * @return 旧定义
     */
    private LocalShellMcpToolDefinition legacyDefinition(String toolId) {
        return catalog.requireTool(legacyName(toolId));
    }

    /**
     * 生成中立工具 ID。
     *
     * @param legacyName 旧工具名
     * @return 中立工具 ID
     */
    private String toolId(String legacyName) {
        return TOOL_PREFIX + legacyName.substring("local_shell_".length()).replace('_', '.');
    }

    /**
     * 恢复旧工具名。
     *
     * @param toolId 中立工具 ID
     * @return 旧工具名
     */
    private String legacyName(String toolId) {
        if (toolId == null || !toolId.startsWith(TOOL_PREFIX)) {
            throw new IllegalArgumentException("不是 Local Shell 中立工具 ID: " + toolId);
        }
        return "local_shell_" + toolId.substring(TOOL_PREFIX.length()).replace('.', '_');
    }

    /**
     * 生成用户可见显示名。
     *
     * @param legacyName 旧工具名
     * @return 显示名
     */
    private String displayName(String legacyName) {
        return switch (legacyName) {
            case "local_shell_exec" -> "本机命令执行";
            case "local_shell_pwd" -> "本机工作目录";
            case "local_shell_list_dir" -> "本机目录查询";
            case "local_shell_read_text" -> "本机文本读取";
            case "local_shell_process_status" -> "本机进程状态";
            case "local_shell_cancel" -> "本机命令取消";
            default -> "本机工具";
        };
    }

    /**
     * 计算访问模式。
     *
     * @param legacyName 旧工具名
     * @return 访问模式
     */
    private AccessMode accessMode(String legacyName) {
        return switch (legacyName) {
            case "local_shell_pwd", "local_shell_list_dir", "local_shell_read_text", "local_shell_process_status" -> AccessMode.READ;
            case "local_shell_cancel" -> AccessMode.CONTROL;
            default -> AccessMode.MIXED;
        };
    }

    /**
     * 生成路由能力。
     *
     * @param legacyName 旧工具名
     * @return 能力标签
     */
    private List<String> capabilities(String legacyName) {
        return switch (legacyName) {
            case "local_shell_pwd", "local_shell_list_dir" -> List.of("desktop.file.read");
            case "local_shell_read_text" -> List.of("desktop.file.content.read");
            case "local_shell_process_status" -> List.of("desktop.process.read");
            case "local_shell_cancel" -> List.of("desktop.process.control");
            default -> List.of("desktop.shell.execute");
        };
    }

    /**
     * 返回支持的宿主机平台。
     *
     * @return 平台集合
     */
    private List<Platform> hostPlatforms() {
        return List.of(Platform.MACOS, Platform.WINDOWS, Platform.LINUX);
    }

    /**
     * 生成幂等定义。
     *
     * @param accessMode 访问模式
     * @return 幂等定义
     */
    private Idempotency idempotency(AccessMode accessMode) {
        return accessMode == AccessMode.READ
                ? new Idempotency(IdempotencyMode.NATURAL, false, "")
                : new Idempotency(IdempotencyMode.VERIFY_REQUIRED, true, "desktop.state.verify");
    }

    /**
     * 参数分类型命令使用最低静态基线，最终风险由用户授权和本地分类器动态升级。
     *
     * @param legacy 旧工具定义
     * @return 中立静态风险基线
     */
    private ToolContract.RiskLevel staticRiskBaseline(LocalShellMcpToolDefinition legacy) {
        return "local_shell_exec".equals(legacy.name())
                ? ToolContract.RiskLevel.LOW
                : riskLevel(legacy.defaultRiskLevel());
    }

    /**
     * 映射风险等级，旧 CRITICAL 统一表达为 HIGH + BLOCK。
     *
     * @param level 旧风险等级
     * @return 中立风险等级
     */
    private ToolContract.RiskLevel riskLevel(AdbRiskLevel level) {
        return level == AdbRiskLevel.CRITICAL ? ToolContract.RiskLevel.HIGH : ToolContract.RiskLevel.valueOf(level.name());
    }

    /**
     * 映射执行状态。
     *
     * @param status 旧状态
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

    /**
     * 根据兼容结果映射错误类别。
     *
     * @param result 兼容结果
     * @return 错误类别
     */
    private ErrorCategory errorCategory(AdbMcpToolResult result) {
        return "LOCAL_SHELL_COMMAND_DENIED".equals(result.errorCode())
                ? ErrorCategory.POLICY
                : result.timedOut() ? ErrorCategory.TIMEOUT : ErrorCategory.EXECUTION;
    }
}
