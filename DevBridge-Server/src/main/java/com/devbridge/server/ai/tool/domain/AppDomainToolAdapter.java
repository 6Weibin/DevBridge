package com.devbridge.server.ai.tool.domain;

import com.devbridge.server.ai.agent.runtime.AgentResourceKey;
import com.devbridge.server.ai.agent.runtime.AgentResourceMode;
import com.devbridge.server.ai.agent.runtime.AgentResourceRequest;
import com.devbridge.server.ai.agent.runtime.AgentResourceType;
import com.devbridge.server.ai.tool.artifact.ToolArtifactStore;
import com.devbridge.server.ai.tool.artifact.ToolArtifactStore.ArtifactMetadata;
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
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.AppDetail;
import com.devbridge.server.service.AndroidDeviceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * App 领域工具适配器，封装应用查询、安装、卸载、启动、停止和权限读取。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AppDomainToolAdapter implements ToolAdapter {

    private static final String LIST = "app.list";
    private static final String DETAIL = "app.detail.read";
    private static final String INSTALL = "app.install";
    private static final String UNINSTALL = "app.uninstall";
    private static final String LAUNCH = "app.launch";
    private static final String STOP = "app.stop";
    private static final String PERMISSIONS = "app.permissions.read";

    private final AndroidDeviceService androidDeviceService;
    private final ToolArtifactStore artifactStore;
    private final Path temporaryRoot;
    private final ObjectMapper objectMapper;

    /**
     * 注入 Android 应用服务、Artifact Store 和临时目录配置。
     *
     * @param androidDeviceService Android 应用领域服务
     * @param artifactStore Artifact Store
     * @param properties DevBridge 配置
     * @param objectMapper JSON 工具
     */
    public AppDomainToolAdapter(
            AndroidDeviceService androidDeviceService,
            ToolArtifactStore artifactStore,
            DevBridgeProperties properties,
            ObjectMapper objectMapper) {
        this.androidDeviceService = androidDeviceService;
        this.artifactStore = artifactStore;
        this.temporaryRoot = Path.of(properties.getDownloadTempRoot()).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    /**
     * 返回 Android App 领域工具定义。
     *
     * @return 工具定义
     */
    @Override
    public List<Definition> definitions() {
        return List.of(
                definition(LIST, "应用列表", "读取 Android 已安装应用列表。", "device.app.read",
                        AccessMode.READ, RiskLevel.LOW, IdempotencyMode.NATURAL, serialSchema()),
                definition(DETAIL, "应用详情", "读取指定 Android 应用详情。", "device.app.detail.read",
                        AccessMode.READ, RiskLevel.LOW, IdempotencyMode.NATURAL, packageSchema()),
                definition(PERMISSIONS, "应用权限", "读取应用申请和已授权权限。", "device.app.permission.read",
                        AccessMode.READ, RiskLevel.LOW, IdempotencyMode.NATURAL, packageSchema()),
                definition(INSTALL, "安装应用", "从受控 Artifact 安装 APK，并验证期望包名存在。", "device.app.install",
                        AccessMode.CONTROL, RiskLevel.MEDIUM, IdempotencyMode.KEYED, installSchema()),
                definition(UNINSTALL, "卸载应用", "卸载非系统应用，并验证包已消失。", "device.app.uninstall",
                        AccessMode.CONTROL, RiskLevel.MEDIUM, IdempotencyMode.KEYED, packageSchema()),
                definition(LAUNCH, "启动应用", "启动应用主入口并检查进程状态。", "device.app.control",
                        AccessMode.CONTROL, RiskLevel.MEDIUM, IdempotencyMode.VERIFY_REQUIRED, packageSchema()),
                definition(STOP, "停止应用", "强制停止应用并检查进程状态。", "device.app.control",
                        AccessMode.CONTROL, RiskLevel.MEDIUM, IdempotencyMode.VERIFY_REQUIRED, packageSchema()));
    }

    /**
     * 查询类工具直接执行，安装、卸载、启动和停止必须确认。
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
                level, action, "app-domain-policy",
                action == RiskAction.ALLOW ? "APP_READ_ALLOWED" : "APP_CHANGE_CONFIRM",
                action == RiskAction.ALLOW ? "只读应用信息" : "应用状态或安装内容将发生变化",
                "", Instant.now());
    }

    /**
     * 查询使用设备共享锁，修改和控制操作使用设备独占锁。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @return 资源请求
     */
    @Override
    public List<AgentResourceRequest> resources(CallRequest request, Definition definition) {
        AgentResourceMode mode = definition.metadata().accessMode() == AccessMode.READ
                ? AgentResourceMode.SHARED
                : AgentResourceMode.EXCLUSIVE;
        return List.of(new AgentResourceRequest(
                new AgentResourceKey(AgentResourceType.DEVICE, serial(request)), mode));
    }

    /**
     * 调用 Android 应用领域服务并返回结构化后置验证结果。
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
            case LIST -> result(request, decision, started,
                    objectMapper.valueToTree(androidDeviceService.listInstalledApps(serial(request))),
                    "应用列表读取完成", false);
            case DETAIL -> result(request, decision, started,
                    objectMapper.valueToTree(androidDeviceService.getAppDetail(serial(request), packageName(request))),
                    "应用详情读取完成", false);
            case PERMISSIONS -> result(request, decision, started,
                    permissions(androidDeviceService.getAppDetail(serial(request), packageName(request))),
                    "应用权限读取完成", false);
            case INSTALL -> install(request, decision, started);
            case UNINSTALL -> uninstall(request, decision, started);
            case LAUNCH -> control(request, decision, started, true);
            case STOP -> control(request, decision, started, false);
            default -> throw new IllegalArgumentException("未知 App 领域工具: " + request.tool().toolId());
        };
    }

    /**
     * 从 Artifact 物化临时 APK，安装完成后立即删除。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param started 开始时间
     * @return 工具结果
     */
    private CallResult install(CallRequest request, RiskDecision decision, Instant started) {
        String artifactId = requiredText(request, "artifactId");
        ArtifactMetadata metadata = artifactStore.find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("APK Artifact 不存在: " + artifactId));
        validateApkArtifact(metadata);
        Path apk = createTemporaryApk();
        try {
            artifactStore.copyTo(artifactId, apk);
            String packageName = packageName(request);
            androidDeviceService.installApp(serial(request), apk, packageName);
            return result(request, decision, started,
                    objectMapper.createObjectNode().put("packageName", packageName).put("installed", true),
                    "应用安装并验证完成", true);
        } finally {
            deleteQuietly(apk);
        }
    }

    /**
     * 卸载应用并返回后置状态。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param started 开始时间
     * @return 工具结果
     */
    private CallResult uninstall(CallRequest request, RiskDecision decision, Instant started) {
        String packageName = packageName(request);
        androidDeviceService.uninstallApp(serial(request), packageName);
        return result(request, decision, started,
                objectMapper.createObjectNode().put("packageName", packageName).put("installed", false),
                "应用卸载并验证完成", true);
    }

    /**
     * 启动或停止应用并返回进程后置状态。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param started 开始时间
     * @param launch true 启动，false 停止
     * @return 工具结果
     */
    private CallResult control(
            CallRequest request, RiskDecision decision, Instant started, boolean launch) {
        String packageName = packageName(request);
        boolean verified = launch
                ? androidDeviceService.launchApp(serial(request), packageName)
                : androidDeviceService.stopApp(serial(request), packageName);
        ObjectNode output = objectMapper.createObjectNode()
                .put("packageName", packageName)
                .put(launch ? "running" : "stopped", verified);
        return result(request, decision, started, output,
                launch ? "应用启动操作完成" : "应用停止操作完成", true);
    }

    /**
     * 提取权限字段，避免把安装路径等无关详情发送给模型。
     *
     * @param detail 应用详情
     * @return 权限结果
     */
    private ObjectNode permissions(AppDetail detail) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("packageName", detail.packageName());
        output.set("requestedPermissions", objectMapper.valueToTree(detail.requestedPermissions()));
        output.set("grantedPermissions", objectMapper.valueToTree(detail.grantedPermissions()));
        return output;
    }

    /**
     * 构造统一 App 工具结果。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param started 开始时间
     * @param output 输出
     * @param summary 摘要
     * @param sideEffect 是否产生副作用
     * @return 工具结果
     */
    private CallResult result(
            CallRequest request,
            RiskDecision decision,
            Instant started,
            JsonNode output,
            String summary,
            boolean sideEffect) {
        Instant finished = Instant.now();
        return new CallResult(
                ToolContract.SCHEMA_VERSION, request.tool(), request.identity().toolCallId(), CallStatus.SUCCEEDED,
                decision, new Timing(started, finished, Math.max(0, finished.toEpochMilli() - started.toEpochMilli())),
                new ResultPayload(output, summary, List.of()),
                new Diagnostics(null, new Exit(0, false),
                        new Metrics(0, output.toString().length(), 0, 0),
                        new SideEffect(sideEffect, true, sideEffect)));
    }

    /**
     * 创建单个 App 工具定义。
     *
     * @param toolId 工具 ID
     * @param displayName 显示名
     * @param description 描述
     * @param capability 能力标签
     * @param accessMode 访问模式
     * @param riskLevel 风险等级
     * @param idempotencyMode 幂等模式
     * @param schema 输入 Schema
     * @return 工具定义
     */
    private Definition definition(
            String toolId,
            String displayName,
            String description,
            String capability,
            AccessMode accessMode,
            RiskLevel riskLevel,
            IdempotencyMode idempotencyMode,
            JsonNode schema) {
        boolean keyRequired = idempotencyMode == IdempotencyMode.KEYED;
        return new Definition(
                ToolContract.SCHEMA_VERSION,
                new Identity(toolId, displayName, description),
                new Metadata(
                        new Source(SourceKind.DOMAIN_SERVICE, "app-service", "1.0.0", "", "IN_PROCESS"),
                        List.of(capability), List.of(Platform.ANDROID), accessMode,
                        new Idempotency(idempotencyMode, keyRequired, "device.app.detail.read"),
                        new RiskProfile(riskLevel, true),
                        new ExecutionProfile(30_000, 300_000, 128 * 1024, true, false, List.of("device", "app"))),
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
        ObjectNode schema = baseSchema();
        addString(schema, "serial", 256);
        schema.set("required", objectMapper.createArrayNode().add("serial"));
        return schema;
    }

    /**
     * 创建包名 Schema。
     *
     * @return Schema
     */
    private ObjectNode packageSchema() {
        ObjectNode schema = serialSchema();
        addString(schema, "packageName", 255);
        schema.set("required", objectMapper.createArrayNode().add("serial").add("packageName"));
        return schema;
    }

    /**
     * 创建安装 Schema。
     *
     * @return Schema
     */
    private ObjectNode installSchema() {
        ObjectNode schema = packageSchema();
        addString(schema, "artifactId", 64);
        schema.set("required", objectMapper.createArrayNode().add("serial").add("packageName").add("artifactId"));
        return schema;
    }

    /**
     * 创建基础对象 Schema。
     *
     * @return Schema
     */
    private ObjectNode baseSchema() {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.set("properties", objectMapper.createObjectNode());
        return schema;
    }

    /**
     * 增加有界字符串字段。
     *
     * @param schema 对象 Schema
     * @param field 字段名
     * @param maxLength 最大长度
     */
    private void addString(ObjectNode schema, String field, int maxLength) {
        ((ObjectNode) schema.get("properties")).set(
                field, objectMapper.createObjectNode().put("type", "string").put("maxLength", maxLength));
    }

    /**
     * 获取设备序列号并校验上下文绑定。
     *
     * @param request 工具请求
     * @return 序列号
     */
    private String serial(CallRequest request) {
        String serial = requiredText(request, "serial");
        String contextDevice = request.executionContext().deviceId();
        if (!contextDevice.isBlank() && !serial.equals(contextDevice)) {
            throw new IllegalArgumentException("设备参数与执行上下文不一致");
        }
        return serial;
    }

    /**
     * 获取应用包名。
     *
     * @param request 工具请求
     * @return 包名
     */
    private String packageName(CallRequest request) {
        return requiredText(request, "packageName");
    }

    /**
     * 读取必填文本。
     *
     * @param request 工具请求
     * @param field 字段名
     * @return 文本
     */
    private String requiredText(CallRequest request, String field) {
        String value = request.arguments().path(field).asText("").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /**
     * 校验安装 Artifact 媒体类型和大小。
     *
     * @param metadata Artifact 元数据
     */
    private void validateApkArtifact(ArtifactMetadata metadata) {
        String mediaType = metadata.identity().mediaType();
        boolean supported = "application/vnd.android.package-archive".equalsIgnoreCase(mediaType)
                || "application/octet-stream".equalsIgnoreCase(mediaType);
        if (!supported || metadata.storage().sizeBytes() <= 0) {
            throw new IllegalArgumentException("Artifact 不是有效 APK");
        }
    }

    /**
     * 创建受控 APK 临时文件。
     *
     * @return 临时文件
     */
    private Path createTemporaryApk() {
        try {
            Files.createDirectories(temporaryRoot);
            return Files.createTempFile(temporaryRoot, "ai-install-", ".apk");
        } catch (IOException ex) {
            throw new IllegalStateException("APK 临时文件创建失败", ex);
        }
    }

    /**
     * 删除安装临时文件。
     *
     * @param path 临时路径
     */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 安装已结束，临时文件由 Storage Manager 后续清理。
        }
    }
}
