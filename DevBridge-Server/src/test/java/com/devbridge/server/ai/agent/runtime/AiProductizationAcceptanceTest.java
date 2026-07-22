package com.devbridge.server.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.config.AiProviderType;
import com.devbridge.server.ai.config.AiRuntimeConfig;
import com.devbridge.server.ai.config.AiModelCapabilityRegistry;
import com.devbridge.server.ai.provider.AiModelRouter;
import com.devbridge.server.ai.storage.AiLocalDataMaintenanceService;
import com.devbridge.server.ai.storage.AiDataMaintenanceLock;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointFileCodec;
import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointService;
import com.devbridge.server.ai.agent.checkpoint.FileAgentCheckpointStore;
import com.devbridge.server.ai.agent.event.AgentEventFileCodec;
import com.devbridge.server.ai.agent.event.FileAgentEventStore;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.store.AgentTaskFileCodec;
import com.devbridge.server.ai.agent.store.FileAgentTaskStore;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M4 产品化验收测试，集中覆盖验证 Agent、模型路由、评测报告和本地备份恢复。
 *
 * <p>by AI.Coding</p>
 */
class AiProductizationAcceptanceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Verification Agent 必须聚合多个只读检查并标记证据状态。 */
    @Test
    void verificationAgentShouldVerifyOnlySuccessfulReadEvidence() {
        AiSpecialistAgentService specialist = fakeSpecialist();
        AiVerificationAgentService service = new AiVerificationAgentService(specialist, objectMapper);

        var output = service.verify(parentRequest("""
                {"checks":[
                  {"checkId":"device","agentId":"device-agent","operation":"HEALTH","arguments":{"serial":"demo"}},
                  {"checkId":"apps","agentId":"app-agent","operation":"LIST","arguments":{"serial":"demo"}}
                ],"claims":[
                  {"claimId":"healthy","claim":"设备与应用状态正常","evidenceRefs":["device","apps"]}
                ]}
                """));

        assertThat(output.path("status").asText()).isEqualTo("VERIFIED");
        assertThat(output.path("checks")).hasSize(2);
    }

    /** Verification Agent 不允许通过写操作完成所谓验证。 */
    @Test
    void verificationAgentShouldRejectWriteOperation() {
        AiVerificationAgentService service = new AiVerificationAgentService(
                fakeSpecialist(), objectMapper);

        assertThatThrownBy(() -> service.verify(parentRequest("""
                {"checks":[
                  {"checkId":"install","agentId":"app-agent","operation":"INSTALL","arguments":{"serial":"demo"}}
                ],"claims":[
                  {"claimId":"installed","claim":"应用已安装","evidenceRefs":["install"]}
                ]}
                """))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只读检查");
    }

    /** 模型故障冷却后优先选择健康兼容备用模型并保留选型原因。 */
    @Test
    void modelRouterShouldPreferHealthyFallbackDuringCooldown() {
        AiModelRouter router = new AiModelRouter();
        AiRuntimeConfig active = new AiRuntimeConfig(
                AiProviderType.OPENAI, "https://openai.example", "key", "gpt-4o");
        AiRuntimeConfig fallback = new AiRuntimeConfig(
                AiProviderType.QWEN, "https://qwen.example", "key", "qwen-plus");
        router.recordRetryableFailure(active);

        var route = router.route(active, List.of(fallback));

        assertThat(route.get(0).config()).isEqualTo(fallback);
        assertThat(route.get(0).reason()).contains("能力兼容备用模型");
        assertThat(route.get(route.size() - 1).config()).isEqualTo(active);
    }

    /** 本地备份按流式文件恢复，并且不会依赖数据库。 */
    @Test
    void localMaintenanceShouldBackupAndRestoreProtectedFiles(@TempDir Path root) throws Exception {
        DevBridgeProperties properties = localProperties(root);
        AiLocalDataMaintenanceService service = new AiLocalDataMaintenanceService(
                properties, null);
        Path sample = root.resolve("sample.enc");
        Files.writeString(sample, "before");

        var backup = service.backup();
        Files.writeString(sample, "after");
        Path createdAfterBackup = root.resolve("created-after-backup.enc");
        Files.writeString(createdAfterBackup, "must-not-survive");
        var restored = service.restore(backup.fileName());

        assertThat(Files.readString(sample)).isEqualTo("before");
        assertThat(createdAfterBackup).doesNotExist();
        assertThat(restored.entries()).isGreaterThan(0);
        assertThat(service.status().backups()).contains(backup.fileName());
    }

    /** 在线恢复必须拒绝活动任务，避免覆盖运行中的 Task 和 Checkpoint。 */
    @Test
    void localMaintenanceShouldRejectRestoreWithActiveTask(@TempDir Path root) throws Exception {
        DevBridgeProperties properties = localProperties(root);
        AgentTaskService tasks = new AgentTaskService(
                new com.devbridge.server.ai.agent.store.InMemoryAgentTaskStore(),
                new AgentTaskStateMachine());
        AiLocalDataMaintenanceService service = new AiLocalDataMaintenanceService(
                properties, null, tasks, new AiDataMaintenanceLock());
        Files.writeString(root.resolve("sample.enc"), "protected");
        var backup = service.backup();
        tasks.createTask(new CreateAgentTaskCommand("conversation-maintenance", "运行任务"));

        assertThatThrownBy(() -> service.restore(backup.fileName()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("运行中 Agent Task");
    }

    /** 生成可供 Provider 升级前比较的轻量 Agent 能力基线报告。 */
    @Test
    void evaluationDatasetShouldProduceBaselineReport(@TempDir Path root) throws Exception {
        AiAgentRegistry registry = new AiAgentRegistry();
        List<RouteCase> dataset = List.of(
                new RouteCase("DEVICE_MANAGEMENT", Platform.ANDROID, "device-agent"),
                new RouteCase("LOG_DIAGNOSIS", Platform.ANDROID, "log-diagnosis-agent"),
                new RouteCase("LOCAL_COMPUTER", Platform.MACOS, "local-agent"));
        AiAgentRegistry.ModelProfile model = new AiAgentRegistry.ModelProfile(true, true, false);
        long matched = dataset.stream().filter(value -> registry.candidates(
                        value.domain(), model, Set.of(value.platform()), List.of()).stream()
                .anyMatch(agent -> agent.identity().agentId().equals(value.expectedAgent()))).count();
        AiModelCapabilityRegistry modelRegistry = new AiModelCapabilityRegistry();
        List<AiRuntimeConfig> providers = List.of(
                new AiRuntimeConfig(AiProviderType.OPENAI, "https://openai.example", "key", "gpt-4o"),
                new AiRuntimeConfig(AiProviderType.DEEPSEEK, "https://deepseek.example", "key", "deepseek-chat"),
                new AiRuntimeConfig(AiProviderType.QWEN, "https://qwen.example", "key", "qwen-plus"),
                new AiRuntimeConfig(AiProviderType.GLM, "https://glm.example", "key", "glm-4-plus"),
                new AiRuntimeConfig(AiProviderType.ERNIE, "https://ernie.example", "key", "ernie-4.0"));
        long providerCompatible = providers.stream().filter(value ->
                modelRegistry.resolve(value.provider(), value.model()).toolCalling()).count();
        double routingAccuracy = (double) matched / dataset.size();
        double duplicateToolCallRate = duplicateToolCallRate(root);
        Path output = Path.of("target", "agent-evaluation");
        Files.createDirectories(output);
        Path reportFile = output.resolve("agent-evaluation-report.json");
        double previousAccuracy = Files.isRegularFile(reportFile)
                ? objectMapper.readTree(reportFile.toFile()).path("agentRoutingAccuracy").asDouble(routingAccuracy)
                : routingAccuracy;
        Map<String, Object> report = Map.ofEntries(
                Map.entry("datasetSize", dataset.size()),
                Map.entry("agentRoutingAccuracy", routingAccuracy),
                Map.entry("agentRoutingAccuracyDelta", routingAccuracy - previousAccuracy),
                Map.entry("providerCompatibilityRate", (double) providerCompatible / providers.size()),
                Map.entry("failedRoutes", dataset.stream().filter(value -> registry.candidates(
                                value.domain(), model, Set.of(value.platform()), List.of()).stream()
                        .noneMatch(agent -> agent.identity().agentId().equals(value.expectedAgent()))).toList()),
                Map.entry("duplicateToolCallRate", duplicateToolCallRate),
                Map.entry("generatedAt", Instant.now().toString()));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);

        assertThat(report.get("agentRoutingAccuracy")).isEqualTo(1.0d);
        assertThat(report.get("providerCompatibilityRate")).isEqualTo(1.0d);
        assertThat(report.get("duplicateToolCallRate")).isEqualTo(0.0d);
        assertThat(Files.readString(reportFile)).contains("duplicateToolCallRate");
    }

    /** 执行两次相同工具预留，使用真实持久 Checkpoint 计算重复执行率。 */
    private double duplicateToolCallRate(Path root) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiAgentDataRoot(root.resolve("evaluation-agent").toString());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileAgentTaskStore taskStore = new FileAgentTaskStore(properties, new AgentTaskFileCodec(mapper));
        AgentTaskService taskService = new AgentTaskService(taskStore, new AgentTaskStateMachine());
        AgentCheckpointService checkpoints = new AgentCheckpointService(
                taskStore, new FileAgentCheckpointStore(properties, new AgentCheckpointFileCodec(mapper)));
        AgentStepIdempotencyService idempotency = new AgentStepIdempotencyService(
                taskService, checkpoints,
                new FileAgentEventStore(properties, new AgentEventFileCodec(mapper)));
        var task = taskService.createTask(new CreateAgentTaskCommand("evaluation", "执行评测工具"));
        task = taskService.transitionTask(task.taskId(), AgentTaskState.PLANNING, "评测规划");
        task = taskService.transitionTask(task.taskId(), AgentTaskState.RUNNING, "评测执行");
        AgentToolExecutionRequest request = new AgentToolExecutionRequest(
                task.taskId(), "step-eval", "tool-eval", "idempotency-eval", "digest-eval", true);
        int executions = idempotency.reserve(request).execute() ? 1 : 0;
        executions += idempotency.reserve(request).execute() ? 1 : 0;
        return Math.max(0, executions - 1) / 2.0d;
    }

    /** 创建带 evidence 的专业 Agent 假结果。 */
    private ObjectNode specialistResult(String evidenceField, boolean success) {
        ObjectNode output = objectMapper.createObjectNode().put("status", success ? "SUCCEEDED" : "FAILED");
        output.set("evidence", objectMapper.createObjectNode().put(evidenceField, "ok"));
        return output;
    }

    /** 创建无需 Mockito/ByteBuddy 的精简专业 Agent 假实现。 */
    private AiSpecialistAgentService fakeSpecialist() {
        return new AiSpecialistAgentService(
                null, objectMapper, new AiAgentRegistry(), new SensitiveDataMasker()) {
            /** 按 Agent ID 返回稳定只读证据。 */
            @Override
            public com.fasterxml.jackson.databind.JsonNode execute(CallRequest parent, String agentId) {
                return specialistResult(agentId, true);
            }
        };
    }

    /** 创建 Verification Agent 父请求。 */
    private CallRequest parentRequest(String json) {
        try {
            return new CallRequest(
                    ToolContract.SCHEMA_VERSION,
                    new CallIdentity("conversation", "task", "turn", "step", "call", Instant.now()),
                    new ToolReference("agent.verification.execute", ToolContract.SCHEMA_VERSION),
                    objectMapper.readTree(json), "digest", "key", Caller.AGENT,
                    new ExecutionContext(Platform.ANDROID, "demo", "", "", List.of()));
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    /** 创建全部数据根目录合并到临时目录的维护配置。 */
    private DevBridgeProperties localProperties(Path root) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiConfigRoot(root.toString());
        properties.setAiAgentDataRoot(root.resolve("agent-data").toString());
        properties.setToolArtifactRoot(root.resolve("artifacts").toString());
        properties.setToolAuditRoot(root.resolve("audit").toString());
        properties.setStorageQuotaBytes(32L * 1024L * 1024L);
        return properties;
    }

    /** Agent 路由评测样本。by AI.Coding */
    private record RouteCase(String domain, Platform platform, String expectedAgent) {
    }
}
