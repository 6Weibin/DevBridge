package com.devbridge.server.ai.localshell.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.config.AiCommandAuthorizationRule;
import com.devbridge.server.ai.config.AiConfigService;
import com.devbridge.server.ai.localshell.catalog.LocalShellToolCatalog;
import com.devbridge.server.ai.localshell.model.LocalShellMcpToolRequest;
import com.devbridge.server.ai.localshell.policy.LocalShellCommandPlanner;
import com.devbridge.server.ai.localshell.policy.LocalShellPolicyService;
import com.devbridge.server.ai.localshell.policy.LocalShellRiskClassifier;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
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
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Local Shell Gateway Adapter 测试，覆盖独立来源、授权风险和中立结果。
 *
 * <p>by AI.Coding</p>
 */
class LocalShellToolGatewayAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 验证本机工具使用独立来源和不含 MCP 的显示名称。
     */
    @Test
    void definitionsShouldExposeIndependentLocalShellSource() {
        LocalShellToolGatewayAdapter adapter = adapter(List.of());

        Definition definition = definition(adapter, "desktop.shell.exec");

        assertThat(definition.metadata().source().provider()).isEqualTo("local-shell");
        assertThat(definition.identity().displayName()).isEqualTo("本机命令执行");
        assertThat(definition.identity().displayName()).doesNotContain("MCP", "ADB");
        assertThat(definition.metadata().platforms())
                .containsExactly(Platform.MACOS, Platform.WINDOWS, Platform.LINUX);
    }

    /**
     * 验证用户配置为高等级的命令会被中立策略表达为 BLOCK。
     */
    @Test
    void assessShouldMapHighAuthorizationToBlock() {
        LocalShellToolGatewayAdapter adapter = adapter(List.of(new AiCommandAuthorizationRule("git status", "HIGH")));
        CallRequest request = request("desktop.shell.exec", execArguments("git", "status"));

        RiskDecision decision = adapter.assess(request, definition(adapter, request.tool().toolId()));

        assertThat(decision.action()).isEqualTo(RiskAction.BLOCK);
        assertThat(decision.reasonSummary()).contains("用户授权规则");
    }

    /**
     * 验证用户显式配置为低等级的命令可以低风险执行，不被目录默认风险错误抬高。
     */
    @Test
    void assessShouldHonorLowAuthorizationForParameterizedCommand() {
        LocalShellToolGatewayAdapter adapter = adapter(List.of(new AiCommandAuthorizationRule("git status", "LOW")));
        CallRequest request = request("desktop.shell.exec", execArguments("git", "status"));
        Definition definition = definition(adapter, request.tool().toolId());

        RiskDecision decision = adapter.assess(request, definition);

        assertThat(definition.metadata().riskProfile().minimumLevel()).isEqualTo(ToolContract.RiskLevel.LOW);
        assertThat(decision.level()).isEqualTo(ToolContract.RiskLevel.LOW);
        assertThat(decision.action()).isEqualTo(RiskAction.ALLOW);
    }

    /**
     * 验证 Local Shell 兼容结果转换为带独立 source 的中立结果。
     */
    @Test
    void executeShouldReturnNeutralLocalShellResult() {
        LocalShellToolGatewayAdapter adapter = adapter(List.of());
        CallRequest request = request("desktop.shell.pwd", objectMapper.createObjectNode());
        Definition definition = definition(adapter, request.tool().toolId());
        RiskDecision decision = adapter.assess(request, definition);

        CallResult result = adapter.execute(request, definition, decision);

        assertThat(result.status()).isEqualTo(CallStatus.SUCCEEDED);
        assertThat(result.payload().output().path("source").asText()).isEqualTo("local-shell");
        assertThat(result.payload().output().has("confirmationToken")).isFalse();
        assertThat(result.payload().output().has("toolTitle")).isFalse();
    }

    /**
     * 创建测试 Adapter。
     *
     * @param rules 用户授权规则
     * @return Adapter
     */
    private LocalShellToolGatewayAdapter adapter(List<AiCommandAuthorizationRule> rules) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.getAiMcpLocalShell().setAllowedWorkingDirectories(List.of(System.getProperty("user.dir")));
        LocalShellPolicyService policy = new LocalShellPolicyService(properties);
        return new LocalShellToolGatewayAdapter(
                new LocalShellToolCatalog(objectMapper),
                new LocalShellCommandPlanner(policy),
                new LocalShellRiskClassifier(properties, new AuthorizationConfigService(rules)),
                new FakeLocalShellToolService(),
                objectMapper);
    }

    /**
     * 按 ID 获取定义。
     *
     * @param adapter Adapter
     * @param toolId 工具 ID
     * @return 工具定义
     */
    private Definition definition(LocalShellToolGatewayAdapter adapter, String toolId) {
        return adapter.definitions().stream()
                .filter(value -> value.identity().toolId().equals(toolId))
                .findFirst()
                .orElseThrow();
    }

    /**
     * 创建 ARGV 参数。
     *
     * @param values 命令参数
     * @return JSON 参数
     */
    private JsonNode execArguments(String... values) {
        var arguments = objectMapper.createObjectNode().put("mode", "ARGV");
        var argv = objectMapper.createArrayNode();
        for (String value : values) {
            argv.add(value);
        }
        arguments.set("argv", argv);
        return arguments;
    }

    /**
     * 创建中立本机工具请求。
     *
     * @param toolId 工具 ID
     * @param arguments 参数
     * @return 请求
     */
    private CallRequest request(String toolId, JsonNode arguments) {
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity("conversation-1", "", "turn-1", "step-1", "call-1", Instant.now()),
                new ToolReference(toolId, ToolContract.SCHEMA_VERSION),
                arguments,
                "digest-1",
                "",
                Caller.AGENT,
                new ExecutionContext(Platform.MACOS, "", System.getProperty("user.dir"), "", List.of()));
    }

    /**
     * 仅返回指定授权规则的配置服务替身。
     *
     * <p>by AI.Coding</p>
     */
    private static class AuthorizationConfigService extends AiConfigService {

        private final List<AiCommandAuthorizationRule> rules;

        /**
         * 创建授权配置替身。
         *
         * @param rules 授权规则
         */
        AuthorizationConfigService(List<AiCommandAuthorizationRule> rules) {
            super(null, null, null);
            this.rules = List.copyOf(rules);
        }

        /**
         * 返回测试授权规则。
         *
         * @return 授权规则
         */
        @Override
        public List<AiCommandAuthorizationRule> localShellAuthorizations() {
            return rules;
        }
    }

    /**
     * 不启动真实本机进程的工具服务替身。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeLocalShellToolService extends LocalShellMcpToolService {

        /**
         * 创建空依赖替身。
         */
        FakeLocalShellToolService() {
            super(null, null, null, null, null, null, null, null);
        }

        /**
         * 返回固定成功结果。
         *
         * @param request Local Shell 请求
         * @return 成功结果
         */
        @Override
        AdbMcpToolResult callApprovedByGateway(LocalShellMcpToolRequest request) {
            return AdbMcpToolResult.success(
                            System.getProperty("user.dir"),
                            "",
                            0,
                            2,
                            false,
                            AdbRiskLevel.LOW)
                    .withToolMetadata("Local Shell MCP", "pwd");
        }
    }
}
