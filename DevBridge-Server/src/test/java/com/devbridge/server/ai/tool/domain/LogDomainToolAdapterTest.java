package com.devbridge.server.ai.tool.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.tool.artifact.ToolArtifactStore;
import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskAction;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskDecision;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskLevel;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.LogSessionInfo;
import com.devbridge.server.service.LogStreamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Log 领域工具测试，覆盖统一会话定义、有界过滤读取和 Artifact 导出。
 *
 * <p>by AI.Coding</p>
 */
class LogDomainToolAdapterTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * 验证日志领域工具完整覆盖启动、读取、停止、状态和导出。
     */
    @Test
    void definitionsShouldExposeCompleteLogLifecycle() {
        assertThat(adapter().definitions()).extracting(value -> value.identity().toolId())
                .containsExactly(
                        "log.capture.start", "log.capture.read", "log.capture.stop",
                        "log.capture.status", "log.capture.export");
    }

    /**
     * 验证读取工具只返回匹配级别和文本的最新日志。
     */
    @Test
    void executeShouldReadAndFilterBoundedLogTail() throws Exception {
        Path log = tempDir.resolve("capture.log");
        Files.writeString(log,
                "07-14 12:00:00.000 1 1 I Network: connected\n"
                        + "07-14 12:00:01.000 1 1 E Location: drift detected\n"
                        + "07-14 12:00:02.000 1 1 E Network: timeout\n");
        FakeLogStreamService service = new FakeLogStreamService(log, tempDir.resolve("logs.zip"));
        LogDomainToolAdapter adapter = adapter(service);
        ObjectNode arguments = objectMapper.createObjectNode()
                .put("sessionId", "session-1")
                .put("level", "E")
                .put("filter", "location")
                .put("maxLines", 10);
        CallRequest request = request("log.capture.read", arguments);

        CallResult result = adapter.execute(request, definition(adapter, request), allow());

        assertThat(result.payload().output().path("returnedLines").asInt()).isEqualTo(1);
        assertThat(result.payload().output().path("lines").get(0).asText()).contains("drift detected");
    }

    /**
     * 验证导出会停止会话并只返回受控 Artifact 引用。
     */
    @Test
    void executeShouldExportCapturedLogsAsArtifact() throws Exception {
        Path zip = tempDir.resolve("logs.zip");
        Files.write(zip, new byte[] {1, 2, 3, 4, 5});
        LogDomainToolAdapter adapter = adapter(new FakeLogStreamService(tempDir.resolve("capture.log"), zip));
        CallRequest request = request(
                "log.capture.export", objectMapper.createObjectNode().put("sessionId", "session-1"));

        CallResult result = adapter.execute(request, definition(adapter, request), allow());

        assertThat(result.payload().artifacts()).hasSize(1);
        assertThat(result.payload().artifacts().get(0).mediaType()).isEqualTo("application/zip");
        assertThat(result.payload().output().has("path")).isFalse();
        assertThat(Files.exists(zip)).isFalse();
    }

    /**
     * 创建默认 Adapter。
     *
     * @return Adapter
     */
    private LogDomainToolAdapter adapter() {
        return adapter(new FakeLogStreamService(tempDir.resolve("capture.log"), tempDir.resolve("logs.zip")));
    }

    /**
     * 创建指定日志服务的 Adapter。
     *
     * @param service 日志服务替身
     * @return Adapter
     */
    private LogDomainToolAdapter adapter(FakeLogStreamService service) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setToolArtifactRoot(tempDir.resolve("artifacts").toString());
        return new LogDomainToolAdapter(
                service, new ToolArtifactStore(properties, objectMapper), objectMapper);
    }

    /**
     * 查找请求对应工具定义。
     *
     * @param adapter Adapter
     * @param request 请求
     * @return 工具定义
     */
    private ToolContract.Definition definition(LogDomainToolAdapter adapter, CallRequest request) {
        return adapter.definitions().stream()
                .filter(value -> value.identity().toolId().equals(request.tool().toolId()))
                .findFirst().orElseThrow();
    }

    /**
     * 创建日志工具请求。
     *
     * @param toolId 工具 ID
     * @param arguments 参数
     * @return 请求
     */
    private CallRequest request(String toolId, ObjectNode arguments) {
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity("conversation", "", "turn", "step", "call", Instant.now()),
                new ToolReference(toolId, ToolContract.SCHEMA_VERSION),
                arguments,
                "digest",
                "key",
                Caller.AGENT,
                new ExecutionContext(Platform.ANDROID, "device-1", "", "", List.of()));
    }

    /**
     * 创建允许执行的风险决策。
     *
     * @return 风险决策
     */
    private RiskDecision allow() {
        return new RiskDecision(RiskLevel.LOW, RiskAction.ALLOW, "test", "TEST", "测试", "", Instant.now());
    }

    /**
     * 不启动真实日志进程的日志服务替身。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeLogStreamService extends LogStreamService {

        private final Path logFile;
        private final Path exportFile;

        /**
         * 保存测试文件。
         *
         * @param logFile 日志文件
         * @param exportFile 导出文件
         */
        FakeLogStreamService(Path logFile, Path exportFile) {
            super(null, null, null, null, null);
            this.logFile = logFile;
            this.exportFile = exportFile;
        }

        /**
         * 返回固定会话。
         *
         * @param platformValue 平台
         * @param serial 序列号
         * @return 会话
         */
        @Override
        public LogSessionInfo startCapture(String platformValue, String serial) {
            return info("running");
        }

        /**
         * 返回固定状态。
         *
         * @param sessionId 会话 ID
         * @return 状态
         */
        @Override
        public LogSessionInfo status(String sessionId) {
            return info("running");
        }

        /**
         * 返回停止状态。
         *
         * @param sessionId 会话 ID
         * @return 状态
         */
        @Override
        public LogSessionInfo stop(String sessionId) {
            return info("stopped");
        }

        /**
         * 返回测试日志文件。
         *
         * @param sessionId 会话 ID
         * @return 文件列表
         */
        @Override
        public List<Path> captureFiles(String sessionId) {
            return List.of(logFile);
        }

        /**
         * 返回测试导出文件。
         *
         * @param sessionId 会话 ID
         * @return 导出文件
         */
        @Override
        public Path exportSession(String sessionId) {
            return exportFile;
        }

        /**
         * 创建固定会话信息。
         *
         * @param status 状态
         * @return 会话信息
         */
        private LogSessionInfo info(String status) {
            return new LogSessionInfo(
                    "session-1", com.devbridge.server.model.Platform.ANDROID,
                    "device-1", status, Instant.EPOCH);
        }
    }
}
