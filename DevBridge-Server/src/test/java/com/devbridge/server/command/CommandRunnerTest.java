package com.devbridge.server.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.config.DevBridgeExecutorProperties;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.config.ToolExecutorConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/**
 * 外部命令执行器测试，覆盖超时和输出读取边界。
 *
 * <p>by AI.Coding</p>
 */
class CommandRunnerTest {

    /**
     * 验证命令超时后能快速返回，避免 adb 子进程卡住时拖死 HTTP 请求。
     */
    @Test
    void runShouldReturnWhenCommandTimesOut() {
        ExecutorService executor = commandIoExecutor();
        try {
            CommandRunner runner = new CommandRunner(new DevBridgeProperties(), executor);
            long start = System.nanoTime();

            CommandResult result = runner.run(List.of("sh", "-c", "sleep 2"), Duration.ofMillis(100));

            long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(result.timedOut()).isTrue();
            assertThat(result.exitCode()).isEqualTo(124);
            assertThat(elapsedMillis).isLessThan(1_500);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 验证命令执行器在读取阶段应用调用方指定的行数上限。
     */
    @Test
    void runShouldApplyOutputLimitsBeforeProcessCompletes() {
        ExecutorService executor = commandIoExecutor();
        try {
            CommandRunner runner = new CommandRunner(new DevBridgeProperties(), executor);
            CommandOutputLimits limits = CommandOutputLimits.of(2, 2, 1_024, 1_024);

            CommandResult result = runner.run(
                    List.of("sh", "-c", "printf 'one\\ntwo\\nthree\\n'"),
                    Duration.ofSeconds(5),
                    Map.of(),
                    limits);

            assertThat(result.stdout()).containsExactly("one", "two");
            assertThat(result.stdoutStats().totalLines()).isEqualTo(3);
            assertThat(result.stdoutStats().discardedLines()).isEqualTo(1);
            assertThat(result.outputTruncated()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 验证命令 IO 队列拒绝任务时快速终止进程并返回稳定错误码。
     */
    @Test
    void runShouldReturnExplicitResultWhenExecutorRejects() {
        CommandRunner runner = new CommandRunner(
                new DevBridgeProperties(),
                task -> { throw new RejectedExecutionException("full"); });
        long started = System.nanoTime();

        CommandResult result = runner.run(List.of("sh", "-c", "sleep 30"), Duration.ofSeconds(5));

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(result.exitCode()).isEqualTo(CommandRunner.EXECUTOR_REJECTED_EXIT_CODE);
        assertThat(result.stderr()).containsExactly("command executor saturated");
        assertThat(elapsedMillis).isLessThan(1_500);
    }

    /**
     * 创建测试专用有界命令 IO 线程池。
     *
     * @return 命令 IO 线程池
     */
    private ExecutorService commandIoExecutor() {
        return new ToolExecutorConfiguration().commandIoExecutor(new DevBridgeExecutorProperties());
    }
}
