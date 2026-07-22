package com.devbridge.server.ai.agent.runtime;

import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory.DeviceSnapshotRequest;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory.IncidentDetails;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory.IncidentRequest;
import com.devbridge.server.ai.localshell.policy.LocalShellPolicyService;
import com.devbridge.server.ai.rag.AiRagBoundary;
import com.devbridge.server.ai.rag.AiRagBoundary.SearchRequest;
import com.devbridge.server.ai.tool.artifact.ToolArtifactStore;
import com.devbridge.server.ai.tool.artifact.ToolArtifactStore.ArtifactWriteRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallStatus;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.devbridge.server.ai.tool.gateway.ToolContract.WorkflowAuthorization;
import com.devbridge.server.ai.tool.gateway.ToolGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 三个产品固定工作流，按稳定步骤调用统一 Tool Gateway 并生成证据化报告。
 *
 * <p>工作流不是通用引擎；只实现设备健康、实时日志诊断和跨端构建安装诊断三个现有产品场景。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AiFixedWorkflowService {

    private static final String DEVICE_DETAIL = "device.detail.read";
    private static final String DEVICE_HEALTH = "device.health.read";
    private static final String LOG_START = "log.capture.start";
    private static final String LOG_READ = "log.capture.read";
    private static final String LOG_STOP = "log.capture.stop";
    private static final String LOCAL_EXEC = "desktop.shell.local_shell_exec";
    private static final String APP_INSTALL = "app.install";
    private static final String APP_LAUNCH = "app.launch";
    private static final int MAX_LOG_SECONDS = 60;
    private static final int MAX_EVIDENCE = 20;

    private final ObjectProvider<ToolGateway> toolGateway;
    private final ObjectMapper objectMapper;
    private final AiDeviceIncidentMemory memory;
    private final AiRagBoundary rag;
    private final ToolArtifactStore artifactStore;
    private final LocalShellPolicyService localShellPolicy;
    private final Map<String, WorkflowRunState> active = new ConcurrentHashMap<>();

    /**
     * 注入工作流复用的产品服务。
     *
     * @param toolGateway 延迟获取统一 Tool Gateway，避免工具注册阶段循环创建
     * @param objectMapper JSON 工具
     * @param memory 设备与故障 Memory
     * @param rag 本地知识库
     * @param artifactStore Artifact Store
     * @param localShellPolicy 本机路径安全策略
     */
    public AiFixedWorkflowService(
            ObjectProvider<ToolGateway> toolGateway,
            ObjectMapper objectMapper,
            AiDeviceIncidentMemory memory,
            AiRagBoundary rag,
            ToolArtifactStore artifactStore,
            LocalShellPolicyService localShellPolicy) {
        this.toolGateway = toolGateway;
        this.objectMapper = objectMapper;
        this.memory = memory;
        this.rag = rag;
        this.artifactStore = artifactStore;
        this.localShellPolicy = localShellPolicy;
    }

    /**
     * 执行设备健康检查并保存趋势快照。
     *
     * @param parent 父工作流工具请求
     * @return 健康报告
     */
    public JsonNode healthCheck(CallRequest parent) {
        ObjectNode arguments = object(parent.arguments());
        String serial = required(arguments, "serial");
        Platform platform = platform(arguments.path("platform").asText("android"));
        CallResult detail = call(parent, "detail", DEVICE_DETAIL, serialArgs(serial), platform, serial, "");
        CallResult health = call(parent, "health", DEVICE_HEALTH, serialArgs(serial), platform, serial, "");
        JsonNode detailOutput = requireSuccess(detail, "读取设备详情");
        JsonNode healthOutput = requireSuccess(health, "读取设备健康指标");
        ObjectNode report = healthReport(detailOutput, healthOutput);
        memory.recordSnapshot(new DeviceSnapshotRequest(
                serial, platform.name(), detailOutput.path("model").asText(""),
                detailOutput.path("osVersion").asText(""), report.deepCopy(), Instant.now()));
        recordHealthIncident(serial, platform, detailOutput, healthOutput, report);
        return report;
    }

    /**
     * 采集固定时间窗口日志，完成分析后无条件停止底层进程。
     *
     * @param parent 父工作流工具请求
     * @return 日志诊断报告
     */
    public JsonNode diagnoseLogs(CallRequest parent) {
        WorkflowRunState state = new WorkflowRunState(parent);
        active.put(parent.identity().toolCallId(), state);
        try {
            return diagnoseLogs(parent, state);
        } finally {
            active.remove(parent.identity().toolCallId());
        }
    }

    /**
     * 使用指定运行状态执行日志窗口，供跨端工作流共享取消信号。
     */
    private JsonNode diagnoseLogs(CallRequest parent, WorkflowRunState state) {
        ObjectNode arguments = object(parent.arguments());
        String serial = required(arguments, "serial");
        Platform platform = platform(arguments.path("platform").asText("android"));
        int seconds = Math.min(MAX_LOG_SECONDS, Math.max(1, arguments.path("durationSeconds").asInt(10)));
        state.execution(platform, serial, parent.executionContext().workspace());
        try {
            CallResult started = call(parent, "log-start", LOG_START, serialArgs(serial), platform, serial, "");
            JsonNode startedOutput = requireSuccess(started, "启动日志采集");
            state.sessionId(startedOutput.path("sessionId").asText(""));
            waitWindow(state, seconds);
            ObjectNode readArgs = objectMapper.createObjectNode()
                    .put("sessionId", state.sessionId()).put("maxLines", 1_500).put("level", "ALL");
            JsonNode logOutput = requireSuccess(
                    call(parent, "log-read", LOG_READ, readArgs, platform, serial, ""), "读取采集日志");
            return logReport(parent, serial, platform, arguments.path("osVersion").asText(""), seconds, logOutput);
        } finally {
            stopLog(state);
        }
    }

    /**
     * 执行本机构建、APK 入库、安装、启动和日志验证。
     *
     * @param parent 已通过高风险入口确认的父工作流请求
     * @return 跨端诊断报告
     */
    public JsonNode buildInstallDiagnose(CallRequest parent) {
        ObjectNode arguments = object(parent.arguments());
        String serial = required(arguments, "serial");
        String packageName = required(arguments, "packageName");
        String workspace = required(arguments, "workspace");
        Platform devicePlatform = platform(arguments.path("platform").asText("android"));
        WorkflowRunState state = new WorkflowRunState(parent);
        active.put(parent.identity().toolCallId(), state);
        List<String> completed = new ArrayList<>();
        String artifactId = "";
        try {
            runBuild(parent, arguments, workspace);
            completed.add("本机构建");
            artifactId = apkArtifact(arguments, workspace);
            completed.add("APK Artifact");
            install(parent, serial, packageName, artifactId, devicePlatform);
            completed.add("安装应用");
            launch(parent, serial, packageName, devicePlatform);
            completed.add("启动应用");
            ObjectNode logArgs = objectMapper.createObjectNode()
                    .put("serial", serial).put("platform", arguments.path("platform").asText("android"))
                    .put("durationSeconds", Math.min(30, Math.max(3, arguments.path("logDurationSeconds").asInt(8))))
                    .put("osVersion", arguments.path("osVersion").asText(""));
            JsonNode logReport = diagnoseLogs(
                    childParent(parent, "verification", logArgs, devicePlatform, serial, workspace), state);
            completed.add("日志验证");
            return buildReport("SUCCEEDED", completed, "", artifactId, logReport);
        } catch (RuntimeException ex) {
            recordBuildIncident(serial, devicePlatform, arguments, completed, ex);
            return buildReport("FAILED", completed, ex.getMessage(), artifactId, objectMapper.createObjectNode());
        } finally {
            active.remove(parent.identity().toolCallId());
        }
    }

    /**
     * 取消活动工作流并停止仍在采集的日志。
     *
     * @param parent 父工作流请求
     */
    public void cancel(CallRequest parent) {
        WorkflowRunState state = active.get(parent.identity().toolCallId());
        if (state != null) {
            state.cancel();
            stopLog(state);
        }
    }

    /**
     * 执行本机 ARGV 构建命令。
     */
    private void runBuild(CallRequest parent, ObjectNode arguments, String workspace) {
        JsonNode argv = arguments.path("buildArgv");
        if (!argv.isArray() || argv.isEmpty()) {
            throw new IllegalArgumentException("buildArgv 不能为空");
        }
        ObjectNode command = objectMapper.createObjectNode()
                .put("mode", "ARGV").put("workingDirectory", workspace);
        command.set("argv", argv.deepCopy());
        JsonNode output = requireSuccess(
                call(parent, "build", LOCAL_EXEC, command, hostPlatform(), "", workspace), "执行本机构建");
        if (output.path("exitCode").asInt(0) != 0) {
            throw new IllegalStateException("本机构建失败: " + output.path("stderr").asText(""));
        }
    }

    /**
     * 复用已有 Artifact 或从允许工作目录导入 APK。
     */
    private String apkArtifact(ObjectNode arguments, String workspace) {
        String existing = arguments.path("artifactId").asText("").trim();
        if (StringUtils.hasText(existing)) {
            artifactStore.find(existing).orElseThrow(() -> new IllegalArgumentException("APK Artifact 不存在"));
            return existing;
        }
        String rawPath = required(arguments, "apkPath");
        Path root = localShellPolicy.workingDirectory(workspace);
        Path apk = localShellPolicy.normalize(root.resolve(rawPath));
        if (!apk.startsWith(root) || !Files.isRegularFile(apk)) {
            throw new IllegalArgumentException("APK 路径不在允许工作目录或文件不存在");
        }
        var metadata = artifactStore.writeFile(new ArtifactWriteRequest(
                "android-apk", "application/vnd.android.package-archive", "SENSITIVE",
                Instant.now().plus(7, ChronoUnit.DAYS), false, "none"), apk);
        return metadata.identity().artifactId();
    }

    /** 安装 APK 并依靠 App 工具完成后置验证。 */
    private void install(
            CallRequest parent,
            String serial,
            String packageName,
            String artifactId,
            Platform platform) {
        ObjectNode args = objectMapper.createObjectNode()
                .put("serial", serial).put("packageName", packageName).put("artifactId", artifactId);
        requireSuccess(call(parent, "install", APP_INSTALL, args, platform, serial, ""), "安装应用");
    }

    /** 启动应用并依靠 App 工具完成进程验证。 */
    private void launch(CallRequest parent, String serial, String packageName, Platform platform) {
        ObjectNode args = objectMapper.createObjectNode().put("serial", serial).put("packageName", packageName);
        requireSuccess(call(parent, "launch", APP_LAUNCH, args, platform, serial, ""), "启动应用");
    }

    /**
     * 构造设备健康报告，缺失指标时不生成虚假分数。
     */
    private ObjectNode healthReport(JsonNode detail, JsonNode health) {
        String assessment = health.path("assessment").asText("UNKNOWN");
        ObjectNode report = objectMapper.createObjectNode()
                .put("workflow", "DEVICE_HEALTH_CHECK")
                .put("status", assessment)
                .put("generatedAt", Instant.now().toString());
        if (!"UNKNOWN".equals(assessment)) {
            report.put("score", "HEALTHY".equals(assessment) ? 100 : 80);
        } else {
            report.putNull("score");
        }
        report.set("device", detail.deepCopy());
        report.set("health", health.deepCopy());
        ArrayNode suggestions = objectMapper.createArrayNode();
        if ("ATTENTION".equals(assessment)) {
            suggestions.add("优先处理健康指标中的异常项，并在处理后重新检查");
        } else if ("UNKNOWN".equals(assessment)) {
            suggestions.add("部分指标不可用，当前不能判定设备健康状态");
        } else {
            suggestions.add("当前已采集指标未发现明显异常");
        }
        report.set("suggestions", suggestions);
        return report;
    }

    /**
     * 健康异常进入 Incident Memory，正常结果只保留趋势快照。
     */
    private void recordHealthIncident(
            String serial,
            Platform platform,
            JsonNode detail,
            JsonNode health,
            JsonNode report) {
        if (!"ATTENTION".equals(health.path("assessment").asText(""))) {
            return;
        }
        memory.recordIncident(new IncidentRequest(
                serial, platform.name(), detail.path("osVersion").asText(""),
                new IncidentDetails(
                        "DEVICE_HEALTH_ATTENTION", "设备健康检查发现需要关注的指标",
                        jsonStrings(health.path("observations")), "处理异常指标后重新执行健康检查",
                        "UNVERIFIED", List.of("health", "device")), null));
    }

    /**
     * 分析有界日志并关联本地知识与 Incident Memory。
     */
    private JsonNode logReport(
            CallRequest parent,
            String serial,
            Platform platform,
            String osVersion,
            int seconds,
            JsonNode logOutput) {
        LogAnalysis analysis = analyzeLines(logOutput.path("lines"));
        var knowledge = rag.search(new SearchRequest(
                analysis.query(), serial, osVersion, 5));
        ObjectNode report = objectMapper.createObjectNode()
                .put("workflow", "REALTIME_LOG_DIAGNOSIS")
                .put("status", analysis.signatures().isEmpty() ? "NO_OBVIOUS_ANOMALY" : "ANOMALY_FOUND")
                .put("durationSeconds", seconds)
                .put("returnedLines", logOutput.path("returnedLines").asInt(0))
                .put("truncated", logOutput.path("truncated").asBoolean(false));
        report.set("signatures", objectMapper.valueToTree(analysis.signatures()));
        report.set("evidence", objectMapper.valueToTree(analysis.evidence()));
        report.set("citations", objectMapper.valueToTree(knowledge.citations()));
        report.set("suggestions", objectMapper.valueToTree(logSuggestions(analysis.signatures())));
        if (!analysis.signatures().isEmpty()) {
            memory.recordIncident(new IncidentRequest(
                    serial, platform.name(), osVersion,
                    new IncidentDetails(
                            String.join(",", analysis.signatures()), "实时日志诊断发现异常",
                            analysis.evidence(), "按报告建议处理并重新采集日志验证",
                            "UNVERIFIED", analysis.signatures()), null));
        }
        return report;
    }

    /**
     * 识别常见 Android/iOS 运行时故障特征并保留少量证据行。
     */
    private LogAnalysis analyzeLines(JsonNode lines) {
        Map<String, List<String>> patterns = new LinkedHashMap<>();
        patterns.put("CRASH", List.of("fatal exception", "uncaught exception", "crash"));
        patterns.put("ANR", List.of("anr in", "not responding", "watchdog"));
        patterns.put("OUT_OF_MEMORY", List.of("outofmemory", "out of memory", "lowmemorykiller"));
        patterns.put("PERMISSION", List.of("securityexception", "permission denial", "not allowed"));
        patterns.put("NETWORK", List.of("unknownhost", "connectexception", "sockettimeout", "connection refused"));
        List<String> signatures = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        if (lines.isArray()) {
            for (JsonNode lineNode : lines) {
                String line = lineNode.asText("");
                String lower = line.toLowerCase(Locale.ROOT);
                for (Map.Entry<String, List<String>> entry : patterns.entrySet()) {
                    if (entry.getValue().stream().anyMatch(lower::contains)) {
                        if (!signatures.contains(entry.getKey())) {
                            signatures.add(entry.getKey());
                        }
                        if (evidence.size() < MAX_EVIDENCE) {
                            evidence.add(limit(line, 600));
                        }
                    }
                }
            }
        }
        String query = signatures.isEmpty() ? "设备日志诊断" : String.join(" ", signatures) + " "
                + (evidence.isEmpty() ? "" : evidence.get(0));
        return new LogAnalysis(List.copyOf(signatures), List.copyOf(evidence), query);
    }

    /** 返回日志异常对应的行动建议。 */
    private List<String> logSuggestions(List<String> signatures) {
        List<String> suggestions = new ArrayList<>();
        if (signatures.contains("CRASH")) {
            suggestions.add("结合崩溃堆栈定位首个业务异常和对应版本变更");
        }
        if (signatures.contains("ANR")) {
            suggestions.add("检查主线程阻塞、锁竞争和 Binder 调用耗时");
        }
        if (signatures.contains("OUT_OF_MEMORY")) {
            suggestions.add("检查大对象、图片、日志缓存和持续增长集合");
        }
        if (signatures.contains("PERMISSION")) {
            suggestions.add("核对应用权限、系统版本限制和运行时授权状态");
        }
        if (signatures.contains("NETWORK")) {
            suggestions.add("检查 DNS、代理、证书、网络权限和服务端可达性");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("当前窗口未发现常见异常，可在问题复现时扩大采集窗口");
        }
        return List.copyOf(suggestions);
    }

    /**
     * 生成跨端工作流结果，明确保留已完成步骤和失败位置。
     */
    private ObjectNode buildReport(
            String status,
            List<String> completed,
            String failure,
            String artifactId,
            JsonNode logReport) {
        ObjectNode report = objectMapper.createObjectNode()
                .put("workflow", "BUILD_INSTALL_DIAGNOSIS")
                .put("status", status)
                .put("failure", failure == null ? "" : failure)
                .put("artifactId", artifactId == null ? "" : artifactId)
                .put("generatedAt", Instant.now().toString());
        report.set("completedSteps", objectMapper.valueToTree(completed));
        report.set("verification", logReport == null ? objectMapper.createObjectNode() : logReport.deepCopy());
        return report;
    }

    /** 记录跨端失败事实，不把成功步骤伪装为已回滚。 */
    private void recordBuildIncident(
            String serial,
            Platform platform,
            ObjectNode arguments,
            List<String> completed,
            RuntimeException error) {
        memory.recordIncident(new IncidentRequest(
                serial, platform.name(), arguments.path("osVersion").asText(""),
                new IncidentDetails(
                        "BUILD_INSTALL_FAILED", "跨端构建安装诊断失败",
                        List.of("已完成步骤=" + completed, "失败原因=" + limit(error.getMessage(), 1_000)),
                        "修复失败步骤后使用同一任务重新执行，已完成子步骤将通过幂等结果复用",
                        "FAILED", List.of("build", "install", "cross-platform")), null));
    }

    /**
     * 调用统一 Tool Gateway，并为子步骤绑定稳定身份和父工作流确认。
     */
    private CallResult call(
            CallRequest parent,
            String step,
            String toolId,
            JsonNode arguments,
            Platform platform,
            String deviceId,
            String workspace) {
        String suffix = shortHash(parent.identity().toolCallId() + ":" + step + ":" + arguments);
        WorkflowAuthorization authorization = parent.executionContext().workflowAuthorization() == null
                ? new WorkflowAuthorization(
                        parent.tool().toolId(), parent.identity().stepId(),
                        parent.identity().toolCallId(), parent.argumentDigest())
                : parent.executionContext().workflowAuthorization();
        CallRequest request = new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity(
                        parent.identity().conversationId(), parent.identity().taskId(), parent.identity().turnId(),
                        "workflow-step-" + step + "-" + suffix,
                        "workflow-call-" + step + "-" + suffix, Instant.now()),
                new ToolReference(toolId, ToolContract.SCHEMA_VERSION), arguments.deepCopy(),
                digest(arguments), "workflow:" + parent.identity().toolCallId() + ":" + step,
                Caller.WORKFLOW,
                new ExecutionContext(
                        platform, deviceId, workspace, parent.executionContext().confirmationId(),
                        List.of("workflow:" + parent.tool().toolId()), authorization));
        ToolGateway gateway = toolGateway.getIfAvailable();
        if (gateway == null) {
            throw new IllegalStateException("统一 Tool Gateway 尚未就绪");
        }
        return gateway.call(request);
    }

    /**
     * 创建日志验证使用的内部父请求，保持原确认和任务绑定。
     */
    private CallRequest childParent(
            CallRequest parent,
            String step,
            JsonNode arguments,
            Platform platform,
            String deviceId,
            String workspace) {
        String suffix = shortHash(parent.identity().toolCallId() + ":" + step);
        return new CallRequest(
                parent.schemaVersion(),
                new CallIdentity(
                        parent.identity().conversationId(), parent.identity().taskId(), parent.identity().turnId(),
                        parent.identity().stepId() + "-" + step,
                        parent.identity().toolCallId() + "-" + suffix, Instant.now()),
                parent.tool(), arguments.deepCopy(), digest(arguments), parent.idempotencyKey(),
                Caller.WORKFLOW,
                new ExecutionContext(platform, deviceId, workspace, parent.executionContext().confirmationId(),
                        List.of(), parent.executionContext().workflowAuthorization()));
    }

    /** 返回成功输出，其他状态立即中断后续依赖步骤。 */
    private JsonNode requireSuccess(CallResult result, String action) {
        if (result.status() != CallStatus.SUCCEEDED) {
            String detail = result.diagnostics().error() == null
                    ? result.payload().summary()
                    : result.diagnostics().error().detail();
            throw new IllegalStateException(action + "失败: " + detail);
        }
        return result.payload().output() == null ? objectMapper.createObjectNode() : result.payload().output();
    }

    /** 等待日志窗口并响应任务取消。 */
    private void waitWindow(WorkflowRunState state, int seconds) {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (state.canceled().get() || Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("工作流已取消");
            }
            try {
                Thread.sleep(Math.min(200L, Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L)));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("工作流已取消", ex);
            }
        }
    }

    /** 无条件停止日志进程，重复停止保持幂等。 */
    private void stopLog(WorkflowRunState state) {
        if (!StringUtils.hasText(state.sessionId()) || !state.markStopping()) {
            return;
        }
        try {
            ObjectNode args = objectMapper.createObjectNode().put("sessionId", state.sessionId());
            call(state.parent(), "log-stop", LOG_STOP, args,
                    state.platform(), state.deviceId(), state.workspace());
        } catch (RuntimeException ignored) {
            // 原始诊断异常优先；LogStreamService 自身还有超时清理兜底。
        }
    }

    /** 构造仅含序列号的参数。 */
    private ObjectNode serialArgs(String serial) {
        return objectMapper.createObjectNode().put("serial", serial);
    }

    /** 要求参数为对象。 */
    private ObjectNode object(JsonNode value) {
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException("工作流参数必须为 JSON 对象");
        }
        return object;
    }

    /** 读取必填文本。 */
    private String required(ObjectNode arguments, String field) {
        String value = arguments.path(field).asText("").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /** 转换平台文本。 */
    private Platform platform(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "ios" -> Platform.IOS;
            case "harmony", "harmonyos", "harmony_os" -> Platform.HARMONY_OS;
            default -> Platform.ANDROID;
        };
    }

    /** 识别当前宿主平台。 */
    private Platform hostPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") ? Platform.MACOS : os.contains("win") ? Platform.WINDOWS : Platform.LINUX;
    }

    /** 计算参数摘要。 */
    private String digest(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    /** 返回短稳定标识。 */
    private String shortHash(String value) {
        return digest(objectMapper.getNodeFactory().textNode(value)).substring(0, 12);
    }

    /** JSON 数组转换为有界字符串列表。 */
    private List<String> jsonStrings(JsonNode values) {
        List<String> result = new ArrayList<>();
        if (values != null && values.isArray()) {
            values.forEach(value -> {
                if (result.size() < MAX_EVIDENCE) {
                    result.add(limit(value.asText(""), 600));
                }
            });
        }
        return List.copyOf(result);
    }

    /** 限制报告文本长度。 */
    private String limit(String value, int maxLength) {
        String text = value == null ? "" : value;
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    /** 日志特征分析结果。by AI.Coding */
    private record LogAnalysis(List<String> signatures, List<String> evidence, String query) {
    }

    /** 活动工作流取消和日志会话状态。by AI.Coding */
    private static final class WorkflowRunState {

        private final CallRequest parent;
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final AtomicBoolean stopping = new AtomicBoolean();
        private volatile String sessionId = "";
        private volatile Platform platform = Platform.ANDROID;
        private volatile String deviceId = "";
        private volatile String workspace = "";

        /** 保存父请求。 */
        private WorkflowRunState(CallRequest parent) {
            this.parent = parent;
        }

        /** 返回父请求。 */
        private CallRequest parent() {
            return parent;
        }

        /** 标记取消。 */
        private void cancel() {
            canceled.set(true);
        }

        /** 返回取消标记。 */
        private AtomicBoolean canceled() {
            return canceled;
        }

        /** 保存日志会话。 */
        private void sessionId(String value) {
            sessionId = value == null ? "" : value;
        }

        /** 返回日志会话。 */
        private String sessionId() {
            return sessionId;
        }

        /** 保存日志子步骤执行上下文。 */
        private void execution(Platform value, String device, String workdir) {
            platform = value == null ? Platform.ANDROID : value;
            deviceId = device == null ? "" : device;
            workspace = workdir == null ? "" : workdir;
        }

        /** 返回日志平台。 */
        private Platform platform() {
            return platform;
        }

        /** 返回日志设备。 */
        private String deviceId() {
            return deviceId;
        }

        /** 返回工作目录。 */
        private String workspace() {
            return workspace;
        }

        /** 只允许一个线程执行停止。 */
        private boolean markStopping() {
            return stopping.compareAndSet(false, true);
        }
    }
}
