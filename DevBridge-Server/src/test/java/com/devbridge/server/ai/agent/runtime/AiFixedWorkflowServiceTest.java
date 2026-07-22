package com.devbridge.server.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.config.AiConfigCrypto;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory;
import com.devbridge.server.ai.localshell.policy.LocalShellPolicyService;
import com.devbridge.server.ai.rag.AiRagBoundary;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.ai.storage.StorageManager;
import com.devbridge.server.ai.tool.artifact.ToolArtifactStore;
import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallStatus;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.AccessMode;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityMetadata;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityQuery;
import com.devbridge.server.ai.tool.gateway.ToolContract.Deprecation;
import com.devbridge.server.ai.tool.gateway.ToolContract.Diagnostics;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionProfile;
import com.devbridge.server.ai.tool.gateway.ToolContract.Exit;
import com.devbridge.server.ai.tool.gateway.ToolContract.Idempotency;
import com.devbridge.server.ai.tool.gateway.ToolContract.IdempotencyMode;
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
import com.devbridge.server.ai.tool.gateway.ToolGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.devbridge.server.config.DevBridgeProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * 固定工作流核心业务闭环测试。
 *
 * <p>by AI.Coding</p>
 */
class AiFixedWorkflowServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    /** 验证健康检查按固定步骤生成报告并保存设备快照。 */
    @Test
    void shouldRunHealthWorkflowAndRecordSnapshot(@TempDir Path root) {
        Fixture fixture = fixture(root);
        ObjectNode arguments = mapper.createObjectNode().put("serial", "device-1").put("platform", "android");

        var report = fixture.service().healthCheck(parent("workflow.device.health", arguments));

        assertThat(report.path("status").asText()).isEqualTo("HEALTHY");
        assertThat(report.path("score").asInt()).isEqualTo(100);
        assertThat(fixture.gateway().calls()).containsExactly("device.detail.read", "device.health.read");
        assertThat(fixture.memory().snapshots("device-1", 10)).hasSize(1);
    }

    /** 验证日志诊断识别崩溃并在结束后停止采集进程。 */
    @Test
    void shouldStopLogProcessAfterDiagnosis(@TempDir Path root) {
        Fixture fixture = fixture(root);
        ObjectNode arguments = mapper.createObjectNode()
                .put("serial", "device-1").put("platform", "android").put("durationSeconds", 1);

        var report = fixture.service().diagnoseLogs(parent("workflow.log.diagnosis", arguments));

        assertThat(report.path("status").asText()).isEqualTo("ANOMALY_FOUND");
        assertThat(report.path("signatures").toString()).contains("CRASH");
        assertThat(fixture.gateway().calls())
                .containsExactly("log.capture.start", "log.capture.read", "log.capture.stop");
        assertThat(fixture.memory().searchIncidents(
                new AiDeviceIncidentMemory.MemoryQuery("device-1", "", "CRASH", 10))).hasSize(1);
    }

    /** 验证安装失败时仍返回已生成的 APK Artifact 和已完成步骤。 */
    @Test
    void shouldKeepArtifactInPartialFailureReport(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Files.writeString(workspace.resolve("app.apk"), "test-apk");
        Fixture fixture = fixture(root);
        WorkflowAuthorization rootAuthorization = new WorkflowAuthorization(
                "agent.supervisor.execute", "supervisor-step", "supervisor-call", "supervisor-digest");
        ObjectNode arguments = mapper.createObjectNode()
                .put("serial", "device-1")
                .put("platform", "android")
                .put("packageName", "com.example.failed")
                .put("workspace", workspace.toString())
                .put("apkPath", "app.apk");
        arguments.set("buildArgv", mapper.createArrayNode().add("./gradlew").add("assembleDebug"));

        var report = fixture.service().buildInstallDiagnose(
                parent("workflow.build.install.diagnosis", arguments, rootAuthorization));

        assertThat(report.path("status").asText()).isEqualTo("FAILED");
        assertThat(report.path("artifactId").asText()).isNotBlank();
        assertThat(report.path("completedSteps").toString()).contains("本机构建", "APK Artifact");
        assertThat(fixture.gateway().calls())
                .containsExactly("desktop.shell.local_shell_exec", "app.install");
        assertThat(fixture.gateway().requests()).allSatisfy(request ->
                assertThat(request.executionContext().workflowAuthorization()).isEqualTo(rootAuthorization));
    }

    /** 验证 Supervisor 按结构化顺序委派注册 Worker。 */
    @Test
    void shouldDelegateRegisteredWorkersInOrder(@TempDir Path root) {
        Fixture fixture = fixture(root);
        ObjectNode arguments = supervisorPlan(
                worker("device", "device-agent", false),
                worker("logs", "log-agent", false));

        var report = fixture.supervisor().execute(parent("agent.supervisor.execute", arguments));

        assertThat(report.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(report.path("workerResults")).hasSize(2);
        assertThat(fixture.gateway().calls())
                .containsExactly("agent.device.execute", "agent.log.execute");
        assertThat(fixture.gateway().requests()).allSatisfy(request -> {
            assertThat(request.requestedBy()).isEqualTo(Caller.WORKFLOW);
            assertThat(request.executionContext().workflowAuthorization().parentToolId())
                    .isEqualTo("agent.supervisor.execute");
        });
    }

    /** 验证 Worker 失败后停止后续步骤并返回部分成功结果。 */
    @Test
    void shouldStopDelegationAfterWorkerFailure(@TempDir Path root) {
        Fixture fixture = fixture(root, Set.of("workflow.log.diagnosis"));
        ObjectNode arguments = supervisorPlan(
                worker("health", "device-health-agent", false),
                worker("logs", "log-diagnosis-agent", false),
                worker("build", "build-install-agent", false));

        var report = fixture.supervisor().execute(parent("agent.supervisor.execute", arguments));

        assertThat(report.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(report.path("executedSteps").asInt()).isEqualTo(2);
        assertThat(report.path("failure").asText()).contains("模拟 Worker 失败");
        assertThat(fixture.gateway().calls())
                .containsExactly("workflow.device.health", "workflow.log.diagnosis");
        assertThat(fixture.supervisor().maximumRisk(supervisorPlan(
                worker("build", "build-install-agent", false)))).isEqualTo(RiskLevel.HIGH);
    }

    /** 验证 Device Agent 只能委派设备领域工具并继承父授权。 */
    @Test
    void shouldExecuteDeviceSpecialistWithinDomain(@TempDir Path root) {
        Fixture fixture = fixture(root);
        ObjectNode input = specialistInput("DETAIL", mapper.createObjectNode()
                .put("serial", "device-1").put("platform", "android"));

        var report = fixture.specialist().execute(parent("agent.device.execute", input), AiAgentRegistry.DEVICE_AGENT);

        assertThat(report.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(report.path("toolId").asText()).isEqualTo("device.detail.read");
        CallRequest delegated = fixture.gateway().requests().get(0);
        assertThat(delegated.requestedBy()).isEqualTo(Caller.WORKFLOW);
        assertThat(delegated.executionContext().workflowAuthorization().parentToolId())
                .isEqualTo("agent.device.execute");
    }

    /** 验证专业 Agent 拒绝跨领域 Operation，并对本机命令使用高风险入口。 */
    @Test
    void shouldRejectCrossDomainOperationAndEscalateLocalExec(@TempDir Path root) {
        Fixture fixture = fixture(root);
        ObjectNode localInput = specialistInput("EXEC", mapper.createObjectNode()
                .put("mode", "ARGV").set("argv", mapper.createArrayNode().add("pwd")));

        assertThatThrownBy(() -> fixture.specialist().execute(
                parent("agent.app.execute", localInput), AiAgentRegistry.APP_AGENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Operation");
        assertThat(fixture.specialist().risk(AiAgentRegistry.LOCAL_AGENT, localInput))
                .isEqualTo(RiskLevel.HIGH);
    }

    /** 创建不访问真实设备和本机命令的工作流依赖。 */
    private Fixture fixture(Path root) {
        return fixture(root, Set.of());
    }

    /** 创建可指定失败 Worker 的隔离依赖。 */
    private Fixture fixture(Path root, Set<String> failedTools) {
        FakeToolGateway gateway = new FakeToolGateway(mapper, failedTools);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("toolGateway", gateway);
        DevBridgeProperties properties = properties(root);
        StorageManager storage = new StorageManager(properties, mapper);
        AiDeviceIncidentMemory memory = new AiDeviceIncidentMemory(
                properties, mapper, new AiConfigCrypto(), storage);
        AiRagBoundary rag = new AiRagBoundary(
                properties, mapper, new AiConfigCrypto(), storage, memory);
        AiFixedWorkflowService service = new AiFixedWorkflowService(
                factory.getBeanProvider(ToolGateway.class), mapper, memory, rag,
                new ToolArtifactStore(properties, mapper, storage), new LocalShellPolicyService(properties));
        AiSupervisorAgentService supervisor = new AiSupervisorAgentService(
                factory.getBeanProvider(ToolGateway.class), mapper, new AiAgentRegistry(), null, null);
        AiSpecialistAgentService specialist = new AiSpecialistAgentService(
                factory.getBeanProvider(ToolGateway.class), mapper,
                new AiAgentRegistry(), new SensitiveDataMasker());
        return new Fixture(service, supervisor, specialist, gateway, memory);
    }

    /** 创建隔离存储配置。 */
    private DevBridgeProperties properties(Path root) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiConfigRoot(root.resolve("ai").toString());
        properties.setAiAgentDataRoot(root.resolve("agent").toString());
        properties.setToolArtifactRoot(root.resolve("artifacts").toString());
        properties.setToolAuditRoot(root.resolve("audit").toString());
        properties.setLogCaptureRoot(root.resolve("logs").toString());
        properties.setDownloadTempRoot(root.resolve("downloads").toString());
        properties.setStorageQuotaBytes(1024L * 1024L * 1024L);
        properties.getAiMcpLocalShell().setAllowedWorkingDirectories(List.of(root.toString()));
        return properties;
    }

    /** 构造绑定任务和设备的父工作流请求。 */
    private CallRequest parent(String toolId, ObjectNode arguments) {
        return parent(toolId, arguments, null);
    }

    /** 构造可携带最外层工作流授权的父请求。 */
    private CallRequest parent(
            String toolId,
            ObjectNode arguments,
            WorkflowAuthorization authorization) {
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity("conversation-1", "task-1", "turn-1", "step-1", "tool-call-1", Instant.now()),
                new ToolReference(toolId, ToolContract.SCHEMA_VERSION),
                arguments, "digest", "workflow-key", Caller.AGENT,
                new ExecutionContext(
                        Platform.PLATFORM_INDEPENDENT, "device-1", "", "", List.of(), authorization));
    }

    /** 创建 Supervisor 计划。 */
    private ObjectNode supervisorPlan(ObjectNode... steps) {
        var values = mapper.createArrayNode();
        for (ObjectNode step : steps) {
            values.add(step);
        }
        return mapper.createObjectNode().set("steps", values);
    }

    /** 创建单个 Android Worker 步骤。 */
    private ObjectNode worker(String stepId, String agentId, boolean continueOnFailure) {
        ObjectNode arguments = mapper.createObjectNode().put("serial", "device-1").put("platform", "android");
        return mapper.createObjectNode()
                .put("stepId", stepId)
                .put("agentId", agentId)
                .put("continueOnFailure", continueOnFailure)
                .set("arguments", arguments);
    }

    /** 创建专业 Agent 输入。 */
    private ObjectNode specialistInput(String operation, ObjectNode arguments) {
        return mapper.createObjectNode().put("operation", operation).set("arguments", arguments);
    }

    /** 测试依赖。by AI.Coding */
    private record Fixture(
            AiFixedWorkflowService service,
            AiSupervisorAgentService supervisor,
            AiSpecialistAgentService specialist,
            FakeToolGateway gateway,
            AiDeviceIncidentMemory memory) {
    }

    /** 返回固定领域工具结果的 Gateway Fake。by AI.Coding */
    private static final class FakeToolGateway extends ToolGateway {

        private final ObjectMapper mapper;
        private final List<String> calls = new ArrayList<>();
        private final List<CallRequest> requests = new ArrayList<>();
        private final Set<String> failedTools;

        /** 创建不使用真实 Gateway 依赖的 Fake。 */
        private FakeToolGateway(ObjectMapper mapper, Set<String> failedTools) {
            super(null, null, null, null, null, null);
            this.mapper = mapper;
            this.failedTools = Set.copyOf(failedTools);
        }

        /** 返回三个固定 Worker 的能力元数据。 */
        @Override
        public List<CapabilityMetadata> listCapabilities(CapabilityQuery query) {
            return List.of(
                    capability("workflow.device.health", "workflow.device.health", AccessMode.READ, RiskLevel.LOW),
                    capability("workflow.log.diagnosis", "workflow.log.diagnosis", AccessMode.CONTROL, RiskLevel.LOW),
                    capability("workflow.build.install.diagnosis", "workflow.build.install",
                            AccessMode.CONTROL, RiskLevel.HIGH),
                    capability("agent.device.execute", "agent.device", AccessMode.READ, RiskLevel.LOW),
                    capability("agent.log.execute", "agent.log", AccessMode.CONTROL, RiskLevel.LOW),
                    capability("agent.app.execute", "agent.app", AccessMode.CONTROL, RiskLevel.LOW),
                    capability("agent.local.execute", "agent.local", AccessMode.CONTROL, RiskLevel.LOW),
                    capability("device.detail.read", "device.detail.read", AccessMode.READ, RiskLevel.LOW),
                    capability("app.uninstall", "device.app.uninstall", AccessMode.CONTROL, RiskLevel.MEDIUM),
                    capability("desktop.shell.exec", "desktop.shell.execute", AccessMode.CONTROL, RiskLevel.MEDIUM));
        }

        /** 按工具 ID 返回固定结构化证据。 */
        @Override
        public CallResult call(CallRequest request) {
            calls.add(request.tool().toolId());
            requests.add(request);
            if (failedTools.contains(request.tool().toolId())) {
                return failure(request, "模拟 Worker 失败");
            }
            ObjectNode output = mapper.createObjectNode();
            switch (request.tool().toolId()) {
                case "device.detail.read" -> output.put("model", "Pixel").put("osVersion", "14");
                case "device.health.read" -> output.put("assessment", "HEALTHY");
                case "log.capture.start" -> output.put("sessionId", "session-1");
                case "log.capture.read" -> {
                    output.put("returnedLines", 1).put("truncated", false);
                    output.set("lines", mapper.createArrayNode().add("E AndroidRuntime: FATAL EXCEPTION: main"));
                }
                case "app.install" -> {
                    return failure(request, "模拟安装失败");
                }
                default -> output.put("ok", true);
            }
            return success(request, output);
        }

        /** 返回调用顺序。 */
        private List<String> calls() {
            return List.copyOf(calls);
        }

        /** 返回完整调用请求。 */
        private List<CallRequest> requests() {
            return List.copyOf(requests);
        }

        /** 创建 Worker 工具能力元数据。 */
        private CapabilityMetadata capability(
                String toolId,
                String capability,
                AccessMode accessMode,
                RiskLevel riskLevel) {
            Metadata metadata = new Metadata(
                    new Source(SourceKind.BUILT_IN, "test", "1.0.0", "", "IN_PROCESS"),
                    List.of(capability), List.of(Platform.PLATFORM_INDEPENDENT), accessMode,
                    new Idempotency(IdempotencyMode.KEYED, true, ""),
                    new RiskProfile(riskLevel, true),
                    new ExecutionProfile(1_000, 10_000, 64 * 1024, true, false, List.of()));
            return new CapabilityMetadata(
                    toolId, toolId, ToolContract.SCHEMA_VERSION, metadata, new Deprecation(false, ""));
        }

        /** 构造统一成功结果。 */
        private CallResult success(CallRequest request, ObjectNode output) {
            Instant now = Instant.now();
            RiskDecision risk = new RiskDecision(
                    RiskLevel.LOW, RiskAction.ALLOW, "test", "TEST", "test", "", now);
            return new CallResult(
                    ToolContract.SCHEMA_VERSION, request.tool(), request.identity().toolCallId(),
                    CallStatus.SUCCEEDED, risk, new Timing(now, now, 0),
                    new ResultPayload(output, "ok", List.of()),
                    new Diagnostics(null, new Exit(0, false),
                            new Metrics(0, output.toString().length(), 0, 0),
                            new SideEffect(false, true, false)));
        }

        /** 构造统一失败结果。 */
        private CallResult failure(CallRequest request, String detail) {
            Instant now = Instant.now();
            RiskDecision risk = new RiskDecision(
                    RiskLevel.HIGH, RiskAction.ALLOW, "test", "TEST", "test", "", now);
            return new CallResult(
                    ToolContract.SCHEMA_VERSION, request.tool(), request.identity().toolCallId(),
                    CallStatus.FAILED, risk, new Timing(now, now, 0),
                    new ResultPayload(mapper.createObjectNode(), "failed", List.of()),
                    new Diagnostics(
                            new ToolContract.Error(
                                    "TEST_FAILED", ToolContract.ErrorCategory.EXECUTION,
                                    "模拟失败", detail, false, false),
                            new Exit(1, false), new Metrics(0, 0, 0, 0),
                            new SideEffect(false, false, false)));
        }
    }
}
