package com.devbridge.server.ai.mcp.adapter.standard;

import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.Definition;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.devbridge.server.ai.tool.gateway.ToolGateway;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

/**
 * 标准 MCP Server 协议适配器，将中立 Tool Gateway 暴露为 tools/list 和 tools/call。
 *
 * <p>协议上下文使用 `_devbridge` 包装，执行前会从业务参数中移除，领域核心不依赖 MCP SDK。</p>
 *
 * <p>by AI.Coding</p>
 */
@Component
public class StandardMcpToolAdapter implements ToolCallbackProvider {

    private static final String CONTEXT_FIELD = "_devbridge";

    private final ToolGateway toolGateway;
    private final ObjectMapper objectMapper;
    private final ToolCallback[] callbacks;

    /**
     * 从统一注册表构建标准 MCP 工具回调。
     *
     * @param toolGateway 中立工具网关
     * @param objectMapper JSON 工具
     */
    public StandardMcpToolAdapter(ToolGateway toolGateway, ObjectMapper objectMapper) {
        this.toolGateway = toolGateway;
        this.objectMapper = objectMapper;
        this.callbacks = toolGateway.listTools().stream()
                .map(GatewayToolCallback::new)
                .toArray(ToolCallback[]::new);
    }

    /**
     * 返回标准 MCP Server 自动配置可注册的工具集合。
     *
     * @return 工具回调副本
     */
    @Override
    public ToolCallback[] getToolCallbacks() {
        return callbacks.clone();
    }

    /**
     * 单个中立工具的标准 MCP 回调。
     *
     * <p>by AI.Coding</p>
     */
    private class GatewayToolCallback implements ToolCallback {

        private final Definition definition;

        /**
         * 保存当前工具定义快照，保证一次进程生命周期内发现和调用一致。
         *
         * @param definition 中立工具定义
         */
        GatewayToolCallback(Definition definition) {
            this.definition = definition;
        }

        /**
         * 将中立定义转换为 MCP 工具定义，并补充协议级执行上下文。
         *
         * @return MCP 工具定义
         */
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(definition.identity().toolId())
                    .description(definition.identity().description())
                    .inputSchema(mcpInputSchema(definition).toString())
                    .build();
        }

        /**
         * 解析 MCP 参数并通过唯一 Tool Gateway 执行，禁止绕过统一策略流水线。
         *
         * @param toolInput MCP JSON 参数
         * @return 中立工具结果 JSON
         */
        @Override
        public String call(String toolInput) {
            try {
                ObjectNode input = objectInput(toolInput);
                JsonNode context = input.remove(CONTEXT_FIELD);
                CallRequest request = request(definition, input, context);
                return objectMapper.writeValueAsString(toolGateway.call(request));
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException("标准 MCP 工具 JSON 处理失败", ex);
            }
        }
    }

    /**
     * 为标准 MCP 增加协议上下文，不修改中立工具原始输入 Schema。
     *
     * @param definition 中立工具定义
     * @return MCP 输入 Schema
     */
    private ObjectNode mcpInputSchema(Definition definition) {
        ObjectNode schema = definition.inputSchema().deepCopy();
        ObjectNode properties = schema.with("properties");
        properties.set(CONTEXT_FIELD, contextSchema());
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 定义协议上下文字段，调用方可绑定任务、设备、平台、工作区和确认记录。
     *
     * @return 上下文 JSON Schema
     */
    private ObjectNode contextSchema() {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("platform", enumSchema(Platform.values()));
        for (String field : List.of(
                "deviceId", "workspace", "confirmationId", "conversationId", "taskId",
                "turnId", "stepId", "toolCallId", "idempotencyKey")) {
            properties.set(field, objectMapper.createObjectNode().put("type", "string").put("maxLength", 512));
        }
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 创建枚举字符串 Schema。
     *
     * @param values 枚举值
     * @return JSON Schema
     */
    private ObjectNode enumSchema(Platform[] values) {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "string");
        var choices = objectMapper.createArrayNode();
        for (Platform value : values) {
            choices.add(value.name());
        }
        schema.set("enum", choices);
        return schema;
    }

    /**
     * 将标准 MCP 输入转换为对象，拒绝数组、标量和空白外的非法 JSON。
     *
     * @param toolInput 原始 JSON
     * @return 可修改的参数对象
     * @throws JsonProcessingException JSON 解析失败
     */
    private ObjectNode objectInput(String toolInput) throws JsonProcessingException {
        JsonNode value = objectMapper.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException("标准 MCP 工具参数必须是 JSON 对象");
        }
        return object.deepCopy();
    }

    /**
     * 构造中立调用请求；协议上下文缺省时生成独立调用标识。
     *
     * @param definition 工具定义
     * @param arguments 已移除协议上下文的业务参数
     * @param context 协议上下文
     * @return 中立工具请求
     */
    private CallRequest request(Definition definition, ObjectNode arguments, JsonNode context) {
        String callId = text(context, "toolCallId", UUID.randomUUID().toString());
        CallIdentity identity = new CallIdentity(
                text(context, "conversationId", "mcp-" + callId),
                text(context, "taskId", ""),
                text(context, "turnId", "mcp-turn-" + callId),
                text(context, "stepId", "mcp-step-" + callId),
                callId,
                Instant.now());
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                identity,
                new ToolReference(definition.identity().toolId(), definition.schemaVersion()),
                arguments,
                digest(arguments),
                text(context, "idempotencyKey", ""),
                Caller.USER,
                executionContext(definition, arguments, context));
    }

    /**
     * 构造平台、设备、工作区和确认绑定上下文。
     *
     * @param definition 工具定义
     * @param arguments 业务参数
     * @param context 协议上下文
     * @return 执行上下文
     */
    private ExecutionContext executionContext(
            Definition definition, ObjectNode arguments, JsonNode context) {
        String workspace = text(context, "workspace", arguments.path("workingDirectory").asText(""));
        return new ExecutionContext(
                platform(definition, text(context, "platform", "")),
                text(context, "deviceId", ""),
                workspace,
                text(context, "confirmationId", ""),
                List.of());
    }

    /**
     * 解析显式平台；未指定时从单平台定义或当前操作系统推导。
     *
     * @param definition 工具定义
     * @param requestedPlatform 显式平台
     * @return 执行平台
     */
    private Platform platform(Definition definition, String requestedPlatform) {
        if (!requestedPlatform.isBlank()) {
            try {
                return Platform.valueOf(requestedPlatform.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("标准 MCP 平台无效: " + requestedPlatform, ex);
            }
        }
        if (definition.metadata().platforms().size() == 1) {
            return definition.metadata().platforms().get(0);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return Platform.MACOS;
        }
        return os.contains("win") ? Platform.WINDOWS : Platform.LINUX;
    }

    /**
     * 从协议上下文读取文本，空值使用默认值。
     *
     * @param context 上下文节点
     * @param field 字段名
     * @param fallback 默认值
     * @return 规范文本
     */
    private String text(JsonNode context, String field, String fallback) {
        String value = context == null ? "" : context.path(field).asText("").trim();
        return value.isEmpty() ? fallback : value;
    }

    /**
     * 计算实际业务参数摘要，确认和幂等不包含协议包装字段。
     *
     * @param arguments 业务参数
     * @return SHA-256 摘要
     */
    private String digest(JsonNode arguments) {
        try {
            byte[] bytes = arguments.toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }
}
