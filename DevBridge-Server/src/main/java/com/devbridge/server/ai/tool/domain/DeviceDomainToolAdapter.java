package com.devbridge.server.ai.tool.domain;

import com.devbridge.server.ai.agent.runtime.AgentResourceKey;
import com.devbridge.server.ai.agent.runtime.AgentResourceMode;
import com.devbridge.server.ai.agent.runtime.AgentResourceRequest;
import com.devbridge.server.ai.agent.runtime.AgentResourceType;
import com.devbridge.server.ai.tool.artifact.ToolArtifactStore;
import com.devbridge.server.ai.tool.artifact.ToolArtifactStore.ArtifactMetadata;
import com.devbridge.server.ai.tool.artifact.ToolArtifactStore.ArtifactWriteRequest;
import com.devbridge.server.ai.tool.gateway.ToolAdapter;
import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.AccessMode;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallStatus;
import com.devbridge.server.ai.tool.gateway.ToolContract.Definition;
import com.devbridge.server.ai.tool.gateway.ToolContract.Deprecation;
import com.devbridge.server.ai.tool.gateway.ToolContract.Diagnostics;
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
import com.devbridge.server.model.DeviceDetail;
import com.devbridge.server.model.DeviceInfo;
import com.devbridge.server.service.AndroidDeviceService;
import com.devbridge.server.service.DeviceService;
import com.devbridge.server.service.HarmonyDeviceService;
import com.devbridge.server.service.IosDeviceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Device 领域工具适配器，复用设备领域服务并隐藏底层 ADB/libimobiledevice 命令。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class DeviceDomainToolAdapter implements ToolAdapter {

    private static final String LIST = "device.list";
    private static final String DETAIL = "device.detail.read";
    private static final String HEALTH = "device.health.read";
    private static final String SCREENSHOT = "device.screenshot.capture";
    private static final String DIAGNOSE = "device.connection.diagnose";

    private final DeviceService deviceService;
    private final AndroidDeviceService androidDeviceService;
    private final IosDeviceService iosDeviceService;
    private final HarmonyDeviceService harmonyDeviceService;
    private final ToolArtifactStore artifactStore;
    private final ObjectMapper objectMapper;

    /**
     * 注入设备领域服务和 Artifact Store。
     *
     * @param deviceService 跨平台设备列表服务
     * @param androidDeviceService Android 设备服务
     * @param iosDeviceService iOS 设备服务
     * @param harmonyDeviceService HarmonyOS 设备服务
     * @param artifactStore Artifact Store
     * @param objectMapper JSON 工具
     */
    public DeviceDomainToolAdapter(
            DeviceService deviceService,
            AndroidDeviceService androidDeviceService,
            IosDeviceService iosDeviceService,
            HarmonyDeviceService harmonyDeviceService,
            ToolArtifactStore artifactStore,
            ObjectMapper objectMapper) {
        this.deviceService = deviceService;
        this.androidDeviceService = androidDeviceService;
        this.iosDeviceService = iosDeviceService;
        this.harmonyDeviceService = harmonyDeviceService;
        this.artifactStore = artifactStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回平台中立的 Device 领域工具定义。
     *
     * @return 工具定义
     */
    @Override
    public List<Definition> definitions() {
        return List.of(
                definition(LIST, "设备列表", "列出当前连接的移动设备。", List.of("device.read"),
                        List.of(Platform.PLATFORM_INDEPENDENT), AccessMode.READ, RiskLevel.LOW, objectSchema()),
                definition(DETAIL, "设备详情", "读取指定设备的结构化详情。", List.of("device.detail.read"),
                        List.of(Platform.ANDROID, Platform.IOS, Platform.HARMONY_OS),
                        AccessMode.READ, RiskLevel.LOW, serialSchema()),
                definition(HEALTH, "设备健康指标", "读取设备电量、存储和内存等健康指标，不对缺失指标误判。",
                        List.of("device.health.read"), List.of(Platform.ANDROID, Platform.IOS, Platform.HARMONY_OS),
                        AccessMode.READ, RiskLevel.LOW, serialSchema()),
                definition(SCREENSHOT, "设备截图", "截取 Android 当前屏幕并保存为受控 Artifact。",
                        List.of("device.screenshot.capture"), List.of(Platform.ANDROID),
                        AccessMode.READ, RiskLevel.MEDIUM, serialSchema()),
                definition(DIAGNOSE, "设备连接诊断", "诊断后端可见的 Android ADB 连接状态。",
                        List.of("device.connection.diagnose"), List.of(Platform.ANDROID),
                        AccessMode.READ, RiskLevel.LOW, objectSchema()));
    }

    /**
     * Device 领域工具使用固定本地风险；截图涉及屏幕数据，需要用户确认。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @return 风险决策
     */
    @Override
    public RiskDecision assess(CallRequest request, Definition definition) {
        RiskLevel level = definition.metadata().riskProfile().minimumLevel();
        RiskAction action = level == RiskLevel.LOW ? RiskAction.ALLOW : RiskAction.CONFIRM;
        return new RiskDecision(
                level, action, "device-domain-policy",
                action == RiskAction.ALLOW ? "DEVICE_READ_ALLOWED" : "DEVICE_SCREENSHOT_CONFIRM",
                action == RiskAction.ALLOW ? "只读设备领域操作" : "截图可能包含屏幕敏感信息",
                "", Instant.now());
    }

    /**
     * 设备级读取申请共享锁，列表和全局诊断申请宿主共享锁。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @return 资源请求
     */
    @Override
    public List<AgentResourceRequest> resources(CallRequest request, Definition definition) {
        if (LIST.equals(request.tool().toolId()) || DIAGNOSE.equals(request.tool().toolId())) {
            return List.of(new AgentResourceRequest(
                    new AgentResourceKey(AgentResourceType.LOCAL_PATH, "device-discovery"),
                    AgentResourceMode.SHARED));
        }
        return List.of(new AgentResourceRequest(
                new AgentResourceKey(AgentResourceType.DEVICE, serial(request)),
                AgentResourceMode.SHARED));
    }

    /**
     * 调用 Device 领域服务并返回结构化结果或 Artifact 引用。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @param decision 风险决策
     * @return 工具结果
     */
    @Override
    public CallResult execute(CallRequest request, Definition definition, RiskDecision decision) {
        Instant started = Instant.now();
        return switch (request.tool().toolId()) {
            case LIST -> success(request, decision, started,
                    objectMapper.valueToTree(deviceService.listDevices()), "设备列表读取完成", List.of());
            case DETAIL -> success(request, decision, started,
                    objectMapper.valueToTree(detail(request)), "设备详情读取完成", List.of());
            case HEALTH -> success(request, decision, started,
                    health(detail(request)), "设备健康指标读取完成", List.of());
            case SCREENSHOT -> screenshot(request, decision, started);
            case DIAGNOSE -> success(request, decision, started,
                    objectMapper.valueToTree(deviceService.diagnoseAdbDevices()), "设备连接诊断完成", List.of());
            default -> throw new IllegalArgumentException("未知 Device 领域工具: " + request.tool().toolId());
        };
    }

    /**
     * 按执行平台调用对应设备详情服务。
     *
     * @param request 工具请求
     * @return 设备详情
     */
    private DeviceDetail detail(CallRequest request) {
        return switch (request.executionContext().platform()) {
            case ANDROID -> androidDeviceService.getDetail(serial(request));
            case IOS -> iosDeviceService.getDetail(serial(request));
            case HARMONY_OS -> harmonyDeviceService.getDetail(serial(request));
            default -> throw new IllegalArgumentException("设备详情暂不支持平台: "
                    + request.executionContext().platform());
        };
    }

    /**
     * 生成缺失指标友好的健康摘要。
     *
     * @param detail 设备详情
     * @return 健康 JSON
     */
    private ObjectNode health(DeviceDetail detail) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("serial", detail.serial());
        output.put("platform", detail.platform().getValue());
        output.put("battery", detail.battery());
        output.put("storage", detail.storage());
        output.put("ram", detail.ram());
        var observations = objectMapper.createArrayNode();
        if (detail.battery() != null && detail.battery() < 20) {
            observations.add("电量低于 20%");
        }
        boolean hasMetrics = detail.battery() != null
                || StringUtils.hasText(detail.storage())
                || StringUtils.hasText(detail.ram());
        output.put("assessment", !hasMetrics ? "UNKNOWN" : observations.isEmpty() ? "HEALTHY" : "ATTENTION");
        output.set("observations", observations);
        return output;
    }

    /**
     * 截图后立即写入 Artifact，并清理临时下载文件。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param started 开始时间
     * @return 工具结果
     */
    private CallResult screenshot(CallRequest request, RiskDecision decision, Instant started) {
        Path file = androidDeviceService.captureScreenshot(serial(request));
        try {
            ArtifactMetadata metadata = artifactStore.writeFile(new ArtifactWriteRequest(
                    "device-screenshot", "image/png", "SENSITIVE",
                    Instant.now().plus(24, ChronoUnit.HOURS), false, "none"), file);
            ObjectNode output = objectMapper.createObjectNode()
                    .put("artifactId", metadata.identity().artifactId())
                    .put("mediaType", metadata.identity().mediaType());
            return success(request, decision, started, output, "设备截图已保存", List.of(artifactStore.reference(metadata)));
        } finally {
            deleteQuietly(file);
        }
    }

    /**
     * 构造成功结果。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param started 开始时间
     * @param output 结构化输出
     * @param summary 摘要
     * @param artifacts Artifact 引用
     * @return 成功结果
     */
    private CallResult success(
            CallRequest request,
            RiskDecision decision,
            Instant started,
            JsonNode output,
            String summary,
            List<ToolContract.ArtifactReference> artifacts) {
        Instant finished = Instant.now();
        return new CallResult(
                ToolContract.SCHEMA_VERSION, request.tool(), request.identity().toolCallId(), CallStatus.SUCCEEDED,
                decision, new Timing(started, finished, Math.max(0, finished.toEpochMilli() - started.toEpochMilli())),
                new ResultPayload(output, summary, artifacts),
                new Diagnostics(null, new Exit(0, false),
                        new Metrics(0, output == null ? 0 : output.toString().length(), 0, 0),
                        new SideEffect(false, true, false)));
    }

    /**
     * 创建单个 Device 工具定义。
     *
     * @param toolId 工具 ID
     * @param displayName 显示名
     * @param description 描述
     * @param capabilities 能力标签
     * @param platforms 平台
     * @param accessMode 访问模式
     * @param riskLevel 风险等级
     * @param inputSchema 输入 Schema
     * @return 工具定义
     */
    private Definition definition(
            String toolId,
            String displayName,
            String description,
            List<String> capabilities,
            List<Platform> platforms,
            AccessMode accessMode,
            RiskLevel riskLevel,
            JsonNode inputSchema) {
        return new Definition(
                ToolContract.SCHEMA_VERSION,
                new Identity(toolId, displayName, description),
                new Metadata(
                        new Source(SourceKind.DOMAIN_SERVICE, "device-service", "1.0.0", "", "IN_PROCESS"),
                        capabilities,
                        platforms,
                        accessMode,
                        new Idempotency(IdempotencyMode.NATURAL, false, ""),
                        new RiskProfile(riskLevel, true),
                        new ExecutionProfile(10_000, 120_000, 64 * 1024, true, false, List.of("device"))),
                inputSchema,
                objectMapper.createObjectNode().put("type", "object"),
                true,
                new Deprecation(false, ""));
    }

    /**
     * 创建空对象 Schema。
     *
     * @return 输入 Schema
     */
    private ObjectNode objectSchema() {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.set("properties", objectMapper.createObjectNode());
        return schema;
    }

    /**
     * 创建设备序列号 Schema。
     *
     * @return 输入 Schema
     */
    private ObjectNode serialSchema() {
        ObjectNode schema = objectSchema();
        ((ObjectNode) schema.get("properties")).set(
                "serial", objectMapper.createObjectNode().put("type", "string").put("maxLength", 256));
        schema.set("required", objectMapper.createArrayNode().add("serial"));
        return schema;
    }

    /**
     * 读取并校验设备序列号。
     *
     * @param request 工具请求
     * @return 序列号
     */
    private String serial(CallRequest request) {
        String serial = request.arguments().path("serial").asText("").trim();
        if (serial.isEmpty()) {
            throw new IllegalArgumentException("设备序列号不能为空");
        }
        String contextDevice = request.executionContext().deviceId();
        if (StringUtils.hasText(contextDevice) && !serial.equals(contextDevice)) {
            throw new IllegalArgumentException("设备参数与执行上下文不一致");
        }
        return serial;
    }

    /**
     * 清理领域服务生成的临时文件。
     *
     * @param file 临时文件
     */
    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Artifact 已持久化，临时文件由后续 Storage Manager 再清理。
        }
    }
}
