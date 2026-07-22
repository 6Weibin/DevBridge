package com.devbridge.server.ai.mcp.adapter.standard;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.devbridge.server.ai.tool.gateway.ToolGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 标准 MCP Adapter 测试，覆盖工具发现和通过中立 Gateway 调用。
 *
 * <p>by AI.Coding</p>
 */
class StandardMcpToolAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * 验证 tools/list 使用稳定 ID，并只在协议 Schema 中增加执行上下文。
     */
    @Test
    void callbacksShouldExposeNeutralToolDefinitions() throws Exception {
        FakeToolGateway gateway = new FakeToolGateway(definition());
        StandardMcpToolAdapter adapter = new StandardMcpToolAdapter(gateway, objectMapper);

        var callback = adapter.getToolCallbacks()[0];
        var schema = objectMapper.readTree(callback.getToolDefinition().inputSchema());

        assertThat(callback.getToolDefinition().name()).isEqualTo("desktop.test.read");
        assertThat(schema.path("properties").has("_devbridge")).isTrue();
        assertThat(gateway.definition.inputSchema().path("properties").has("_devbridge")).isFalse();
    }

    /**
     * 验证 tools/call 会移除协议上下文，并把平台和工作区传入统一 Gateway。
     */
    @Test
    void callShouldDelegateToToolGatewayWithSeparatedContext() throws Exception {
        FakeToolGateway gateway = new FakeToolGateway(definition());
        StandardMcpToolAdapter adapter = new StandardMcpToolAdapter(gateway, objectMapper);
        String input = "{\"query\":\"status\",\"_devbridge\":{\"platform\":\"MACOS\",\"workspace\":\"/tmp\"}}";

        String resultJson = adapter.getToolCallbacks()[0].call(input);

        assertThat(objectMapper.readTree(resultJson).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(gateway.lastRequest.arguments().has("_devbridge")).isFalse();
        assertThat(gateway.lastRequest.arguments().path("query").asText()).isEqualTo("status");
        assertThat(gateway.lastRequest.executionContext().platform()).isEqualTo(Platform.MACOS);
        assertThat(gateway.lastRequest.executionContext().workspace()).isEqualTo("/tmp");
    }

    /**
     * 创建测试工具定义。
     *
     * @return 工具定义
     */
    private Definition definition() {
        var input = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        input.set("properties", objectMapper.createObjectNode().set(
                "query", objectMapper.createObjectNode().put("type", "string")));
        return new Definition(
                ToolContract.SCHEMA_VERSION,
                new Identity("desktop.test.read", "测试读取", "读取测试状态"),
                new Metadata(
                        new Source(SourceKind.BUILT_IN, "test", "1.0.0", "", "IN_PROCESS"),
                        List.of("desktop.test.read"),
                        List.of(Platform.MACOS, Platform.WINDOWS, Platform.LINUX),
                        AccessMode.READ,
                        new Idempotency(IdempotencyMode.NATURAL, false, ""),
                        new RiskProfile(RiskLevel.LOW, true),
                        new ExecutionProfile(1000, 5000, 4096, true, false, List.of())),
                input,
                objectMapper.createObjectNode().put("type", "object"),
                true,
                new Deprecation(false, ""));
    }

    /**
     * 只记录调用请求的中立 Gateway 替身。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeToolGateway extends ToolGateway {

        private final Definition definition;
        private CallRequest lastRequest;

        /**
         * 创建仅包含一个工具的 Gateway 替身。
         *
         * @param definition 工具定义
         */
        FakeToolGateway(Definition definition) {
            super(null, null, null, null, null, null);
            this.definition = definition;
        }

        /**
         * 返回固定工具定义。
         *
         * @return 工具定义
         */
        @Override
        public List<Definition> listTools() {
            return List.of(definition);
        }

        /**
         * 记录请求并返回固定成功结果。
         *
         * @param request 工具请求
         * @return 成功结果
         */
        @Override
        public CallResult call(CallRequest request) {
            lastRequest = request;
            Instant now = Instant.now();
            RiskDecision risk = new RiskDecision(
                    RiskLevel.LOW, RiskAction.ALLOW, "test", "TEST", "测试", "", now);
            return new CallResult(
                    ToolContract.SCHEMA_VERSION,
                    request.tool(),
                    request.identity().toolCallId(),
                    CallStatus.SUCCEEDED,
                    risk,
                    new Timing(now, now, 0),
                    new ResultPayload(null, "成功", List.of()),
                    new Diagnostics(
                            null,
                            new Exit(0, false),
                            new Metrics(0, 0, 0, 0),
                            new SideEffect(false, true, false)));
        }
    }
}
