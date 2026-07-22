package com.devbridge.server.ai.tool.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallStatus;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.Diagnostics;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.Exit;
import com.devbridge.server.ai.tool.gateway.ToolContract.Metrics;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.ResultPayload;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskAction;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskDecision;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskLevel;
import com.devbridge.server.ai.tool.gateway.ToolContract.SideEffect;
import com.devbridge.server.ai.tool.gateway.ToolContract.Timing;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.audit.ToolAuditStore.AuditQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 结构化工具审计存储测试，覆盖持久化、查询、脱敏、损坏隔离和保留清理。
 *
 * <p>by AI.Coding</p>
 */
class ToolAuditStoreTest {

    @TempDir
    Path tempDir;

    /**
     * 验证审计记录在 Store 重建后仍可查询，且不会落盘完整参数或凭证。
     *
     * @throws IOException 文件读取失败
     */
    @Test
    void shouldPersistAcrossRestartWithoutSensitivePayload() throws IOException {
        ToolAuditStore store = store();
        CallRequest request = request("task-1", "desktop.shell.run", "digest-only");

        store.recordRequested(request);
        store.recordResult(request, result("api_key=super-secret user@example.com"));

        ToolAuditStore restarted = store();
        assertThat(restarted.query(new AuditQuery("task-1", "", "", null, null, 10)))
                .hasSize(2)
                .allMatch(event -> event.identity().argumentDigest().equals("digest-only"));
        assertThat(restarted.query(new AuditQuery("task-1", "", "", null, null, 1)))
                .singleElement()
                .extracting(event -> event.outcome().phase())
                .isEqualTo("COMPLETED");
        String persisted = Files.readString(auditFile(), StandardCharsets.UTF_8);
        assertThat(persisted)
                .doesNotContain("super-secret", "user@example.com", "dangerous-command")
                .contains("api_key=***", "***@***");
    }

    /**
     * 验证任务、工具、风险和时间筛选共同生效。
     */
    @Test
    void shouldFilterByTaskToolRiskAndTime() {
        ToolAuditStore store = store();
        CallRequest request = request("task-filter", "android.adb.shell", "digest-filter");
        store.recordResult(request, result("完成"));

        Instant now = Instant.now();
        AuditQuery matched = new AuditQuery(
                "task-filter", "android.adb.shell", "MEDIUM", now.minusSeconds(60), now.plusSeconds(60), 10);
        AuditQuery excluded = new AuditQuery(
                "task-other", "android.adb.shell", "MEDIUM", now.minusSeconds(60), now.plusSeconds(60), 10);

        assertThat(store.query(matched)).hasSize(1);
        assertThat(store.query(excluded)).isEmpty();
    }

    /**
     * 验证单行损坏不会阻断同文件其他有效审计记录读取。
     *
     * @throws IOException 文件改写失败
     */
    @Test
    void shouldIsolateCorruptJsonlLine() throws IOException {
        ToolAuditStore store = store();
        CallRequest request = request("task-corrupt", "desktop.shell.run", "digest-corrupt");
        store.recordRequested(request);
        store.recordResult(request, result("完成"));
        List<String> lines = Files.readAllLines(auditFile(), StandardCharsets.UTF_8);

        Files.writeString(
                auditFile(), lines.get(0) + "\n{corrupt-json}\n" + lines.get(1) + "\n", StandardCharsets.UTF_8);

        assertThat(store().query(new AuditQuery("task-corrupt", "", "", null, null, 10))).hasSize(2);
    }

    /**
     * 验证超过保留期的整日文件被清理，当前文件保持不变。
     *
     * @throws IOException 测试文件创建失败
     */
    @Test
    void shouldPurgeExpiredDailyFiles() throws IOException {
        ToolAuditStore store = store();
        store.recordRequested(request("task-retention", "desktop.shell.run", "digest-retention"));
        String oldDate = LocalDate.now(ZoneOffset.UTC)
                .minusDays(91)
                .format(DateTimeFormatter.BASIC_ISO_DATE);
        Path oldFile = tempDir.resolve("audit-" + oldDate + ".jsonl");
        Files.writeString(oldFile, "expired\n", StandardCharsets.UTF_8);

        assertThat(store.purgeExpired()).isEqualTo(1);
        assertThat(oldFile).doesNotExist();
        assertThat(auditFile()).exists();
    }

    /**
     * 创建使用临时目录的审计 Store。
     *
     * @return 测试 Store
     */
    private ToolAuditStore store() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new ToolAuditStore(tempDir, mapper, new SensitiveDataMasker());
    }

    /**
     * 创建测试调用请求，参数正文包含不应进入审计文件的命令。
     *
     * @param taskId 任务 ID
     * @param toolId 工具 ID
     * @param digest 参数摘要
     * @return 调用请求
     */
    private CallRequest request(String taskId, String toolId, String digest) {
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity("conversation-1", taskId, "turn-1", "step-1", "call-1", Instant.now()),
                new ToolReference(toolId, ToolContract.SCHEMA_VERSION),
                new ObjectMapper().createObjectNode().put("command", "dangerous-command --password super-secret"),
                digest,
                "",
                Caller.AGENT,
                new ExecutionContext(Platform.MACOS, "", "", "", List.of()));
    }

    /**
     * 创建带中风险决策的成功结果。
     *
     * @param summary 结果摘要
     * @return 工具结果
     */
    private CallResult result(String summary) {
        Instant now = Instant.now();
        RiskDecision decision = new RiskDecision(
                RiskLevel.MEDIUM, RiskAction.CONFIRM, "policy-1", "CONFIRM", "需确认", "", now);
        Diagnostics diagnostics = new Diagnostics(
                null, new Exit(0, false), new Metrics(1, 2, 0, 0), new SideEffect(false, true, false));
        return new CallResult(
                ToolContract.SCHEMA_VERSION,
                new ToolReference("desktop.shell.run", ToolContract.SCHEMA_VERSION),
                "call-1",
                CallStatus.SUCCEEDED,
                decision,
                new Timing(now.minusMillis(25), now, 25),
                new ResultPayload(new ObjectMapper().createObjectNode(), summary, List.of()),
                diagnostics);
    }

    /**
     * 返回当天审计文件。
     *
     * @return 审计文件路径
     */
    private Path auditFile() {
        String date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        return tempDir.resolve("audit-" + date + ".jsonl");
    }
}
