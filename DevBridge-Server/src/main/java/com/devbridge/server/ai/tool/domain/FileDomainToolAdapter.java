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
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.service.AndroidDeviceService;
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

/**
 * File 领域工具适配器，统一 Android 文件浏览、搜索、预览、传输和安全变更。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class FileDomainToolAdapter implements ToolAdapter {

    private static final String LIST = "file.list";
    private static final String DETAIL = "file.detail.read";
    private static final String SEARCH = "file.search";
    private static final String PREVIEW = "file.preview";
    private static final String PULL = "file.pull";
    private static final String PUSH = "file.push";
    private static final String DELETE = "file.delete";
    private static final String RENAME = "file.rename";
    private static final String COPY = "file.copy";

    private final AndroidDeviceService androidDeviceService;
    private final ToolArtifactStore artifactStore;
    private final Path temporaryRoot;
    private final ObjectMapper objectMapper;

    /**
     * 注入文件领域服务、Artifact Store 和临时目录配置。
     *
     * @param androidDeviceService Android 文件服务
     * @param artifactStore Artifact Store
     * @param properties DevBridge 配置
     * @param objectMapper JSON 工具
     */
    public FileDomainToolAdapter(
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
     * 返回完整 Android File 领域工具定义。
     *
     * @return 工具定义
     */
    @Override
    public List<Definition> definitions() {
        return List.of(
                definition(LIST, "目录列表", "列出远端目录内容。", "device.file.read",
                        AccessMode.READ, RiskLevel.LOW, IdempotencyMode.NATURAL, pathSchema("path")),
                definition(DETAIL, "文件详情", "读取远端文件元数据。", "device.file.detail.read",
                        AccessMode.READ, RiskLevel.LOW, IdempotencyMode.NATURAL, pathSchema("path")),
                definition(SEARCH, "文件搜索", "在指定根目录内按文件名片段搜索。", "device.file.search",
                        AccessMode.READ, RiskLevel.LOW, IdempotencyMode.NATURAL, searchSchema()),
                definition(PREVIEW, "文件预览", "有界预览远端文本文件。", "device.file.preview",
                        AccessMode.READ, RiskLevel.LOW, IdempotencyMode.NATURAL, pathSchema("path")),
                definition(PULL, "拉取文件", "将远端文件保存为受控 Artifact。", "device.file.transfer",
                        AccessMode.READ, RiskLevel.LOW, IdempotencyMode.KEYED, pathSchema("path")),
                definition(PUSH, "上传文件", "从 Artifact 上传文件，默认拒绝覆盖。", "device.file.transfer",
                        AccessMode.WRITE, RiskLevel.MEDIUM, IdempotencyMode.KEYED, pushSchema()),
                definition(DELETE, "删除文件", "删除单个远端文件，不递归删除目录。", "device.file.delete",
                        AccessMode.WRITE, RiskLevel.MEDIUM, IdempotencyMode.KEYED, pathSchema("path")),
                definition(RENAME, "重命名文件", "在同一目录内重命名文件。", "device.file.rename",
                        AccessMode.WRITE, RiskLevel.MEDIUM, IdempotencyMode.KEYED, renameSchema()),
                definition(COPY, "复制文件", "在同一目录创建不覆盖已有文件的副本。", "device.file.copy",
                        AccessMode.WRITE, RiskLevel.MEDIUM, IdempotencyMode.KEYED, pathSchema("path")));
    }

    /**
     * 读取和拉取直接执行，上传、删除、重命名和复制需要确认。
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
                level, action, "file-domain-policy",
                action == RiskAction.ALLOW ? "FILE_READ_ALLOWED" : "FILE_CHANGE_CONFIRM",
                action == RiskAction.ALLOW ? "只读文件操作" : "设备文件内容或名称将发生变化",
                "", Instant.now());
    }

    /**
     * 文件读取使用设备共享锁，变更使用设备独占锁。
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
     * 调用文件领域服务并处理 Artifact 传输。
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
                    objectMapper.valueToTree(androidDeviceService.listFiles(serial(request), path(request))),
                    "目录读取完成", false, List.of());
            case DETAIL -> result(request, decision, started,
                    objectMapper.valueToTree(androidDeviceService.getFileDetail(serial(request), path(request))),
                    "文件详情读取完成", false, List.of());
            case SEARCH -> result(request, decision, started,
                    objectMapper.valueToTree(androidDeviceService.searchFiles(
                            serial(request), requiredText(request, "rootPath"), requiredText(request, "query"),
                            request.arguments().path("maxResults").asInt(100))),
                    "文件搜索完成", false, List.of());
            case PREVIEW -> result(request, decision, started,
                    objectMapper.createObjectNode().put(
                            "content", androidDeviceService.previewTextFile(serial(request), path(request))),
                    "文件预览完成", false, List.of());
            case PULL -> pull(request, decision, started);
            case PUSH -> push(request, decision, started);
            case DELETE -> delete(request, decision, started);
            case RENAME -> rename(request, decision, started);
            case COPY -> result(request, decision, started,
                    objectMapper.valueToTree(androidDeviceService.copyFile(serial(request), path(request))),
                    "文件副本创建完成", true, List.of());
            default -> throw new IllegalArgumentException("未知 File 领域工具: " + request.tool().toolId());
        };
    }

    /**
     * 拉取远端文件并保存为 Artifact。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param started 开始时间
     * @return 工具结果
     */
    private CallResult pull(CallRequest request, RiskDecision decision, Instant started) {
        Path file = androidDeviceService.pullFile(serial(request), path(request));
        try {
            ArtifactMetadata metadata = artifactStore.writeFile(new ArtifactWriteRequest(
                    "device-file", "application/octet-stream", "SENSITIVE",
                    Instant.now().plus(7, ChronoUnit.DAYS), false, "gzip"), file);
            ObjectNode output = objectMapper.createObjectNode()
                    .put("artifactId", metadata.identity().artifactId())
                    .put("sizeBytes", metadata.storage().sizeBytes());
            return result(request, decision, started, output, "文件已保存为 Artifact", false,
                    List.of(artifactStore.reference(metadata)));
        } finally {
            deleteQuietly(file);
        }
    }

    /**
     * 从 Artifact 物化临时文件并上传到设备。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param started 开始时间
     * @return 工具结果
     */
    private CallResult push(CallRequest request, RiskDecision decision, Instant started) {
        String artifactId = requiredText(request, "artifactId");
        Path temporary = createTemporaryFile();
        try {
            artifactStore.copyTo(artifactId, temporary);
            JsonNode output = objectMapper.valueToTree(androidDeviceService.pushFile(
                    serial(request), temporary, requiredText(request, "remoteDirectory"),
                    requiredText(request, "targetName")));
            return result(request, decision, started, output, "文件上传并验证完成", true, List.of());
        } finally {
            deleteQuietly(temporary);
        }
    }

    /**
     * 删除单个文件并返回明确后置状态。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param started 开始时间
     * @return 工具结果
     */
    private CallResult delete(CallRequest request, RiskDecision decision, Instant started) {
        String path = path(request);
        androidDeviceService.deleteFile(serial(request), path);
        return result(request, decision, started,
                objectMapper.createObjectNode().put("path", path).put("deleted", true),
                "文件删除完成", true, List.of());
    }

    /**
     * 重命名文件并返回新详情。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param started 开始时间
     * @return 工具结果
     */
    private CallResult rename(CallRequest request, RiskDecision decision, Instant started) {
        JsonNode output = objectMapper.valueToTree(androidDeviceService.renameFile(
                serial(request), path(request), requiredText(request, "newName")));
        return result(request, decision, started, output, "文件重命名完成", true, List.of());
    }

    /**
     * 构造统一 File 工具结果。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param started 开始时间
     * @param output 输出
     * @param summary 摘要
     * @param sideEffect 是否产生副作用
     * @param artifacts Artifact 引用
     * @return 工具结果
     */
    private CallResult result(
            CallRequest request,
            RiskDecision decision,
            Instant started,
            JsonNode output,
            String summary,
            boolean sideEffect,
            List<ToolContract.ArtifactReference> artifacts) {
        Instant finished = Instant.now();
        return new CallResult(
                ToolContract.SCHEMA_VERSION, request.tool(), request.identity().toolCallId(), CallStatus.SUCCEEDED,
                decision, new Timing(started, finished, Math.max(0, finished.toEpochMilli() - started.toEpochMilli())),
                new ResultPayload(output, summary, artifacts),
                new Diagnostics(null, new Exit(0, false),
                        new Metrics(0, output.toString().length(), 0, 0),
                        new SideEffect(sideEffect, true, sideEffect)));
    }

    /**
     * 创建 File 工具定义。
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
                        new Source(SourceKind.DOMAIN_SERVICE, "file-service", "1.0.0", "", "IN_PROCESS"),
                        List.of(capability), List.of(Platform.ANDROID), accessMode,
                        new Idempotency(idempotencyMode, keyRequired, "device.file.detail.read"),
                        new RiskProfile(riskLevel, true),
                        new ExecutionProfile(30_000, 300_000, 64 * 1024, true, false, List.of("device", "file"))),
                schema,
                objectMapper.createObjectNode().put("type", "object"),
                true,
                new Deprecation(false, ""));
    }

    /**
     * 创建路径 Schema。
     *
     * @param field 路径字段
     * @return Schema
     */
    private ObjectNode pathSchema(String field) {
        ObjectNode schema = serialSchema();
        addString(schema, field, 1024);
        schema.set("required", objectMapper.createArrayNode().add("serial").add(field));
        return schema;
    }

    /**
     * 创建搜索 Schema。
     *
     * @return Schema
     */
    private ObjectNode searchSchema() {
        ObjectNode schema = serialSchema();
        addString(schema, "rootPath", 1024);
        addString(schema, "query", 128);
        ((ObjectNode) schema.get("properties")).set(
                "maxResults", objectMapper.createObjectNode().put("type", "integer").put("minimum", 1).put("maximum", 500));
        schema.set("required", objectMapper.createArrayNode().add("serial").add("rootPath").add("query"));
        return schema;
    }

    /**
     * 创建上传 Schema。
     *
     * @return Schema
     */
    private ObjectNode pushSchema() {
        ObjectNode schema = serialSchema();
        addString(schema, "artifactId", 64);
        addString(schema, "remoteDirectory", 1024);
        addString(schema, "targetName", 255);
        schema.set("required", objectMapper.createArrayNode()
                .add("serial").add("artifactId").add("remoteDirectory").add("targetName"));
        return schema;
    }

    /**
     * 创建重命名 Schema。
     *
     * @return Schema
     */
    private ObjectNode renameSchema() {
        ObjectNode schema = pathSchema("path");
        addString(schema, "newName", 255);
        schema.set("required", objectMapper.createArrayNode().add("serial").add("path").add("newName"));
        return schema;
    }

    /**
     * 创建设备序列号 Schema。
     *
     * @return Schema
     */
    private ObjectNode serialSchema() {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.set("properties", objectMapper.createObjectNode());
        addString(schema, "serial", 256);
        schema.set("required", objectMapper.createArrayNode().add("serial"));
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
     * 读取设备序列号并校验上下文绑定。
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
     * 读取远端路径。
     *
     * @param request 工具请求
     * @return 路径
     */
    private String path(CallRequest request) {
        return requiredText(request, "path");
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
     * 创建上传物化临时文件。
     *
     * @return 临时文件
     */
    private Path createTemporaryFile() {
        try {
            Files.createDirectories(temporaryRoot);
            return Files.createTempFile(temporaryRoot, "ai-file-push-", ".bin");
        } catch (IOException ex) {
            throw new IllegalStateException("文件上传临时文件创建失败", ex);
        }
    }

    /**
     * 删除临时文件。
     *
     * @param path 临时路径
     */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 传输已结束，临时文件由 Storage Manager 后续清理。
        }
    }
}
