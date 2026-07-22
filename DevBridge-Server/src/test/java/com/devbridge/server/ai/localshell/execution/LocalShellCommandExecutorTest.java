package com.devbridge.server.ai.localshell.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.localshell.model.LocalShellCommandMode;
import com.devbridge.server.ai.localshell.model.LocalShellCommandPlan;
import com.devbridge.server.ai.localshell.security.LocalShellOutputSanitizer;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import com.devbridge.server.ai.mcp.model.AdbOutputLimit;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Local Shell 命令执行器测试，覆盖工具卡片所需的标题和命令摘要元数据。
 *
 * <p>by AI.Coding</p>
 */
class LocalShellCommandExecutorTest {

    /**
     * 验证成功结果会携带 Local Shell 工具标题和最终执行命令。
     */
    @Test
    void executeShouldAttachToolTitleAndCommandSummary() {
        LocalShellCommandExecutor executor = new LocalShellCommandExecutor(
                new LocalShellOutputSanitizer(new SensitiveDataMasker()),
                new LocalShellRunningToolRegistry(),
                Runnable::run,
                Runnable::run);
        LocalShellCommandPlan plan = new LocalShellCommandPlan(
                "request-1",
                "conversation-1",
                "local_shell_exec",
                LocalShellCommandMode.ARGV,
                "pwd",
                "",
                List.of("pwd"),
                Path.of(System.getProperty("user.dir")),
                Map.of(),
                Duration.ofSeconds(5),
                AdbOutputLimit.defaults(),
                false);

        AdbMcpToolResult result = executor.execute(plan, AdbRiskLevel.LOW);

        assertThat(result.toolTitle()).isEqualTo("Local Shell MCP");
        assertThat(result.commandSummary()).isEqualTo("pwd");
        assertThat(result.status().name()).isEqualTo("SUCCESS");
    }

    /**
     * 验证 Local Shell 在读取阶段使用命令计划中的字节上限。
     */
    @Test
    void executeShouldApplyPlanOutputLimitDuringRead() {
        LocalShellCommandExecutor executor = new LocalShellCommandExecutor(
                new LocalShellOutputSanitizer(new SensitiveDataMasker()),
                new LocalShellRunningToolRegistry(),
                Runnable::run,
                Runnable::run);
        LocalShellCommandPlan plan = new LocalShellCommandPlan(
                "request-bounded",
                "conversation-1",
                "local_shell_exec",
                LocalShellCommandMode.ARGV,
                "/usr/bin/printf",
                "",
                List.of("/usr/bin/printf", "%s", "x".repeat(512)),
                Path.of(System.getProperty("user.dir")),
                Map.of(),
                Duration.ofSeconds(5),
                new AdbOutputLimit(10, 10, 32, 32),
                false);

        AdbMcpToolResult result = executor.execute(plan, AdbRiskLevel.LOW);

        assertThat(result.stdout()).hasSize(32);
        assertThat(result.stdout()).isEqualTo("x".repeat(32));
        assertThat(result.truncated()).isTrue();
    }

    /**
     * 验证命令 IO 队列饱和时返回明确错误并停止已启动进程。
     */
    @Test
    void executeShouldReportExecutorSaturation() {
        LocalShellCommandExecutor executor = new LocalShellCommandExecutor(
                new LocalShellOutputSanitizer(new SensitiveDataMasker()),
                new LocalShellRunningToolRegistry(),
                Runnable::run,
                task -> { throw new RejectedExecutionException("full"); });
        LocalShellCommandPlan plan = new LocalShellCommandPlan(
                "request-rejected", "conversation-1", "local_shell_exec", LocalShellCommandMode.ARGV,
                "sleep", "", List.of("/bin/sh", "-c", "sleep 30"),
                Path.of(System.getProperty("user.dir")), Map.of(), Duration.ofSeconds(5),
                AdbOutputLimit.defaults(), false);

        AdbMcpToolResult result = executor.execute(plan, AdbRiskLevel.LOW);

        assertThat(result.errorCode()).isEqualTo("TOOL_EXECUTOR_SATURATED");
        assertThat(result.exitCode()).isEqualTo(125);
        assertThat(result.message()).isEqualTo("本机命令执行队列已满");
    }

    /** 同步 Agent 工具路径登记真实进程后，取消请求必须终止进程并快速收敛。 */
    @Test
    void executeShouldCancelRegisteredProcess() throws Exception {
        LocalShellRunningToolRegistry registry = new LocalShellRunningToolRegistry();
        ExecutorService io = Executors.newFixedThreadPool(2);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            LocalShellCommandExecutor executor = new LocalShellCommandExecutor(
                    new LocalShellOutputSanitizer(new SensitiveDataMasker()), registry,
                    Runnable::run, io);
            LocalShellCommandPlan plan = new LocalShellCommandPlan(
                    "request-cancel", "conversation-1", "local_shell_exec", LocalShellCommandMode.ARGV,
                    "sleep", "", List.of("/bin/sh", "-c", "sleep 30"),
                    Path.of(System.getProperty("user.dir")), Map.of(), Duration.ofSeconds(30),
                    AdbOutputLimit.defaults(), false);

            CompletableFuture<AdbMcpToolResult> result = CompletableFuture.supplyAsync(
                    () -> executor.execute(plan, AdbRiskLevel.LOW), worker);
            for (int attempt = 0; attempt < 100 && registry.snapshot().isEmpty(); attempt++) {
                Thread.sleep(10L);
            }
            registry.cancel("request-cancel");

            assertThat(result.get(2, TimeUnit.SECONDS).status().name()).isEqualTo("FAILED");
        } finally {
            worker.shutdownNow();
            io.shutdownNow();
        }
    }
}
