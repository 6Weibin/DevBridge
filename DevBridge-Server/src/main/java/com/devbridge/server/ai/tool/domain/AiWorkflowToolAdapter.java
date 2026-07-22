package com.devbridge.server.ai.tool.domain;

import com.devbridge.server.ai.agent.runtime.AiFixedWorkflowService;
import com.devbridge.server.ai.agent.runtime.AiSupervisorAgentService;
import com.devbridge.server.ai.agent.runtime.AgentResourceRequest;
import com.devbridge.server.ai.agent.runtime.AgentTaskApplicationService;
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
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

/**
 * 高层业务工具适配器，暴露三个固定工作流和受限 Supervisor，不提供通用流程编辑能力。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AiWorkflowToolAdapter implements ToolAdapter {

    private static final String HEALTH = "workflow.device.health";
    private static final String LOG_DIAGNOSIS = "workflow.log.diagnosis";
    private static final String BUILD_INSTALL = "workflow.build.install.diagnosis";
    private static final String SUPERVISOR = "agent.supervisor.execute";
    private static final String INPUT_REQUEST = "agent.input.request";

    private final AiFixedWorkflowService workflowService;
    private final AiSupervisorAgentService supervisorService;
    private final ObjectMapper objectMapper;
    private final DevBridgeProperties properties;
    private final AgentTaskApplicationService taskApplicationService;

    /**
     * 注入固定工作流服务和 JSON 工具。
     *
     * @param workflowService 固定工作流服务
     * @param objectMapper JSON 工具
     */
    @Autowired
    public AiWorkflowToolAdapter(
            AiFixedWorkflowService workflowService,
            AiSupervisorAgentService supervisorService,
            ObjectMapper objectMapper,
            DevBridgeProperties properties,
            AgentTaskApplicationService taskApplicationService) {
        this.workflowService = workflowService;
        this.supervisorService = supervisorService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.taskApplicationService = taskApplicationService;
    }

    /**
     * 返回固定工作流和 Supervisor 工具定义。
     *
     * @return 工作流工具
     */
    @Override
    public List<Definition> definitions() {
        List<Definition> values = new ArrayList<>();
        values.add(definition(HEALTH, "设备健康检查", "按固定步骤采集设备详情和健康指标，生成评分、证据和建议。",
                "workflow.device.health", AccessMode.READ, healthSchema(), 120_000));
        values.add(definition(LOG_DIAGNOSIS, "实时日志诊断", "采集固定时间窗口日志，识别异常、检索历史知识并确保停止采集进程。",
                "workflow.log.diagnosis", AccessMode.CONTROL, logSchema(), 180_000));
        values.add(definition(BUILD_INSTALL, "构建安装诊断", "在本机受控目录构建 APK，安装并启动到 Android 设备，再采集日志验证。",
                "workflow.build.install", AccessMode.CONTROL, buildSchema(), 600_000));
        values.add(definition(INPUT_REQUEST, "请求用户补充信息", "缺少任务所必需的包名、路径或其它字段时暂停原任务并请求用户输入。",
                "agent.input.request", AccessMode.READ, inputRequestSchema(), 5_000));
        if (properties.getAiFeatures().isMultiAgentEnabled()) {
            values.add(definition(SUPERVISOR, "Supervisor Agent", "按结构化顺序委派后端注册 Agent，并汇总每个 Worker 的执行结果。",
                    "agent.supervisor", AccessMode.CONTROL, supervisorSchema(), 900_000));
        }
        return List.copyOf(values);
    }

    /**
     * 健康和日志流程为低风险；构建安装及 Supervisor 计划按实际最高风险确认。
     */
    @Override
    public RiskDecision assess(CallRequest request, Definition definition) {
        RiskLevel level = SUPERVISOR.equals(request.tool().toolId())
                ? supervisorService.maximumRisk(request.arguments())
                : BUILD_INSTALL.equals(request.tool().toolId()) ? RiskLevel.HIGH : RiskLevel.LOW;
        boolean sensitive = level != RiskLevel.LOW;
        return new RiskDecision(
                level,
                sensitive ? RiskAction.CONFIRM : RiskAction.ALLOW,
                "fixed-workflow-policy",
                SUPERVISOR.equals(request.tool().toolId())
                        ? sensitive ? "SUPERVISOR_PLAN_CONFIRM" : "SUPERVISOR_PLAN_ALLOWED"
                        : sensitive ? "BUILD_INSTALL_CONFIRM" : "READ_DIAGNOSIS_ALLOWED",
                sensitive
                        ? SUPERVISOR.equals(request.tool().toolId())
                                ? "Supervisor 计划包含需要确认的 Worker 操作"
                                : "将执行本机构建命令、读取 APK、安装并启动设备应用"
                        : "固定只读诊断或受控日志采集",
                "", Instant.now());
    }

    /**
     * 子工具自行申请设备和工作目录锁，父流程不重复持锁，避免同任务嵌套锁等待。
     */
    @Override
    public List<AgentResourceRequest> resources(CallRequest request, Definition definition) {
        return List.of();
    }

    /**
     * 按工具 ID 执行固定流程或 Supervisor 委派并返回结构化报告。
     */
    @Override
    public CallResult execute(CallRequest request, Definition definition, RiskDecision decision) {
        Instant started = Instant.now();
        JsonNode output = switch (request.tool().toolId()) {
            case HEALTH -> workflowService.healthCheck(request);
            case LOG_DIAGNOSIS -> workflowService.diagnoseLogs(request);
            case BUILD_INSTALL -> workflowService.buildInstallDiagnose(request);
            case SUPERVISOR -> supervisorService.execute(request);
            case INPUT_REQUEST -> requestInput(request);
            default -> throw new IllegalArgumentException("未知高层业务工具: " + request.tool().toolId());
        };
        String status = output.path("status").asText("");
        boolean failed = SUPERVISOR.equals(request.tool().toolId())
                ? !"SUCCEEDED".equals(status) : "FAILED".equals(status);
        return result(request, decision, started, output, failed);
    }

    /** 保存结构化等待项并返回前端可识别的输入请求。 */
    private JsonNode requestInput(CallRequest request) {
        if (!StringUtils.hasText(request.identity().taskId())) {
            throw new IllegalStateException("请求用户输入必须绑定 Agent Task");
        }
        String inputKey = request.arguments().path("inputKey").asText("").trim();
        String reason = request.arguments().path("reason").asText("").trim();
        taskApplicationService.waitForInput(request.identity().taskId(), inputKey, reason);
        return objectMapper.createObjectNode()
                .put("status", "WAITING_INPUT")
                .put("inputRequired", true)
                .put("inputKey", inputKey)
                .put("reason", reason);
    }

    /**
     * 将任务取消传播到活动日志会话和等待窗口。
     */
    @Override
    public void cancel(CallRequest request, Definition definition) {
        workflowService.cancel(request);
    }

    /**
     * 构造高层业务结果；业务失败保留已完成步骤并进入 FAILED 状态。
     */
    private CallResult result(
            CallRequest request,
            RiskDecision decision,
            Instant started,
            JsonNode output,
            boolean failed) {
        Instant finished = Instant.now();
        Error error = failed ? new Error(
                "WORKFLOW_FAILED", ErrorCategory.EXECUTION, "固定工作流执行失败",
                output.path("failure").asText(""), false, false) : null;
        return new CallResult(
                ToolContract.SCHEMA_VERSION, request.tool(), request.identity().toolCallId(),
                failed ? CallStatus.FAILED : CallStatus.SUCCEEDED,
                decision, new Timing(started, finished, Math.max(0, finished.toEpochMilli() - started.toEpochMilli())),
                new ResultPayload(output, failed ? "固定工作流部分失败" : "固定工作流执行完成", List.of()),
                new Diagnostics(error, new Exit(failed ? 1 : 0, false),
                        new Metrics(0, output.toString().length(), 0, 0),
                        new SideEffect(!HEALTH.equals(request.tool().toolId()), !failed, false)));
    }

    /**
     * 创建单个工作流定义。
     */
    private Definition definition(
            String toolId,
            String displayName,
            String description,
            String capability,
            AccessMode accessMode,
            JsonNode schema,
            long timeoutMs) {
        boolean sensitive = BUILD_INSTALL.equals(toolId);
        IdempotencyMode idempotencyMode = HEALTH.equals(toolId) || INPUT_REQUEST.equals(toolId)
                ? IdempotencyMode.NATURAL : IdempotencyMode.KEYED;
        return new Definition(
                ToolContract.SCHEMA_VERSION,
                new Identity(toolId, displayName, description),
                new Metadata(
                        new Source(SourceKind.BUILT_IN, "fixed-workflow", "1.0.0", "", "IN_PROCESS"),
                        List.of(capability), List.of(Platform.PLATFORM_INDEPENDENT), accessMode,
                        new Idempotency(idempotencyMode, idempotencyMode == IdempotencyMode.KEYED, ""),
                        new RiskProfile(sensitive ? RiskLevel.HIGH : RiskLevel.LOW, true),
                        new ExecutionProfile(timeoutMs, timeoutMs, 128 * 1024, true, false,
                                List.of("device", "workspace"))),
                schema, objectSchema(), true, new Deprecation(false, ""));
    }

    /** 创建设备健康检查 Schema。 */
    private ObjectNode healthSchema() {
        ObjectNode schema = baseSchema();
        addString(schema, "serial", 256);
        addPlatform(schema);
        schema.set("required", objectMapper.createArrayNode().add("serial"));
        return schema;
    }

    /** 创建日志诊断 Schema。 */
    private ObjectNode logSchema() {
        ObjectNode schema = healthSchema();
        addString(schema, "osVersion", 128);
        properties(schema).set("durationSeconds", objectMapper.createObjectNode()
                .put("type", "integer").put("minimum", 1).put("maximum", 60));
        return schema;
    }

    /** 创建跨端构建安装 Schema。 */
    private ObjectNode buildSchema() {
        ObjectNode schema = logSchema();
        addString(schema, "packageName", 255);
        addString(schema, "workspace", 1_024);
        addString(schema, "apkPath", 1_024);
        properties(schema).set("buildArgv", objectMapper.createObjectNode()
                .put("type", "array").put("minItems", 1).put("maxItems", 32)
                .set("items", objectMapper.createObjectNode().put("type", "string").put("maxLength", 1_024)));
        properties(schema).set("logDurationSeconds", objectMapper.createObjectNode()
                .put("type", "integer").put("minimum", 1).put("maximum", 30));
        schema.set("required", objectMapper.createArrayNode()
                .add("serial").add("packageName").add("workspace").add("apkPath").add("buildArgv"));
        return schema;
    }

    /** 创建 Supervisor 结构化委派 Schema。 */
    private ObjectNode supervisorSchema() {
        ObjectNode step = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode stepProperties = objectMapper.createObjectNode();
        stepProperties.set("stepId", objectMapper.createObjectNode()
                .put("type", "string").put("minLength", 1).put("maxLength", 64));
        ObjectNode agentId = objectMapper.createObjectNode().put("type", "string");
        agentId.set("enum", objectMapper.valueToTree(supervisorService.agentIds()));
        stepProperties.set("agentId", agentId);
        stepProperties.set("arguments", objectMapper.createObjectNode()
                .put("type", "object").put("additionalProperties", true));
        stepProperties.set("continueOnFailure", objectMapper.createObjectNode().put("type", "boolean"));
        step.set("properties", stepProperties);
        step.set("required", objectMapper.createArrayNode().add("stepId").add("agentId").add("arguments"));
        ObjectNode schema = baseSchema();
        properties(schema).set("steps", objectMapper.createObjectNode()
                .put("type", "array").put("minItems", 1).put("maxItems", 8).set("items", step));
        schema.set("required", objectMapper.createArrayNode().add("steps"));
        return schema;
    }

    /** 创建等待用户输入工具 Schema。 */
    private ObjectNode inputRequestSchema() {
        ObjectNode schema = baseSchema();
        addString(schema, "inputKey", 128);
        addString(schema, "reason", 500);
        schema.set("required", objectMapper.createArrayNode().add("inputKey").add("reason"));
        return schema;
    }

    /** 增加平台枚举字段。 */
    private void addPlatform(ObjectNode schema) {
        ObjectNode platform = objectMapper.createObjectNode().put("type", "string");
        platform.set("enum", objectMapper.createArrayNode().add("android").add("ios").add("harmony"));
        properties(schema).set("platform", platform);
    }

    /** 增加有界字符串字段。 */
    private void addString(ObjectNode schema, String field, int maxLength) {
        properties(schema).set(field, objectMapper.createObjectNode()
                .put("type", "string").put("minLength", 1).put("maxLength", maxLength));
    }

    /** 返回 Schema properties。 */
    private ObjectNode properties(ObjectNode schema) {
        return (ObjectNode) schema.get("properties");
    }

    /** 创建严格对象 Schema。 */
    private ObjectNode baseSchema() {
        ObjectNode schema = objectSchema();
        schema.set("properties", objectMapper.createObjectNode());
        return schema;
    }

    /** 创建通用对象输出 Schema。 */
    private ObjectNode objectSchema() {
        return objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
    }
}
