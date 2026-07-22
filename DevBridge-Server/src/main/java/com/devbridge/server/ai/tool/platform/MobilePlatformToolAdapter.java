package com.devbridge.server.ai.tool.platform;

import com.devbridge.server.ai.agent.runtime.AgentResourceKey;
import com.devbridge.server.ai.agent.runtime.AgentResourceMode;
import com.devbridge.server.ai.agent.runtime.AgentResourceRequest;
import com.devbridge.server.ai.agent.runtime.AgentResourceType;
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
import com.devbridge.server.service.HarmonyDeviceService;
import com.devbridge.server.service.IosDeviceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * iOS/libimobiledevice 与 HarmonyOS/HDC 平台 Adapter，提供领域服务之外的平台能力后端。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class MobilePlatformToolAdapter implements ToolAdapter {

    private static final String IOS_DETAIL = "ios.device.detail.read";
    private static final String IOS_LOG_CHECK = "ios.log.capability.check";
    private static final String HARMONY_DETAIL = "harmony.device.detail.read";
    private static final String HARMONY_DIAGNOSE = "harmony.connection.diagnose";

    private final IosDeviceService iosDeviceService;
    private final HarmonyDeviceService harmonyDeviceService;
    private final ObjectMapper objectMapper;

    /**
     * 注入 iOS、HarmonyOS 服务和 JSON 工具。
     *
     * @param iosDeviceService iOS 服务
     * @param harmonyDeviceService HarmonyOS 服务
     * @param objectMapper JSON 工具
     */
    public MobilePlatformToolAdapter(
            IosDeviceService iosDeviceService,
            HarmonyDeviceService harmonyDeviceService,
            ObjectMapper objectMapper) {
        this.iosDeviceService = iosDeviceService;
        this.harmonyDeviceService = harmonyDeviceService;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回 iOS 和 HarmonyOS 平台工具定义。
     *
     * @return 工具定义
     */
    @Override
    public List<Definition> definitions() {
        return List.of(
                definition(IOS_DETAIL, "iOS 设备详情后端", "通过 libimobiledevice 读取 iOS 设备详情。",
                        "device.detail.read", Platform.IOS, "libimobiledevice", serialSchema()),
                definition(IOS_LOG_CHECK, "iOS 日志能力检查", "检查 iOS 设备连接和 idevicesyslog 可用性。",
                        "device.log.status", Platform.IOS, "libimobiledevice", serialSchema()),
                definition(HARMONY_DETAIL, "HarmonyOS 设备详情后端", "通过 HDC 读取 HarmonyOS 设备详情。",
                        "device.detail.read", Platform.HARMONY_OS, "hdc", serialSchema()),
                definition(HARMONY_DIAGNOSE, "HarmonyOS 连接诊断", "通过 HDC 查询目标连接状态。",
                        "device.connection.diagnose", Platform.HARMONY_OS, "hdc", objectSchema()));
    }

    /**
     * 平台详情和连接检查均为低风险只读操作。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @return 风险决策
     */
    @Override
    public RiskDecision assess(CallRequest request, Definition definition) {
        return new RiskDecision(
                RiskLevel.LOW, RiskAction.ALLOW, "mobile-platform-policy", "PLATFORM_READ_ALLOWED",
                "只读平台能力", "", Instant.now());
    }

    /**
     * 设备详情和日志检查持有设备共享锁，连接诊断持有宿主共享锁。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @return 资源请求
     */
    @Override
    public List<AgentResourceRequest> resources(CallRequest request, Definition definition) {
        String value = HARMONY_DIAGNOSE.equals(request.tool().toolId())
                ? "harmony-discovery"
                : serial(request);
        AgentResourceType type = HARMONY_DIAGNOSE.equals(request.tool().toolId())
                ? AgentResourceType.LOCAL_PATH
                : AgentResourceType.DEVICE;
        return List.of(new AgentResourceRequest(
                new AgentResourceKey(type, value), AgentResourceMode.SHARED));
    }

    /**
     * 调用对应平台白名单服务。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @param decision 风险决策
     * @return 工具结果
     */
    @Override
    public CallResult execute(CallRequest request, Definition definition, RiskDecision decision) {
        Instant started = Instant.now();
        JsonNode output = switch (request.tool().toolId()) {
            case IOS_DETAIL -> objectMapper.valueToTree(iosDeviceService.getDetail(serial(request)));
            case IOS_LOG_CHECK -> iosLogCapability(request);
            case HARMONY_DETAIL -> objectMapper.valueToTree(harmonyDeviceService.getDetail(serial(request)));
            case HARMONY_DIAGNOSE -> objectMapper.valueToTree(harmonyDeviceService.diagnoseConnection());
            default -> throw new IllegalArgumentException("未知移动平台工具: " + request.tool().toolId());
        };
        return success(request, decision, started, output);
    }

    /**
     * 检查 iOS 日志所需设备和工具，返回结构化可用性。
     *
     * @param request 工具请求
     * @return 能力结果
     */
    private ObjectNode iosLogCapability(CallRequest request) {
        iosDeviceService.ensureLoggable(serial(request));
        iosDeviceService.idevicesyslogExecutable();
        return objectMapper.createObjectNode().put("supported", true).put("transport", "idevicesyslog");
    }

    /**
     * 构造成功结果。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param started 开始时间
     * @param output 输出
     * @return 工具结果
     */
    private CallResult success(
            CallRequest request, RiskDecision decision, Instant started, JsonNode output) {
        Instant finished = Instant.now();
        return new CallResult(
                ToolContract.SCHEMA_VERSION, request.tool(), request.identity().toolCallId(), CallStatus.SUCCEEDED,
                decision, new Timing(started, finished, Math.max(0, finished.toEpochMilli() - started.toEpochMilli())),
                new ResultPayload(output, "平台能力读取完成", List.of()),
                new Diagnostics(null, new Exit(0, false),
                        new Metrics(0, output.toString().length(), 0, 0),
                        new SideEffect(false, true, false)));
    }

    /**
     * 创建平台工具定义。
     *
     * @param toolId 工具 ID
     * @param displayName 显示名
     * @param description 描述
     * @param capability 能力标签
     * @param platform 平台
     * @param provider 平台提供方
     * @param schema 输入 Schema
     * @return 工具定义
     */
    private Definition definition(
            String toolId,
            String displayName,
            String description,
            String capability,
            Platform platform,
            String provider,
            JsonNode schema) {
        return new Definition(
                ToolContract.SCHEMA_VERSION,
                new Identity(toolId, displayName, description),
                new Metadata(
                        new Source(SourceKind.LOCAL_ADAPTER, provider, "1.0.0", "", "IN_PROCESS"),
                        List.of(capability), List.of(platform), AccessMode.READ,
                        new Idempotency(IdempotencyMode.NATURAL, false, ""),
                        new RiskProfile(RiskLevel.LOW, true),
                        new ExecutionProfile(10_000, 60_000, 64 * 1024, true, false, List.of("device"))),
                schema,
                objectMapper.createObjectNode().put("type", "object"),
                true,
                new Deprecation(false, ""));
    }

    /**
     * 创建设备序列号 Schema。
     *
     * @return Schema
     */
    private ObjectNode serialSchema() {
        ObjectNode schema = objectSchema();
        ((ObjectNode) schema.get("properties")).set(
                "serial", objectMapper.createObjectNode().put("type", "string").put("maxLength", 256));
        schema.set("required", objectMapper.createArrayNode().add("serial"));
        return schema;
    }

    /**
     * 创建空对象 Schema。
     *
     * @return Schema
     */
    private ObjectNode objectSchema() {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.set("properties", objectMapper.createObjectNode());
        return schema;
    }

    /**
     * 读取设备序列号并校验执行上下文绑定。
     *
     * @param request 工具请求
     * @return 序列号
     */
    private String serial(CallRequest request) {
        String serial = request.arguments().path("serial").asText("").trim();
        if (serial.isBlank()) {
            throw new IllegalArgumentException("设备序列号不能为空");
        }
        String contextDevice = request.executionContext().deviceId();
        if (!contextDevice.isBlank() && !serial.equals(contextDevice)) {
            throw new IllegalArgumentException("设备参数与执行上下文不一致");
        }
        return serial;
    }
}
