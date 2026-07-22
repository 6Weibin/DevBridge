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
import com.devbridge.server.model.LogSessionInfo;
import com.devbridge.server.service.LogStreamService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Log 领域工具适配器，统一 AI 与页面实时日志所使用的采集会话和底层进程。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class LogDomainToolAdapter implements ToolAdapter {

    private static final String START = "log.capture.start";
    private static final String READ = "log.capture.read";
    private static final String STOP = "log.capture.stop";
    private static final String STATUS = "log.capture.status";
    private static final String EXPORT = "log.capture.export";
    private static final int MAX_READ_BYTES = 64 * 1024;
    private static final int MAX_READ_LINES = 2000;

    private final LogStreamService logStreamService;
    private final ToolArtifactStore artifactStore;
    private final ObjectMapper objectMapper;

    /**
     * 注入日志会话服务、Artifact Store 和 JSON 工具。
     *
     * @param logStreamService 日志会话服务
     * @param artifactStore Artifact Store
     * @param objectMapper JSON 工具
     */
    public LogDomainToolAdapter(
            LogStreamService logStreamService,
            ToolArtifactStore artifactStore,
            ObjectMapper objectMapper) {
        this.logStreamService = logStreamService;
        this.artifactStore = artifactStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回启动、读取、停止、状态和导出日志工具定义。
     *
     * @return 工具定义
     */
    @Override
    public List<Definition> definitions() {
        List<Platform> platforms = List.of(Platform.ANDROID, Platform.IOS);
        return List.of(
                definition(START, "开始日志采集", "启动指定设备实时日志采集。", "device.log.capture",
                        platforms, AccessMode.CONTROL, IdempotencyMode.KEYED, startSchema()),
                definition(READ, "读取采集日志", "有界读取当前采集会话的最新日志并按级别和文本过滤。",
                        "device.log.read", platforms, AccessMode.READ, IdempotencyMode.NATURAL, readSchema()),
                definition(STOP, "停止日志采集", "停止日志会话和对应底层进程。", "device.log.control",
                        platforms, AccessMode.CONTROL, IdempotencyMode.VERIFY_REQUIRED, sessionSchema()),
                definition(STATUS, "日志采集状态", "查询日志会话和底层进程状态。", "device.log.status",
                        platforms, AccessMode.READ, IdempotencyMode.NATURAL, sessionSchema()),
                definition(EXPORT, "导出采集日志", "停止日志会话并将完整滚动日志保存为 Artifact。",
                        "device.log.export", platforms, AccessMode.CONTROL, IdempotencyMode.KEYED, sessionSchema()));
    }

    /**
     * 日志采集属于受控低风险设备操作，仍受设备资源锁和任务取消治理。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @return 风险决策
     */
    @Override
    public RiskDecision assess(CallRequest request, Definition definition) {
        return new RiskDecision(
                RiskLevel.LOW, RiskAction.ALLOW, "log-domain-policy", "LOG_OPERATION_ALLOWED",
                "受控日志采集操作", "", Instant.now());
    }

    /**
     * 启动、停止和导出使用设备独占锁，读取和状态使用共享锁。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @return 资源锁请求
     */
    @Override
    public List<AgentResourceRequest> resources(CallRequest request, Definition definition) {
        AgentResourceMode mode = definition.metadata().accessMode() == AccessMode.READ
                ? AgentResourceMode.SHARED
                : AgentResourceMode.EXCLUSIVE;
        String resource = request.arguments().path("serial").asText("");
        if (resource.isBlank()) {
            resource = "log-session:" + sessionId(request);
        }
        return List.of(new AgentResourceRequest(
                new AgentResourceKey(AgentResourceType.DEVICE, resource), mode));
    }

    /**
     * 调用统一日志会话服务。
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
            case START -> result(request, decision, started,
                    objectMapper.valueToTree(logStreamService.startCapture(
                            platform(request), serial(request))), "日志采集已启动", true, List.of());
            case READ -> result(request, decision, started,
                    read(request), "日志读取完成", false, List.of());
            case STOP -> result(request, decision, started,
                    objectMapper.valueToTree(logStreamService.stop(sessionId(request))), "日志采集已停止", true, List.of());
            case STATUS -> result(request, decision, started,
                    objectMapper.valueToTree(logStreamService.status(sessionId(request))), "日志状态读取完成", false, List.of());
            case EXPORT -> export(request, decision, started);
            default -> throw new IllegalArgumentException("未知 Log 领域工具: " + request.tool().toolId());
        };
    }

    /**
     * 有界读取滚动日志尾部并应用文本与级别过滤。
     *
     * @param request 工具请求
     * @return 结构化日志结果
     */
    private ObjectNode read(CallRequest request) {
        int maxLines = Math.min(MAX_READ_LINES, Math.max(1, request.arguments().path("maxLines").asInt(500)));
        String filter = request.arguments().path("filter").asText("").toLowerCase(Locale.ROOT);
        String level = request.arguments().path("level").asText("ALL").toUpperCase(Locale.ROOT);
        TailContent tail = readTail(logStreamService.captureFiles(sessionId(request)));
        List<String> lines = tail.text().lines()
                .filter(line -> filter.isBlank() || line.toLowerCase(Locale.ROOT).contains(filter))
                .filter(line -> "ALL".equals(level) || line.matches(".*\\s" + level + "\\s+.*"))
                .toList();
        int start = Math.max(0, lines.size() - maxLines);
        ObjectNode output = objectMapper.createObjectNode();
        output.put("sessionId", sessionId(request));
        output.put("truncated", tail.truncated() || start > 0);
        output.put("returnedLines", lines.size() - start);
        var values = objectMapper.createArrayNode();
        lines.subList(start, lines.size()).forEach(values::add);
        output.set("lines", values);
        return output;
    }

    /**
     * 停止会话并将完整 zip 流式保存为 Artifact。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param started 开始时间
     * @return 工具结果
     */
    private CallResult export(CallRequest request, RiskDecision decision, Instant started) {
        Path zip = logStreamService.exportSession(sessionId(request));
        try {
            ArtifactMetadata metadata = artifactStore.writeFile(new ArtifactWriteRequest(
                    "device-log", "application/zip", "SENSITIVE",
                    Instant.now().plus(7, ChronoUnit.DAYS), false, "none"), zip);
            ObjectNode output = objectMapper.createObjectNode()
                    .put("artifactId", metadata.identity().artifactId())
                    .put("sizeBytes", metadata.storage().sizeBytes());
            return result(request, decision, started, output, "完整日志已导出", true,
                    List.of(artifactStore.reference(metadata)));
        } finally {
            deleteQuietly(zip);
        }
    }

    /**
     * 从最新滚动文件向前读取最多 64 KiB，内存占用固定有界。
     *
     * @param files 滚动文件
     * @return 尾部文本
     */
    private TailContent readTail(List<Path> files) {
        List<byte[]> chunks = new ArrayList<>();
        int remaining = MAX_READ_BYTES;
        long totalBytes = 0;
        List<Path> reversed = new ArrayList<>(files);
        Collections.reverse(reversed);
        for (Path file : reversed) {
            if (remaining == 0 || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                long size = Files.size(file);
                totalBytes += size;
                int length = (int) Math.min(remaining, size);
                chunks.add(0, readFileTail(file, size - length, length));
                remaining -= length;
            } catch (IOException ex) {
                throw new IllegalStateException("日志采集文件读取失败", ex);
            }
        }
        int captured = MAX_READ_BYTES - remaining;
        byte[] combined = new byte[captured];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, combined, offset, chunk.length);
            offset += chunk.length;
        }
        String text = new String(combined, StandardCharsets.UTF_8);
        return new TailContent(text, totalBytes > captured);
    }

    /**
     * 读取单个文件尾部范围。
     *
     * @param file 文件
     * @param offset 起始偏移
     * @param length 长度
     * @return 字节
     * @throws IOException IO 失败
     */
    private byte[] readFileTail(Path file, long offset, int length) throws IOException {
        byte[] bytes = new byte[length];
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // FileChannel 可能分批返回，持续读取到目标长度或 EOF。
            }
        }
        return bytes;
    }

    /**
     * 创建统一工具结果。
     *
     * @param request 工具请求
     * @param decision 风险决策
     * @param started 开始时间
     * @param output 输出
     * @param summary 摘要
     * @param sideEffect 是否产生会话副作用
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
     * 创建 Log 工具定义。
     *
     * @param toolId 工具 ID
     * @param displayName 显示名
     * @param description 描述
     * @param capability 能力标签
     * @param platforms 平台
     * @param accessMode 访问模式
     * @param idempotencyMode 幂等模式
     * @param inputSchema 输入 Schema
     * @return 工具定义
     */
    private Definition definition(
            String toolId,
            String displayName,
            String description,
            String capability,
            List<Platform> platforms,
            AccessMode accessMode,
            IdempotencyMode idempotencyMode,
            JsonNode inputSchema) {
        boolean keyRequired = idempotencyMode == IdempotencyMode.KEYED;
        return new Definition(
                ToolContract.SCHEMA_VERSION,
                new Identity(toolId, displayName, description),
                new Metadata(
                        new Source(SourceKind.DOMAIN_SERVICE, "log-service", "1.0.0", "", "IN_PROCESS"),
                        List.of(capability), platforms, accessMode,
                        new Idempotency(idempotencyMode, keyRequired, "device.log.status"),
                        new RiskProfile(RiskLevel.LOW, true),
                        new ExecutionProfile(10_000, 300_000, MAX_READ_BYTES, true, true, List.of("device"))),
                inputSchema,
                objectMapper.createObjectNode().put("type", "object"),
                true,
                new Deprecation(false, ""));
    }

    /**
     * 创建启动 Schema。
     *
     * @return Schema
     */
    private ObjectNode startSchema() {
        ObjectNode schema = baseSchema();
        addString(schema, "serial", 256);
        schema.set("required", objectMapper.createArrayNode().add("serial"));
        return schema;
    }

    /**
     * 创建会话 Schema。
     *
     * @return Schema
     */
    private ObjectNode sessionSchema() {
        ObjectNode schema = baseSchema();
        addString(schema, "sessionId", 128);
        schema.set("required", objectMapper.createArrayNode().add("sessionId"));
        return schema;
    }

    /**
     * 创建日志读取 Schema。
     *
     * @return Schema
     */
    private ObjectNode readSchema() {
        ObjectNode schema = sessionSchema();
        addString(schema, "filter", 256);
        ObjectNode level = objectMapper.createObjectNode().put("type", "string");
        level.set("enum", objectMapper.createArrayNode().add("ALL").add("V").add("D").add("I").add("W").add("E").add("F"));
        ((ObjectNode) schema.get("properties")).set("level", level);
        ((ObjectNode) schema.get("properties")).set(
                "maxLines", objectMapper.createObjectNode().put("type", "integer").put("minimum", 1).put("maximum", MAX_READ_LINES));
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
     * 向 Schema 增加有界字符串字段。
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
     * 获取设备平台字符串。
     *
     * @param request 工具请求
     * @return 平台字符串
     */
    private String platform(CallRequest request) {
        return switch (request.executionContext().platform()) {
            case ANDROID -> "android";
            case IOS -> "ios";
            default -> throw new IllegalArgumentException("日志采集暂不支持平台: "
                    + request.executionContext().platform());
        };
    }

    /**
     * 获取并校验设备序列号。
     *
     * @param request 工具请求
     * @return 序列号
     */
    private String serial(CallRequest request) {
        String value = request.arguments().path("serial").asText("").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("设备序列号不能为空");
        }
        return value;
    }

    /**
     * 获取并校验日志会话 ID。
     *
     * @param request 工具请求
     * @return 会话 ID
     */
    private String sessionId(CallRequest request) {
        String value = request.arguments().path("sessionId").asText("").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("日志会话 ID 不能为空");
        }
        return value;
    }

    /**
     * 删除导出阶段生成的临时 zip。
     *
     * @param path 临时路径
     */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Artifact 已持久化，临时文件由 Storage Manager 后续清理。
        }
    }

    private record TailContent(String text, boolean truncated) {
    }
}
