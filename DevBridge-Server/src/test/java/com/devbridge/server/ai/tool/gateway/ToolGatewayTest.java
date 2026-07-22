package com.devbridge.server.ai.tool.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.agent.runtime.AgentResourceRequest;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmation;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationBinding;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationCoordinator;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationRequest;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationRiskLevel;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationStatus;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationStore;
import com.devbridge.server.ai.mcp.execution.AiMcpToolEventPublisher;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.ai.security.untrusted.AiUntrustedContentService;
import com.devbridge.server.ai.tool.AiToolRegistry;
import com.devbridge.server.ai.tool.AiToolScope;
import com.devbridge.server.ai.tool.gateway.ToolContract.AccessMode;
import com.devbridge.server.ai.tool.gateway.ToolContract.ArtifactReference;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallStatus;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityQuery;
import com.devbridge.server.ai.tool.gateway.ToolContract.Definition;
import com.devbridge.server.ai.tool.gateway.ToolContract.Deprecation;
import com.devbridge.server.ai.tool.gateway.ToolContract.Diagnostics;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
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
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.devbridge.server.ai.tool.gateway.ToolContract.WorkflowAuthorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

/**
 * 统一 Tool Gateway 测试，覆盖发现、Schema、平台、风险、执行和脱敏流水线。
 *
 * <p>by AI.Coding</p>
 */
class ToolGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 验证低风险工具只执行一次，并统一脱敏 Adapter 返回文本。
     */
    @Test
    void callShouldRunUnifiedPipelineAndMaskResult() {
        FakeAdapter adapter = new FakeAdapter(definition(RiskLevel.LOW), decision(RiskLevel.LOW, RiskAction.ALLOW));
        ToolGateway gateway = gateway(adapter);

        CallResult result = gateway.call(request(Platform.MACOS, objectMapper.createObjectNode().put("query", "ok")));

        assertThat(result.status()).isEqualTo(CallStatus.SUCCEEDED);
        assertThat(result.payload().output().path("text").asText()).doesNotContain("secret-value");
        assertThat(adapter.executions).hasValue(1);
    }

    /** 旧 REST 参数变化必须形成不同任务目标，后续由任务服务按 requestId 拒绝冲突。 */
    @Test
    void legacyRequestIdShouldBindArgumentDigest() {
        LegacyToolGatewayFacade facade = new LegacyToolGatewayFacade(
                null, null, null, null, null, null, null, objectMapper);

        assertThat(facade.idempotencyGoal("android.adb.shell", Map.of("command", "echo one")))
                .isNotEqualTo(facade.idempotencyGoal(
                        "android.adb.shell", Map.of("command", "echo two")));
    }

    /**
     * 验证未知参数在 Adapter 执行前被 Schema 拒绝。
     */
    @Test
    void callShouldRejectUnknownSchemaFields() {
        FakeAdapter adapter = new FakeAdapter(definition(RiskLevel.LOW), decision(RiskLevel.LOW, RiskAction.ALLOW));
        ObjectNode arguments = objectMapper.createObjectNode().put("query", "ok").put("unknown", true);

        assertThatThrownBy(() -> gateway(adapter).call(request(Platform.MACOS, arguments)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知字段");
        assertThat(adapter.executions).hasValue(0);
    }

    /**
     * 验证平台不匹配时不会回退到其他平台命令。
     */
    @Test
    void callShouldRejectUnsupportedPlatform() {
        FakeAdapter adapter = new FakeAdapter(definition(RiskLevel.LOW), decision(RiskLevel.LOW, RiskAction.ALLOW));

        assertThatThrownBy(() -> gateway(adapter).call(request(Platform.ANDROID, objectMapper.createObjectNode().put("query", "ok"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不支持目标平台");
    }

    /**
     * 验证 Adapter 不能把动态风险降到静态风险基线以下。
     */
    @Test
    void callShouldRejectRiskDowngrade() {
        FakeAdapter adapter = new FakeAdapter(definition(RiskLevel.MEDIUM), decision(RiskLevel.LOW, RiskAction.ALLOW));

        assertThatThrownBy(() -> gateway(adapter).call(request(Platform.MACOS, objectMapper.createObjectNode().put("query", "ok"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能低于静态基线");
    }

    /**
     * 验证工作流工具不再因风险动作进入二次确认。
     */
    @Test
    void workflowChildShouldExecuteWithoutConfirmation() {
        FakeAdapter adapter = new FakeAdapter(
                definition(RiskLevel.MEDIUM), decision(RiskLevel.MEDIUM, RiskAction.CONFIRM));
        ToolRegistry registry = new ToolRegistry(List.of(adapter));
        ToolPolicyPipeline policy = new ToolPolicyPipeline(new ToolSchemaValidator());

        var accepted = policy.evaluate(registry.require("desktop.test.read"), workflowRequest("parent-digest"));
        var mismatched = policy.evaluate(registry.require("desktop.test.read"), workflowRequest("other-digest"));

        assertThat(accepted.decision().action()).isEqualTo(RiskAction.ALLOW);
        assertThat(mismatched.decision().action()).isEqualTo(RiskAction.ALLOW);
        assertThat(mismatched.decision().reasonCode()).isEqualTo("DIRECT_EXECUTION_ENABLED");
    }

    /** 高风险工具的 BLOCK 判定仅保留风险等级，不再阻断本机用户执行。 */
    @Test
    void policyShouldConvertBlockedToolToDirectExecution() {
        FakeAdapter adapter = new FakeAdapter(
                definition(RiskLevel.HIGH), decision(RiskLevel.HIGH, RiskAction.BLOCK));
        ToolRegistry registry = new ToolRegistry(List.of(adapter));

        var outcome = new ToolPolicyPipeline(new ToolSchemaValidator())
                .evaluate(registry.require("desktop.test.read"),
                        request(Platform.MACOS, objectMapper.createObjectNode().put("query", "ok")));

        assertThat(outcome.decision().level()).isEqualTo(RiskLevel.HIGH);
        assertThat(outcome.decision().action()).isEqualTo(RiskAction.ALLOW);
    }

    /**
     * 验证旧版同主版本参数必须通过 Adapter 显式迁移后才能执行。
     */
    @Test
    void callShouldMigrateOlderToolSchemaBeforeValidation() {
        FakeAdapter adapter = new FakeAdapter(
                definition("1.1.0", RiskLevel.LOW),
                decision(RiskLevel.LOW, RiskAction.ALLOW),
                true);
        ObjectNode oldArguments = objectMapper.createObjectNode().put("term", "migrated");

        CallResult result = gateway(adapter).call(request("1.0.0", Platform.MACOS, oldArguments));

        assertThat(result.status()).isEqualTo(CallStatus.SUCCEEDED);
        assertThat(adapter.lastArguments.path("query").asText()).isEqualTo("migrated");
        assertThat(adapter.lastToolSchemaVersion).isEqualTo("1.1.0");
    }

    /**
     * 验证未知主版本在 Adapter 和策略执行前被明确拒绝。
     */
    @Test
    void callShouldRejectUnknownMajorToolSchema() {
        FakeAdapter adapter = new FakeAdapter(definition(RiskLevel.LOW), decision(RiskLevel.LOW, RiskAction.ALLOW));

        assertThatThrownBy(() -> gateway(adapter).call(request(
                "2.0.0", Platform.MACOS, objectMapper.createObjectNode().put("query", "ok"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("版本不兼容");
        assertThat(adapter.executions).hasValue(0);
    }

    /**
     * 验证 Router 可按平台、能力和访问模式查询稳定工具元数据。
     */
    @Test
    void listCapabilitiesShouldFilterToolMetadata() {
        FakeAdapter adapter = new FakeAdapter(definition(RiskLevel.LOW), decision(RiskLevel.LOW, RiskAction.ALLOW));

        var items = gateway(adapter).listCapabilities(new CapabilityQuery(
                Platform.MACOS, List.of("desktop.test.read"), AccessMode.READ, false));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).toolId()).isEqualTo("desktop.test.read");
        assertThat(items.get(0).metadata().source().provider()).isEqualTo("test");
        assertThat(items.get(0).schemaVersion()).isEqualTo(ToolContract.SCHEMA_VERSION);
    }

    /**
     * 验证当前 Chat 工具注册表不再直连旧服务，而是调用统一 Tool Gateway 并发布兼容卡片。
     */
    @Test
    void aiToolRegistryShouldCallUnifiedGateway() {
        FakeChatToolGateway gateway = new FakeChatToolGateway(false);
        AiMcpToolEventPublisher publisher = new AiMcpToolEventPublisher();
        AtomicReference<AdbMcpToolResult> published = new AtomicReference<>();
        publisher.register("conversation-chat", published::set);
        AiToolRegistry registry = new AiToolRegistry(
                gateway, publisher, objectMapper, new AiUntrustedContentService(), null);
        ToolCallback callback = registry.toolCallbacks(AiToolScope.LOCAL_DEVELOPMENT, Platform.MACOS)
                .get(0);

        String result = callback.call(
                "{\"query\":\"status\"}",
                new ToolContext(toolContext("conversation-chat", "task-chat", "")));

        assertThat(gateway.requests).hasSize(1);
        assertThat(gateway.requests.get(0).identity().taskId()).isEqualTo("task-chat");
        assertThat(published.get().status().name()).isEqualTo("SUCCESS");
        assertThat(published.get().toolTitle()).isEqualTo("测试查询");
        assertThat(result).contains("UNTRUSTED_CONTENT_ENVELOPE");
    }

    /**
     * 验证批准后的 Gateway 重试复用原步骤和工具调用标识，使确认和幂等校验命中原请求。
     */
    @Test
    void aiToolRegistryShouldReuseConfirmedToolIdentity() {
        FakeChatToolGateway gateway = new FakeChatToolGateway(true);
        AiMcpToolEventPublisher publisher = new AiMcpToolEventPublisher();
        AtomicReference<AdbMcpToolResult> published = new AtomicReference<>();
        publisher.register("conversation-confirm", published::set);
        RecordingConfirmationStore confirmations = new RecordingConfirmationStore();
        AiToolRegistry registry = new AiToolRegistry(
                gateway, publisher, objectMapper, new AiUntrustedContentService(), confirmations);
        ToolCallback callback = registry.toolCallbacks(AiToolScope.LOCAL_DEVELOPMENT, Platform.MACOS)
                .get(0);

        callback.call(
                "{\"query\":\"delete\"}",
                new ToolContext(toolContext("conversation-confirm", "task-confirm", "")));
        String token = published.get().confirmationToken();
        CallRequest original = gateway.requests.get(0);
        confirmations.accept(original, "confirmation-1");
        callback.call(
                "{\"query\":\"delete\"}",
                new ToolContext(toolContext("conversation-confirm", "task-confirm", token)));
        CallRequest resumed = gateway.requests.get(1);

        assertThat(token).startsWith("agent-confirmation:task-confirm:confirmation-1:");
        assertThat(resumed.identity().stepId()).isEqualTo(original.identity().stepId());
        assertThat(resumed.identity().toolCallId()).isEqualTo(original.identity().toolCallId());
        assertThat(resumed.executionContext().confirmationId()).isEqualTo("confirmation-1");
    }

    /**
     * 验证确认令牌不能被其他工具或修改后的参数复用。
     */
    @Test
    void aiToolRegistryShouldRejectConfirmationIdentityForChangedArguments() {
        FakeChatToolGateway gateway = new FakeChatToolGateway(true);
        AiMcpToolEventPublisher publisher = new AiMcpToolEventPublisher();
        AtomicReference<AdbMcpToolResult> published = new AtomicReference<>();
        publisher.register("conversation-confirm", published::set);
        RecordingConfirmationStore confirmations = new RecordingConfirmationStore();
        AiToolRegistry registry = new AiToolRegistry(
                gateway, publisher, objectMapper, new AiUntrustedContentService(), confirmations);
        ToolCallback callback = registry.toolCallbacks(AiToolScope.LOCAL_DEVELOPMENT, Platform.MACOS)
                .get(0);

        callback.call(
                "{\"query\":\"delete\"}",
                new ToolContext(toolContext("conversation-confirm", "task-confirm", "")));
        String token = published.get().confirmationToken();
        CallRequest original = gateway.requests.get(0);
        confirmations.accept(original, "confirmation-1");
        callback.call(
                "{\"query\":\"different\"}",
                new ToolContext(toolContext("conversation-confirm", "task-confirm", token)));
        CallRequest changed = gateway.requests.get(1);

        assertThat(changed.identity().toolCallId()).isNotEqualTo(original.identity().toolCallId());
        assertThat(changed.executionContext().confirmationId()).isEmpty();
    }

    /**
     * 构造 Chat ToolContext。
     *
     * @param conversationId 会话标识
     * @param taskId 任务标识
     * @param confirmationToken 确认令牌
     * @return 上下文 Map
     */
    private Map<String, Object> toolContext(
            String conversationId, String taskId, String confirmationToken) {
        return Map.of(
                "conversationId", conversationId,
                "taskId", taskId,
                "turnId", "turn-chat",
                "stepId", "step-chat",
                "modelCallId", "model-chat",
                "devicePlatform", "macos",
                "confirmationToken", confirmationToken);
    }

    /**
     * 创建测试 Gateway。
     *
     * @param adapter 测试 Adapter
     * @return Gateway
     */
    private ToolGateway gateway(FakeAdapter adapter) {
        ToolRegistry registry = new ToolRegistry(List.of(adapter));
        ToolPolicyPipeline policy = new ToolPolicyPipeline(new ToolSchemaValidator());
        ToolExecutionPipeline execution = new ToolExecutionPipeline(null, null, new SensitiveDataMasker());
        return new ToolGateway(registry, policy, execution, null, null, null);
    }

    /**
     * 创建工具定义。
     *
     * @param baseline 静态风险基线
     * @return 工具定义
     */
    private Definition definition(RiskLevel baseline) {
        return definition(ToolContract.SCHEMA_VERSION, baseline);
    }

    /**
     * 创建指定 Schema 版本的测试工具定义。
     *
     * @param schemaVersion 工具 Schema 版本
     * @param baseline 静态风险基线
     * @return 工具定义
     */
    private Definition definition(String schemaVersion, RiskLevel baseline) {
        ObjectNode input = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        input.set("properties", objectMapper.createObjectNode().set(
                "query", objectMapper.createObjectNode().put("type", "string").put("maxLength", 64)));
        input.set("required", objectMapper.createArrayNode().add("query"));
        Metadata metadata = new Metadata(
                new Source(SourceKind.BUILT_IN, "test", "1.0.0", "", "IN_PROCESS"),
                List.of("desktop.test.read"),
                List.of(Platform.MACOS),
                AccessMode.READ,
                new Idempotency(IdempotencyMode.NATURAL, false, ""),
                new RiskProfile(baseline, true),
                new ExecutionProfile(1000, 5000, 4096, true, false, List.of()));
        return new Definition(
                schemaVersion,
                new Identity("desktop.test.read", "测试查询", "读取测试数据"),
                metadata,
                input,
                objectMapper.createObjectNode().put("type", "object"),
                true,
                new Deprecation(false, ""));
    }

    /**
     * 创建测试调用请求。
     *
     * @param platform 平台
     * @param arguments 参数
     * @return 调用请求
     */
    private CallRequest request(Platform platform, ObjectNode arguments) {
        return request(ToolContract.SCHEMA_VERSION, platform, arguments);
    }

    /**
     * 创建指定工具 Schema 版本的测试调用请求。
     *
     * @param toolSchemaVersion 调用方记录的工具版本
     * @param platform 平台
     * @param arguments 参数
     * @return 调用请求
     */
    private CallRequest request(String toolSchemaVersion, Platform platform, ObjectNode arguments) {
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity("conversation-1", "", "turn-1", "step-1", "call-1", Instant.now()),
                new ToolReference("desktop.test.read", toolSchemaVersion),
                arguments,
                "digest-1",
                "",
                Caller.AGENT,
                new ExecutionContext(platform, "", "", "", List.of()));
    }

    /**
     * 创建携带父工作流授权绑定的子工具请求。
     *
     * @param parentDigest 父工作流参数摘要
     * @return 工作流子请求
     */
    private CallRequest workflowRequest(String parentDigest) {
        ObjectNode arguments = objectMapper.createObjectNode().put("query", "ok");
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity(
                        "conversation-workflow", "task-workflow", "turn-workflow",
                        "step-child", "call-child", Instant.now()),
                new ToolReference("desktop.test.read", ToolContract.SCHEMA_VERSION),
                arguments, "child-digest", "workflow-child", Caller.WORKFLOW,
                new ExecutionContext(
                        Platform.MACOS, "", "", "confirmation-parent", List.of(),
                        new WorkflowAuthorization(
                                "workflow.build.install.diagnosis", "step-parent",
                                "call-parent", parentDigest)));
    }

    /**
     * 创建风险决策。
     *
     * @param level 风险等级
     * @param action 策略动作
     * @return 风险决策
     */
    private RiskDecision decision(RiskLevel level, RiskAction action) {
        return new RiskDecision(level, action, "test-rule", "TEST", "测试策略", "", Instant.now());
    }

    /**
     * 显式测试 Adapter，记录实际执行次数。
     *
     * <p>by AI.Coding</p>
     */
    private class FakeAdapter implements ToolAdapter {

        private final Definition definition;
        private final RiskDecision decision;
        private final boolean supportsMigration;
        private final AtomicInteger executions = new AtomicInteger();
        private JsonNode lastArguments;
        private String lastToolSchemaVersion;

        /**
         * 创建测试 Adapter。
         *
         * @param definition 工具定义
         * @param decision 风险决策
         */
        FakeAdapter(Definition definition, RiskDecision decision) {
            this(definition, decision, false);
        }

        /**
         * 创建可选支持旧版参数迁移的测试 Adapter。
         *
         * @param definition 工具定义
         * @param decision 风险决策
         * @param supportsMigration 是否支持迁移
         */
        FakeAdapter(Definition definition, RiskDecision decision, boolean supportsMigration) {
            this.definition = definition;
            this.decision = decision;
            this.supportsMigration = supportsMigration;
        }

        /**
         * 返回唯一测试工具。
         *
         * @return 工具定义
         */
        @Override
        public List<Definition> definitions() {
            return List.of(definition);
        }

        /**
         * 将测试旧字段 term 显式迁移为当前字段 query。
         *
         * @param sourceSchemaVersion 旧版本
         * @param value 当前定义
         * @param arguments 旧参数
         * @return 当前参数
         */
        @Override
        public JsonNode migrateArguments(String sourceSchemaVersion, Definition value, JsonNode arguments) {
            if (!supportsMigration) {
                return ToolAdapter.super.migrateArguments(sourceSchemaVersion, value, arguments);
            }
            return objectMapper.createObjectNode().put("query", arguments.path("term").asText());
        }

        /**
         * 返回固定测试风险。
         *
         * @param request 工具请求
         * @param value 工具定义
         * @return 风险决策
         */
        @Override
        public RiskDecision assess(CallRequest request, Definition value) {
            return decision;
        }

        /**
         * 测试工具不申请资源。
         *
         * @param request 工具请求
         * @param value 工具定义
         * @return 空资源
         */
        @Override
        public List<AgentResourceRequest> resources(CallRequest request, Definition value) {
            return List.of();
        }

        /**
         * 返回包含敏感文本的成功结果，验证 Gateway 统一脱敏。
         *
         * @param request 工具请求
         * @param value 工具定义
         * @param risk 风险决策
         * @return 工具结果
         */
        @Override
        public CallResult execute(CallRequest request, Definition value, RiskDecision risk) {
            executions.incrementAndGet();
            lastArguments = request.arguments();
            lastToolSchemaVersion = request.tool().schemaVersion();
            Instant now = Instant.now();
            ObjectNode output = objectMapper.createObjectNode().put("text", "token=secret-value");
            return new CallResult(
                    ToolContract.SCHEMA_VERSION,
                    request.tool(),
                    request.identity().toolCallId(),
                    CallStatus.SUCCEEDED,
                    risk,
                    new Timing(now, now, 1),
                    new ResultPayload(output, "执行成功", List.<ArtifactReference>of()),
                    new Diagnostics(
                            null,
                            new Exit(0, false),
                            new Metrics(10, 20, 0, 0),
                            new SideEffect(false, true, false)));
        }
    }

    /**
     * 模拟 Chat ToolCallback 所需的统一 Gateway，记录请求并可返回确认流程。
     *
     * <p>by AI.Coding</p>
     */
    private class FakeChatToolGateway extends ToolGateway {

        private final boolean confirmationFlow;
        private final List<CallRequest> requests = new java.util.ArrayList<>();

        /**
         * 创建 Gateway Fake。
         *
         * @param confirmationFlow 是否先返回待确认
         */
        FakeChatToolGateway(boolean confirmationFlow) {
            super(null, null, null, null, null, null);
            this.confirmationFlow = confirmationFlow;
        }

        /**
         * 返回单个测试工具定义。
         *
         * @return 定义
         */
        @Override
        public List<Definition> listTools() {
            return List.of(definition(RiskLevel.LOW));
        }

        /**
         * 记录请求；确认场景首次等待，携带确认标识的第二次调用成功。
         *
         * @param request 工具请求
         * @return 工具结果
         */
        @Override
        public CallResult call(CallRequest request) {
            requests.add(request);
            boolean waiting = confirmationFlow && request.executionContext().confirmationId().isBlank();
            RiskDecision risk = new RiskDecision(
                    waiting ? RiskLevel.MEDIUM : RiskLevel.LOW,
                    waiting ? RiskAction.CONFIRM : RiskAction.ALLOW,
                    "test-policy",
                    waiting ? "CONFIRM" : "ALLOW",
                    waiting ? "需要确认" : "允许执行",
                    waiting ? "confirmation-1" : request.executionContext().confirmationId(),
                    Instant.now());
            ObjectNode output = objectMapper.createObjectNode()
                    .put("stdout", waiting ? "" : "done")
                    .put("commandSummary", "test command");
            Instant now = Instant.now();
            return new CallResult(
                    ToolContract.SCHEMA_VERSION,
                    request.tool(),
                    request.identity().toolCallId(),
                    waiting ? CallStatus.WAITING_CONFIRMATION : CallStatus.SUCCEEDED,
                    risk,
                    new Timing(now, now, 1),
                    new ResultPayload(output, waiting ? "等待确认" : "执行成功", List.of()),
                    new Diagnostics(
                            null,
                            new Exit(waiting ? null : 0, false),
                            new Metrics(0, output.toString().length(), 0, 0),
                            new SideEffect(false, !waiting, false)));
        }
    }

    /**
     * 保存单条已接受确认的显式测试 Store。
     *
     * <p>by AI.Coding</p>
     */
    private static class RecordingConfirmationStore implements AgentConfirmationStore {

        private AgentConfirmation confirmation;

        /**
         * 根据原工具请求创建已接受确认绑定。
         *
         * @param request 原工具请求
         * @param confirmationId 确认标识
         */
        void accept(CallRequest request, String confirmationId) {
            Instant now = Instant.now();
            AgentConfirmationBinding binding = new AgentConfirmationBinding(
                    request.identity().stepId(), request.identity().toolCallId(),
                    request.tool().toolId(), request.argumentDigest(),
                    AgentConfirmationRiskLevel.MEDIUM, "测试确认");
            confirmation = new AgentConfirmation(
                    confirmationId, request.identity().taskId(), binding,
                    AgentConfirmationStatus.ACCEPTED, now, now.plusSeconds(120), now, "用户已确认");
        }

        /**
         * 创建已接受的高风险父工作流确认。
         *
         * @param taskId 任务标识
         * @param confirmationId 确认标识
         */
        void acceptParent(String taskId, String confirmationId) {
            Instant now = Instant.now();
            AgentConfirmationBinding binding = new AgentConfirmationBinding(
                    "step-parent", "call-parent", "workflow.build.install.diagnosis",
                    "parent-digest", AgentConfirmationRiskLevel.HIGH, "跨端工作流确认");
            confirmation = new AgentConfirmation(
                    confirmationId, taskId, binding, AgentConfirmationStatus.ACCEPTED,
                    now, now.plusSeconds(120), now, "用户已确认");
        }

        /**
         * 保存确认记录。
         *
         * @param value 确认记录
         * @return 保存结果
         */
        @Override
        public AgentConfirmation save(AgentConfirmation value) {
            confirmation = value;
            return value;
        }

        /**
         * 更新确认记录。
         *
         * @param value 新确认记录
         * @param expectedStatus 预期状态
         * @return 更新结果
         */
        @Override
        public AgentConfirmation update(
                AgentConfirmation value, AgentConfirmationStatus expectedStatus) {
            confirmation = value;
            return value;
        }

        /**
         * 查询当前确认记录。
         *
         * @param taskId 任务标识
         * @param confirmationId 确认标识
         * @return 匹配记录
         */
        @Override
        public Optional<AgentConfirmation> find(String taskId, String confirmationId) {
            if (confirmation == null || !taskId.equals(confirmation.taskId())
                    || !confirmationId.equals(confirmation.confirmationId())) {
                return Optional.empty();
            }
            return Optional.of(confirmation);
        }
    }

    /**
     * 记录未命中父授权时的新确认请求，避免测试依赖完整任务控制面。
     *
     * <p>by AI.Coding</p>
     */
    private static final class RecordingConfirmationCoordinator extends AgentConfirmationCoordinator {

        private final AtomicInteger requests = new AtomicInteger();

        /** 创建轻量确认协调器。 */
        private RecordingConfirmationCoordinator() {
            super(null, null, null, null, null);
        }

        /** 未继承父授权时返回一条待确认记录。 */
        @Override
        public AgentConfirmation request(AgentConfirmationRequest request) {
            requests.incrementAndGet();
            Instant now = Instant.now();
            AgentConfirmationBinding binding = new AgentConfirmationBinding(
                    request.stepId(), request.toolCallId(), request.toolId(), request.argumentDigest(),
                    request.riskLevel(), request.impactSummary());
            return new AgentConfirmation(
                    "confirmation-child", request.taskId(), binding, AgentConfirmationStatus.PENDING,
                    now, now.plusSeconds(120), null, null);
        }
    }

}
