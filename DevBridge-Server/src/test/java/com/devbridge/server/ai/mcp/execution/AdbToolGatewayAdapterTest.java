package com.devbridge.server.ai.mcp.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.mcp.catalog.AdbToolCatalog;
import com.devbridge.server.ai.mcp.model.AdbMcpToolRequest;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import com.devbridge.server.ai.mcp.risk.AdbRiskClassifier;
import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallStatus;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.Definition;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskAction;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskDecision;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADB Gateway Adapter 测试，覆盖中立定义、动态风险和结果转换。
 *
 * <p>by AI.Coding</p>
 */
class AdbToolGatewayAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AdbToolCatalog catalog = new AdbToolCatalog(objectMapper);
    private final AdbCommandPlanner planner = new AdbCommandPlanner();
    private final AdbRiskClassifier riskClassifier = new AdbRiskClassifier();

    /**
     * 验证 ADB 工具以稳定中立 ID、Android 平台和无 MCP 显示名注册。
     */
    @Test
    void definitionsShouldExposeNeutralMetadata() {
        AdbToolGatewayAdapter adapter = adapter();

        Definition definition = adapter.definitions().stream()
                .filter(value -> value.identity().toolId().equals("android.adb.devices"))
                .findFirst()
                .orElseThrow();

        assertThat(definition.identity().displayName()).isEqualTo("Android 设备查询");
        assertThat(definition.identity().displayName()).doesNotContain("MCP");
        assertThat(definition.metadata().platforms()).containsExactly(Platform.ANDROID);
        assertThat(definition.metadata().source().provider()).isEqualTo("adb");
        assertThat(definition.inputSchema().path("additionalProperties").asBoolean()).isFalse();
    }

    /**
     * 验证删除类 shell 参数仍由现有 ADB 风险分类器识别为确认操作。
     */
    @Test
    void assessShouldReuseAdbDynamicRiskClassifier() {
        AdbToolGatewayAdapter adapter = adapter();
        CallRequest request = request(
                "android.adb.shell",
                objectMapper.createObjectNode()
                        .put("command", "rm")
                        .set("commandArgs", objectMapper.createArrayNode().add("/sdcard/test.txt")));
        Definition definition = adapter.definitions().stream()
                .filter(value -> value.identity().toolId().equals(request.tool().toolId()))
                .findFirst()
                .orElseThrow();

        RiskDecision decision = adapter.assess(request, definition);

        assertThat(decision.action()).isEqualTo(RiskAction.CONFIRM);
        assertThat(decision.reasonSummary()).contains("删除");
    }

    /**
     * 验证旧 ADB 结果转换后不再向上暴露 MCP 标题和确认令牌字段。
     */
    @Test
    void executeShouldReturnNeutralResult() {
        AdbToolGatewayAdapter adapter = adapter();
        CallRequest request = request("android.adb.version", objectMapper.createObjectNode());
        Definition definition = adapter.definitions().stream()
                .filter(value -> value.identity().toolId().equals(request.tool().toolId()))
                .findFirst()
                .orElseThrow();
        RiskDecision decision = adapter.assess(request, definition);

        CallResult result = adapter.execute(request, definition, decision);

        assertThat(result.status()).isEqualTo(CallStatus.SUCCEEDED);
        assertThat(result.payload().output().path("stdout").asText()).contains("Android Debug Bridge");
        assertThat(result.payload().output().has("confirmationToken")).isFalse();
        assertThat(result.payload().output().has("toolTitle")).isFalse();
    }

    /**
     * 创建使用显式 ADB 服务替身的 Adapter。
     *
     * @return ADB Adapter
     */
    private AdbToolGatewayAdapter adapter() {
        return new AdbToolGatewayAdapter(
                catalog,
                planner,
                riskClassifier,
                new FakeAdbToolService(),
                objectMapper);
    }

    /**
     * 创建中立 ADB 请求。
     *
     * @param toolId 工具 ID
     * @param arguments 参数
     * @return 工具请求
     */
    private CallRequest request(String toolId, com.fasterxml.jackson.databind.JsonNode arguments) {
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity("conversation-1", "", "turn-1", "step-1", "call-1", Instant.now()),
                new ToolReference(toolId, ToolContract.SCHEMA_VERSION),
                arguments,
                "digest-1",
                "",
                Caller.AGENT,
                new ExecutionContext(Platform.ANDROID, "serial-1", "", "", List.of()));
    }

    /**
     * 不启动真实 ADB 进程的工具服务替身。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeAdbToolService extends AdbMcpToolService {

        /**
         * 创建空依赖测试服务，执行入口由本类覆盖。
         */
        FakeAdbToolService() {
            super(null, null, null, null, null, null, null, null);
        }

        /**
         * 返回固定成功结果。
         *
         * @param request ADB 请求
         * @return 固定结果
         */
        @Override
        AdbMcpToolResult callApprovedByGateway(AdbMcpToolRequest request) {
            return AdbMcpToolResult.success(
                            "Android Debug Bridge version 1.0.41",
                            "",
                            0,
                            2,
                            false,
                            AdbRiskLevel.LOW)
                    .withToolMetadata("ADB MCP", "adb version");
        }
    }
}
