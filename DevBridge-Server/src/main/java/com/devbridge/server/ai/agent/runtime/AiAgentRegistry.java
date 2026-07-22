package com.devbridge.server.ai.agent.runtime;

import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.DataType;
import com.devbridge.server.ai.tool.gateway.ToolContract.AccessMode;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityMetadata;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskLevel;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 后端权威 Agent 注册表，集中声明 Agent 的领域、工具、模型和数据权限边界。
 *
 * <p>当前注册固定工作流和四个专业 Worker Agent，不承担动态编排。</p>
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AiAgentRegistry {

    private static final String HEALTH_AGENT = "device-health-agent";
    private static final String LOG_AGENT = "log-diagnosis-agent";
    private static final String BUILD_AGENT = "build-install-agent";
    public static final String DEVICE_AGENT = "device-agent";
    public static final String DOMAIN_LOG_AGENT = "log-agent";
    public static final String APP_AGENT = "app-agent";
    public static final String LOCAL_AGENT = "local-agent";
    public static final String VERIFICATION_AGENT = "verification-agent";

    private final Map<String, AgentDefinition> definitions;

    /** 使用当前产品已经可执行的固定工作流创建注册表。 */
    public AiAgentRegistry() {
        this(defaultDefinitions());
    }

    /**
     * 创建指定定义的注册表，供 Router 业务测试复用。
     *
     * @param values Agent 定义
     */
    public AiAgentRegistry(List<AgentDefinition> values) {
        Map<String, AgentDefinition> registered = new LinkedHashMap<>();
        for (AgentDefinition definition : values == null ? List.<AgentDefinition>of() : values) {
            validate(definition);
            String agentId = definition.identity().agentId();
            if (registered.putIfAbsent(agentId, definition) != null) {
                throw new IllegalArgumentException("Agent ID 重复: " + agentId);
            }
        }
        definitions = Map.copyOf(registered);
    }

    /** 返回全部后端注册 Agent。 */
    public List<AgentDefinition> definitions() {
        return definitions.values().stream()
                .sorted(java.util.Comparator.comparing(value -> value.identity().agentId()))
                .toList();
    }

    /**
     * 按当前任务和已选择工具返回具备最小权限的候选 Agent。
     *
     * @param domain 任务领域
     * @param model 当前模型能力
     * @param platforms 目标平台
     * @param tools Router 已选择工具
     * @return 稳定排序候选 Agent
     */
    public List<AgentDefinition> candidates(
            String domain,
            ModelProfile model,
            Set<Platform> platforms,
            List<CapabilityMetadata> tools) {
        Set<Platform> requiredPlatforms = platforms == null ? Set.of() : Set.copyOf(platforms);
        List<CapabilityMetadata> requiredTools = tools == null ? List.of() : List.copyOf(tools);
        return definitions().stream()
                .filter(value -> value.domains().contains(domain))
                .filter(value -> value.platforms().containsAll(requiredPlatforms))
                .filter(value -> supportsModel(value.modelRequirement(), model))
                .filter(value -> requiredTools.stream().allMatch(tool -> allowsEntryTool(value, tool)))
                .toList();
    }

    /**
     * 查询单个 Agent 定义。
     *
     * @param agentId Agent ID
     * @return Agent 定义
     */
    public Optional<AgentDefinition> find(String agentId) {
        return Optional.ofNullable(definitions.get(agentId));
    }

    /**
     * 返回 Agent 在当前候选工具中的授权子集。
     *
     * @param agentId Agent ID
     * @param tools 候选工具
     * @return 允许工具
     */
    public List<CapabilityMetadata> authorizedTools(String agentId, List<CapabilityMetadata> tools) {
        AgentDefinition definition = definitions.get(agentId);
        if (definition == null || tools == null) {
            return List.of();
        }
        return tools.stream().filter(tool -> allowsEntryTool(definition, tool)).toList();
    }

    /**
     * 返回专业 Agent 在候选领域工具中的授权子集。
     *
     * @param agentId Agent ID
     * @param tools 候选领域工具
     * @return Worker 允许工具
     */
    public List<CapabilityMetadata> authorizedWorkerTools(
            String agentId, List<CapabilityMetadata> tools) {
        AgentDefinition definition = definitions.get(agentId);
        if (definition == null || tools == null) {
            return List.of();
        }
        return tools.stream().filter(tool -> allowsWorkerTool(definition, tool)).toList();
    }

    /**
     * 返回 Agent 允许发送给模型的数据类型。
     *
     * @param agentId Agent ID
     * @return 数据权限，未知 Agent 返回空集合
     */
    public Set<DataType> dataPermissions(String agentId) {
        AgentDefinition definition = definitions.get(agentId);
        return definition == null ? Set.of() : definition.dataPermissions();
    }

    /** 校验工具能力、访问模式和静态风险均在 Agent 授权内。 */
    private boolean allowsEntryTool(AgentDefinition definition, CapabilityMetadata tool) {
        return allowsTool(definition, tool, definition.toolPolicy().entryCapabilities());
    }

    /** 校验专业 Worker 内部工具权限。 */
    private boolean allowsWorkerTool(AgentDefinition definition, CapabilityMetadata tool) {
        return allowsTool(definition, tool, definition.toolPolicy().workerCapabilities());
    }

    /** 按指定能力集合校验访问模式和静态风险。 */
    private boolean allowsTool(
            AgentDefinition definition,
            CapabilityMetadata tool,
            Set<String> capabilities) {
        return capabilities.containsAll(tool.metadata().capabilities())
                && definition.toolPolicy().accessModes().contains(tool.metadata().accessMode())
                && riskOrder(tool.metadata().riskProfile().minimumLevel())
                <= riskOrder(definition.toolPolicy().maximumRisk());
    }

    /** 校验模型满足 Agent 的 Tool Calling、流式和多模态要求。 */
    private boolean supportsModel(ModelRequirement required, ModelProfile model) {
        if (model == null) {
            return false;
        }
        return (!required.toolCalling() || model.toolCalling())
                && (!required.streaming() || model.streaming())
                && (!required.multimodal() || model.multimodal());
    }

    /** 校验 Agent 定义包含实际授权边界。 */
    private void validate(AgentDefinition definition) {
        if (definition == null || definition.identity() == null
                || definition.identity().agentId() == null || definition.identity().agentId().isBlank()
                || definition.domains().isEmpty() || definition.platforms().isEmpty()
                || definition.toolPolicy() == null || definition.toolPolicy().entryCapabilities().isEmpty()
                || definition.toolPolicy().accessModes().isEmpty()
                || definition.toolPolicy().maximumRisk() == null || definition.modelRequirement() == null) {
            throw new IllegalArgumentException("Agent 定义字段不完整");
        }
    }

    /** 计算风险等级严格程度。 */
    private int riskOrder(RiskLevel level) {
        return switch (level) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case UNCLASSIFIED -> 3;
        };
    }

    /** 创建当前全部可执行 Agent。 */
    private static List<AgentDefinition> defaultDefinitions() {
        List<AgentDefinition> values = new ArrayList<>(fixedWorkflowDefinitions());
        values.addAll(specialistDefinitions());
        values.add(verificationDefinition());
        return List.copyOf(values);
    }

    /** 创建三个固定工作流 Agent。 */
    private static List<AgentDefinition> fixedWorkflowDefinitions() {
        return List.of(
                definition(
                        HEALTH_AGENT, "设备健康 Agent", "执行设备健康检查固定工作流",
                        "只执行设备健康固定工作流，返回设备证据、异常和建议。",
                        Set.of("DEVICE_MANAGEMENT"),
                        Set.of(Platform.ANDROID, Platform.IOS, Platform.HARMONY_OS),
                        new AgentToolPolicy(
                                Set.of("workflow.device.health"), Set.of(),
                                Set.of(AccessMode.READ), RiskLevel.LOW),
                        Set.of(DataType.USER_MESSAGE, DataType.DEVICE_CONTEXT,
                                DataType.DEVICE_IDENTIFIER, DataType.TOOL_OUTPUT)),
                definition(
                        LOG_AGENT, "日志诊断 Agent", "执行实时日志诊断固定工作流",
                        "只执行实时日志诊断固定工作流，必须在结束后停止采集。",
                        Set.of("LOG_DIAGNOSIS"),
                        Set.of(Platform.ANDROID, Platform.IOS, Platform.HARMONY_OS),
                        new AgentToolPolicy(
                                Set.of("workflow.log.diagnosis"), Set.of(),
                                Set.of(AccessMode.CONTROL), RiskLevel.LOW),
                        Set.of(DataType.USER_MESSAGE, DataType.DEVICE_CONTEXT, DataType.DEVICE_LOG,
                                DataType.DEVICE_IDENTIFIER, DataType.TOOL_OUTPUT)),
                definition(
                        BUILD_AGENT, "构建安装 Agent", "执行电脑构建与 Android 安装诊断固定工作流",
                        "只执行构建、安装、启动和日志验证固定工作流，保留部分成功结果。",
                        Set.of("CROSS_PLATFORM"),
                        Set.of(Platform.ANDROID, Platform.MACOS, Platform.WINDOWS, Platform.LINUX),
                        new AgentToolPolicy(
                                Set.of("workflow.build.install"), Set.of(),
                                Set.of(AccessMode.CONTROL), RiskLevel.HIGH),
                        Set.of(DataType.USER_MESSAGE, DataType.DEVICE_CONTEXT, DataType.DEVICE_LOG,
                                DataType.DEVICE_IDENTIFIER, DataType.TOOL_OUTPUT,
                                DataType.LOCAL_COMMAND_OUTPUT, DataType.FILE_CONTENT, DataType.SOURCE_CODE)));
    }

    /** 创建四个领域隔离的专业 Worker Agent。 */
    private static List<AgentDefinition> specialistDefinitions() {
        return List.of(
                definition(
                        DEVICE_AGENT, "Device Agent", "处理设备信息、健康、截图和连接诊断",
                        "只处理移动设备领域；不得调用日志、应用或本机命令工具。",
                        Set.of("DEVICE_MANAGEMENT"),
                        Set.of(Platform.ANDROID, Platform.IOS, Platform.HARMONY_OS),
                        new AgentToolPolicy(
                                Set.of("agent.device"),
                                Set.of("device.read", "device.detail.read", "device.health.read",
                                        "device.screenshot.capture", "device.connection.diagnose"),
                                Set.of(AccessMode.READ), RiskLevel.MEDIUM),
                        Set.of(DataType.USER_MESSAGE, DataType.DEVICE_CONTEXT,
                                DataType.DEVICE_IDENTIFIER, DataType.TOOL_OUTPUT, DataType.SCREENSHOT)),
                definition(
                        DOMAIN_LOG_AGENT, "Log Agent", "处理设备日志采集、读取、状态和导出",
                        "只处理日志领域；采集结束或失败后必须停止对应进程。",
                        Set.of("LOG_DIAGNOSIS"), Set.of(Platform.ANDROID, Platform.IOS),
                        new AgentToolPolicy(
                                Set.of("agent.log"),
                                Set.of("device.log.capture", "device.log.read", "device.log.control",
                                        "device.log.status", "device.log.export"),
                                Set.of(AccessMode.READ, AccessMode.CONTROL), RiskLevel.LOW),
                        Set.of(DataType.USER_MESSAGE, DataType.DEVICE_CONTEXT, DataType.DEVICE_LOG,
                                DataType.DEVICE_IDENTIFIER, DataType.TOOL_OUTPUT)),
                definition(
                        APP_AGENT, "App Agent", "处理 Android 应用查询、权限和生命周期操作",
                        "只处理 Android 应用领域；写操作必须经过确认和执行后验证。",
                        Set.of("APP_MANAGEMENT"), Set.of(Platform.ANDROID),
                        new AgentToolPolicy(
                                Set.of("agent.app"),
                                Set.of("device.app.read", "device.app.detail.read", "device.app.permission.read",
                                        "device.app.install", "device.app.uninstall", "device.app.control"),
                                Set.of(AccessMode.READ, AccessMode.CONTROL), RiskLevel.MEDIUM),
                        Set.of(DataType.USER_MESSAGE, DataType.DEVICE_CONTEXT, DataType.DEVICE_IDENTIFIER,
                                DataType.APPLICATION_LIST, DataType.FILE_CONTENT, DataType.TOOL_OUTPUT)),
                definition(
                        LOCAL_AGENT, "Local Agent", "处理受控目录、文本、进程和本机命令",
                        "只处理本地电脑领域；命令和路径必须受 Local Shell 策略约束。",
                        Set.of("LOCAL_COMPUTER"),
                        Set.of(Platform.MACOS, Platform.WINDOWS, Platform.LINUX),
                        new AgentToolPolicy(
                                Set.of("agent.local"),
                                Set.of("desktop.file.read", "desktop.file.content.read", "desktop.process.read",
                                        "desktop.process.control", "desktop.shell.execute"),
                                Set.of(AccessMode.READ, AccessMode.CONTROL), RiskLevel.HIGH),
                        Set.of(DataType.USER_MESSAGE, DataType.LOCAL_COMMAND_OUTPUT,
                                DataType.FILE_CONTENT, DataType.SOURCE_CODE, DataType.TOOL_OUTPUT)));
    }

    /** 创建只能调用只读专业 Operation 的证据验证 Agent。 */
    private static AgentDefinition verificationDefinition() {
        return definition(
                VERIFICATION_AGENT, "Verification Agent", "独立检查关键诊断结论的只读证据",
                "只使用 Device、Log 和 App 的只读检查验证结论；证据不足必须明确标记。",
                Set.of("VERIFICATION", "DEVICE_MANAGEMENT", "LOG_DIAGNOSIS", "APP_MANAGEMENT"),
                Set.of(Platform.ANDROID, Platform.IOS, Platform.HARMONY_OS),
                new AgentToolPolicy(
                        Set.of("agent.verification"),
                        Set.of("agent.device", "agent.log", "agent.app"),
                        Set.of(AccessMode.READ), RiskLevel.LOW),
                Set.of(DataType.USER_MESSAGE, DataType.DEVICE_CONTEXT, DataType.DEVICE_LOG,
                        DataType.DEVICE_IDENTIFIER, DataType.APPLICATION_LIST, DataType.TOOL_OUTPUT));
    }

    /** 创建要求 Tool Calling 和流式输出的 Agent。 */
    private static AgentDefinition definition(
            String agentId,
            String displayName,
            String description,
            String instruction,
            Set<String> domains,
            Set<Platform> platforms,
            AgentToolPolicy toolPolicy,
            Set<DataType> dataPermissions) {
        return new AgentDefinition(
                new AgentIdentity(agentId, displayName, description, instruction), domains, platforms,
                toolPolicy, new ModelRequirement(true, true, false), dataPermissions);
    }

    /** Agent 展示身份。by AI.Coding */
    public record AgentIdentity(String agentId, String displayName, String description, String instruction) {
    }

    /** Agent 所需模型能力。by AI.Coding */
    public record ModelRequirement(boolean toolCalling, boolean streaming, boolean multimodal) {
    }

    /** Router 当前模型能力快照。by AI.Coding */
    public record ModelProfile(boolean toolCalling, boolean streaming, boolean multimodal) {
    }

    /** Agent 入口与 Worker 工具权限。by AI.Coding */
    public record AgentToolPolicy(
            Set<String> entryCapabilities,
            Set<String> workerCapabilities,
            Set<AccessMode> accessModes,
            RiskLevel maximumRisk) {

        /** 固化工具权限集合。 */
        public AgentToolPolicy {
            entryCapabilities = entryCapabilities == null ? Set.of() : Set.copyOf(entryCapabilities);
            workerCapabilities = workerCapabilities == null ? Set.of() : Set.copyOf(workerCapabilities);
            accessModes = accessModes == null ? Set.of() : Set.copyOf(accessModes);
        }
    }

    /** Agent 权威定义。by AI.Coding */
    public record AgentDefinition(
            AgentIdentity identity,
            Set<String> domains,
            Set<Platform> platforms,
            AgentToolPolicy toolPolicy,
            ModelRequirement modelRequirement,
            Set<DataType> dataPermissions) {

        /** 固化所有权限集合。 */
        public AgentDefinition {
            domains = domains == null ? Set.of() : Set.copyOf(domains);
            platforms = platforms == null ? Set.of() : Set.copyOf(platforms);
            dataPermissions = dataPermissions == null ? Set.of() : Set.copyOf(dataPermissions);
        }
    }
}
