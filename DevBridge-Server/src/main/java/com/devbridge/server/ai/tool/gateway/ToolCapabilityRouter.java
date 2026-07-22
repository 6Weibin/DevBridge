package com.devbridge.server.ai.tool.gateway;

import com.devbridge.server.ai.agent.runtime.AiAgentRegistry;
import com.devbridge.server.ai.agent.runtime.AiAgentRegistry.ModelProfile;
import com.devbridge.server.ai.tool.gateway.ToolContract.AccessMode;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityMetadata;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityQuery;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 结构化能力 Router，按平台、模型、Agent、风险和工具能力生成最小工具集合。
 *
 * <p>Router 不解析用户自然语言关键词；自然语言到结构化计划的转换由受 Schema 约束的模型阶段完成。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class ToolCapabilityRouter {

    private final ToolRegistry toolRegistry;
    private final ToolSchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;
    private final AiAgentRegistry agentRegistry;

    /**
     * 注入统一工具注册表、Schema 校验器和 JSON 工具。
     *
     * @param toolRegistry 工具能力注册表
     * @param schemaValidator Schema 校验器
     * @param objectMapper JSON 工具
     */
    @Autowired
    public ToolCapabilityRouter(
            ToolRegistry toolRegistry,
            ToolSchemaValidator schemaValidator,
            ObjectMapper objectMapper,
            AiAgentRegistry agentRegistry) {
        this.toolRegistry = toolRegistry;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
        this.agentRegistry = agentRegistry;
    }

    /**
     * 兼容直接构造 Router 的旧测试，Agent 仍来自后端默认注册表。
     */
    public ToolCapabilityRouter(
            ToolRegistry toolRegistry,
            ToolSchemaValidator schemaValidator,
            ObjectMapper objectMapper) {
        this(toolRegistry, schemaValidator, objectMapper, new AiAgentRegistry());
    }

    /**
     * 校验并解析模型输出的结构化路由计划。
     *
     * @param structuredPlan 结构化 JSON
     * @return 路由结果
     */
    public RouteDecision route(JsonNode structuredPlan) {
        schemaValidator.validate(routeRequestSchema(), structuredPlan);
        try {
            return route(objectMapper.treeToValue(structuredPlan, RouteRequest.class));
        } catch (Exception ex) {
            throw new IllegalArgumentException("结构化路由计划解析失败", ex);
        }
    }

    /**
     * 解析模型结构化输出，失败时最多调用两次修复函数。
     *
     * @param initialOutput 模型首次输出
     * @param repair 结构化修复函数，入参为有界错误说明和上一轮输出
     * @return 已校验路由结果
     */
    public RouteDecision routeWithRepair(String initialOutput, UnaryOperator<String> repair) {
        String current = initialOutput == null ? "" : initialOutput;
        IllegalArgumentException failure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return route(parseStructuredOutput(current));
            } catch (RuntimeException ex) {
                failure = new IllegalArgumentException("结构化路由输出无效: " + ex.getMessage(), ex);
                if (attempt == 2 || repair == null) {
                    break;
                }
                String bounded = current.length() <= 4_000 ? current : current.substring(0, 4_000);
                current = repair.apply("请只返回符合 JSON Schema 的 JSON。错误="
                        + ex.getMessage() + "；上一轮输出=" + bounded);
            }
        }
        throw new IllegalArgumentException("结构化路由输出修复两次后仍无效", failure);
    }

    /**
     * 提取纯 JSON 或 Markdown fenced JSON，其他自由文本不能进入 Router。
     */
    private JsonNode parseStructuredOutput(String output) {
        String value = output == null ? "" : output.trim();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int closing = value.lastIndexOf("```");
            value = firstLine >= 0 && closing > firstLine
                    ? value.substring(firstLine + 1, closing).trim()
                    : value;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("输出不是合法 JSON", ex);
        }
    }

    /**
     * 根据已校验的结构化请求选择最小工具集合和候选 Agent。
     *
     * @param request 路由请求
     * @return 路由结果
     */
    public RouteDecision route(RouteRequest request) {
        validateRequest(request);
        if (requiresTools(request.executionMode()) && !request.model().toolCalling()) {
            return clarification(request, "MODEL_TOOL_CALLING_UNSUPPORTED");
        }
        List<ToolSelection> selections = new ArrayList<>();
        for (RouteTarget target : request.targets()) {
            ToolSelection selection = select(target, request.maximumRisk());
            if (!selection.missingCapabilities().isEmpty()) {
                return clarification(request, "CAPABILITY_UNAVAILABLE");
            }
            selections.add(selection);
        }
        String agentId = selectAgent(request, selections);
        if (requiresAgent(request.executionMode()) && agentId.isBlank()) {
            return clarification(request, "AGENT_UNAVAILABLE");
        }
        return new RouteDecision(
                request.domain(), request.executionMode(), agentId, request.model().modelId(),
                selections, false, "ROUTED");
    }

    /**
     * 返回供 Router 模型结构化输出使用的 JSON Schema。
     *
     * @return 路由请求 Schema
     */
    public ObjectNode routeRequestSchema() {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("domain", enumSchema(Domain.values()));
        properties.set("executionMode", enumSchema(ExecutionMode.values()));
        properties.set("maximumRisk", enumSchema(RiskLevel.values()));
        properties.set("model", modelSchema());
        properties.set("targets", objectMapper.createObjectNode()
                .put("type", "array")
                .set("items", targetSchema()));
        properties.set("agents", objectMapper.createObjectNode()
                .put("type", "array")
                .set("items", agentSchema()));
        schema.set("properties", properties);
        schema.set("required", array("domain", "executionMode", "maximumRisk", "model", "targets", "agents"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 校验路由请求的必要业务约束。
     *
     * @param request 路由请求
     */
    private void validateRequest(RouteRequest request) {
        if (request == null || request.domain() == null || request.executionMode() == null
                || request.maximumRisk() == null || request.model() == null) {
            throw new IllegalArgumentException("路由请求字段不完整");
        }
        if (request.targets().isEmpty()) {
            throw new IllegalArgumentException("路由目标不能为空");
        }
        for (RouteTarget target : request.targets()) {
            if (target == null || target.platform() == null || !target.connected()
                    || target.requiredCapabilities().isEmpty()) {
                throw new IllegalArgumentException("路由目标平台、连接状态和能力不能为空");
            }
        }
    }

    /**
     * 为单个平台按每项能力查询工具并去重，避免暴露无关工具。
     *
     * @param target 路由目标
     * @param maximumRisk 最大允许静态风险
     * @return 工具选择结果
     */
    private ToolSelection select(RouteTarget target, RiskLevel maximumRisk) {
        Map<String, CapabilityMetadata> selected = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String capability : target.requiredCapabilities()) {
            List<CapabilityMetadata> matches = toolRegistry.capabilities(new CapabilityQuery(
                    target.platform(), List.of(capability), null, false)).stream()
                    .filter(tool -> target.allowedAccessModes().contains(tool.metadata().accessMode()))
                    .filter(tool -> riskOrder(tool.metadata().riskProfile().minimumLevel()) <= riskOrder(maximumRisk))
                    .toList();
            if (matches.isEmpty()) {
                missing.add(capability);
            } else {
                CapabilityMetadata preferred = matches.stream()
                        .sorted(Comparator.comparingInt(this::sourceOrder)
                                .thenComparing(CapabilityMetadata::toolId))
                        .findFirst()
                        .orElseThrow();
                selected.putIfAbsent(preferred.toolId(), preferred);
            }
        }
        return new ToolSelection(
                target.platform(), target.requiredCapabilities(), List.copyOf(selected.values()), missing);
    }

    /**
     * 从已注册候选中选择覆盖领域、平台和能力的最小 Agent。
     *
     * @param request 路由请求
     * @return Agent ID，无候选时返回空串
     */
    private String selectAgent(RouteRequest request, List<ToolSelection> selections) {
        if (!requiresAgent(request.executionMode())) {
            return "";
        }
        Set<Platform> platforms = request.targets().stream()
                .map(RouteTarget::platform)
                .collect(java.util.stream.Collectors.toSet());
        List<CapabilityMetadata> tools = selections.stream()
                .flatMap(selection -> selection.tools().stream())
                .toList();
        ModelProfile model = new ModelProfile(
                request.model().toolCalling(), request.model().streaming(), request.model().multimodal());
        return agentRegistry.candidates(request.domain().name(), model, platforms, tools).stream()
                .map(value -> value.identity().agentId())
                .findFirst()
                .orElse("");
    }

    /**
     * 构造需要补充能力或更换模型的结构化结果。
     *
     * @param request 原始请求
     * @param reasonCode 原因码
     * @return 澄清结果
     */
    private RouteDecision clarification(RouteRequest request, String reasonCode) {
        return new RouteDecision(
                request.domain(), request.executionMode(), "", request.model().modelId(),
                List.of(), true, reasonCode);
    }

    /**
     * 判断执行模式是否需要工具调用能力。
     *
     * @param mode 执行模式
     * @return 需要工具返回 true
     */
    private boolean requiresTools(ExecutionMode mode) {
        return mode != ExecutionMode.CHAT_ONLY;
    }

    /** 固定工作流和动态 Agent 模式必须使用后端注册 Agent。 */
    private boolean requiresAgent(ExecutionMode mode) {
        return mode == ExecutionMode.FIXED_WORKFLOW || mode == ExecutionMode.AGENT;
    }

    /**
     * 计算风险严格程度。
     *
     * @param level 风险等级
     * @return 严格程度
     */
    private int riskOrder(RiskLevel level) {
        return switch (level) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case UNCLASSIFIED -> 3;
        };
    }

    /**
     * 领域服务优先于平台和协议 Adapter，避免同一能力同时暴露多个底层工具。
     *
     * @param metadata 工具能力元数据
     * @return 来源优先级
     */
    private int sourceOrder(CapabilityMetadata metadata) {
        return switch (metadata.metadata().source().kind()) {
            case DOMAIN_SERVICE -> 0;
            case BUILT_IN -> 1;
            case LOCAL_ADAPTER -> 2;
            case STANDARD_MCP -> 3;
            case REMOTE_API -> 4;
        };
    }

    /**
     * 创建字符串枚举 Schema。
     *
     * @param values 枚举值
     * @return JSON Schema
     */
    private ObjectNode enumSchema(Enum<?>[] values) {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "string");
        var choices = objectMapper.createArrayNode();
        for (Enum<?> value : values) {
            choices.add(value.name());
        }
        schema.set("enum", choices);
        return schema;
    }

    /**
     * 创建模型能力 Schema。
     *
     * @return JSON Schema
     */
    private ObjectNode modelSchema() {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("modelId", stringSchema());
        properties.set("toolCalling", booleanSchema());
        properties.set("streaming", booleanSchema());
        properties.set("multimodal", booleanSchema());
        schema.set("properties", properties);
        schema.set("required", array("modelId", "toolCalling", "streaming", "multimodal"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 创建单个目标平台 Schema。
     *
     * @return JSON Schema
     */
    private ObjectNode targetSchema() {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("platform", enumSchema(Platform.values()));
        properties.set("connected", booleanSchema());
        properties.set("requiredCapabilities", stringArraySchema());
        properties.set("allowedAccessModes", enumArraySchema(AccessMode.values()));
        schema.set("properties", properties);
        schema.set("required", array("platform", "connected", "requiredCapabilities", "allowedAccessModes"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 创建候选 Agent Schema。
     *
     * @return JSON Schema
     */
    private ObjectNode agentSchema() {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("agentId", stringSchema());
        properties.set("domains", enumArraySchema(Domain.values()));
        properties.set("platforms", enumArraySchema(Platform.values()));
        properties.set("capabilities", stringArraySchema());
        schema.set("properties", properties);
        schema.set("required", array("agentId", "domains", "platforms", "capabilities"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 创建字符串 Schema。
     *
     * @return JSON Schema
     */
    private ObjectNode stringSchema() {
        return objectMapper.createObjectNode().put("type", "string").put("maxLength", 256);
    }

    /**
     * 创建布尔 Schema。
     *
     * @return JSON Schema
     */
    private ObjectNode booleanSchema() {
        return objectMapper.createObjectNode().put("type", "boolean");
    }

    /**
     * 创建字符串数组 Schema。
     *
     * @return JSON Schema
     */
    private ObjectNode stringArraySchema() {
        return objectMapper.createObjectNode().put("type", "array").set("items", stringSchema());
    }

    /**
     * 创建枚举数组 Schema。
     *
     * @param values 枚举值
     * @return JSON Schema
     */
    private ObjectNode enumArraySchema(Enum<?>[] values) {
        return objectMapper.createObjectNode().put("type", "array").set("items", enumSchema(values));
    }

    /**
     * 创建必填字段数组。
     *
     * @param values 字段名
     * @return JSON 数组
     */
    private JsonNode array(String... values) {
        var array = objectMapper.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    public enum Domain {
        DEVICE_MANAGEMENT, LOG_DIAGNOSIS, APP_MANAGEMENT, FILE_MANAGEMENT, LOCAL_COMPUTER, CROSS_PLATFORM, GENERAL
    }

    public enum ExecutionMode {
        CHAT_ONLY, DIRECT_TOOL, FIXED_WORKFLOW, AGENT
    }

    public record ModelCapability(String modelId, boolean toolCalling, boolean streaming, boolean multimodal) {
    }

    public record AgentCapability(
            String agentId,
            Set<Domain> domains,
            Set<Platform> platforms,
            Set<String> capabilities) {

        /**
         * 固化 Agent 能力集合。
         */
        public AgentCapability {
            domains = domains == null ? Set.of() : Set.copyOf(domains);
            platforms = platforms == null ? Set.of() : Set.copyOf(platforms);
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }
    }

    public record RouteTarget(
            Platform platform,
            boolean connected,
            List<String> requiredCapabilities,
            Set<AccessMode> allowedAccessModes) {

        /**
         * 固化路由目标约束；未指定访问模式时按最小权限只允许读取。
         */
        public RouteTarget {
            requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
            allowedAccessModes = allowedAccessModes == null || allowedAccessModes.isEmpty()
                    ? EnumSet.of(AccessMode.READ)
                    : Set.copyOf(allowedAccessModes);
        }
    }

    public record RouteRequest(
            Domain domain,
            ExecutionMode executionMode,
            RiskLevel maximumRisk,
            ModelCapability model,
            List<RouteTarget> targets,
            List<AgentCapability> agents) {

        /**
         * 固化路由输入集合。
         */
        public RouteRequest {
            targets = targets == null ? List.of() : List.copyOf(targets);
            agents = agents == null ? List.of() : List.copyOf(agents);
        }
    }

    public record ToolSelection(
            Platform platform,
            List<String> requiredCapabilities,
            List<CapabilityMetadata> tools,
            List<String> missingCapabilities) {

        /**
         * 固化工具选择结果。
         */
        public ToolSelection {
            requiredCapabilities = List.copyOf(requiredCapabilities);
            tools = List.copyOf(tools);
            missingCapabilities = List.copyOf(missingCapabilities);
        }
    }

    public record RouteDecision(
            Domain domain,
            ExecutionMode executionMode,
            String agentId,
            String modelId,
            List<ToolSelection> selections,
            boolean requiresClarification,
            String reasonCode) {

        /**
         * 固化路由输出集合。
         */
        public RouteDecision {
            selections = selections == null ? List.of() : List.copyOf(selections);
        }
    }
}
