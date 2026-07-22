package com.devbridge.server.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.model.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.jupiter.api.Test;

/**
 * 流式命令执行器测试，验证线程池饱和时的进程清理和错误边界。
 *
 * <p>by AI.Coding</p>
 */
class StreamingCommandRunnerTest {

    /**
     * 验证输出读取任务被拒绝时返回稳定业务错误，而不是遗留运行中进程。
     */
    @Test
    void startShouldFailExplicitlyWhenReaderExecutorRejects() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        try {
            StreamingCommandRunner runner = new StreamingCommandRunner(
                    task -> { throw new RejectedExecutionException("full"); },
                    scheduler);

            assertThatThrownBy(() -> runner.start(
                    List.of("sh", "-c", "sleep 30"),
                    Duration.ofSeconds(5),
                    line -> { },
                    line -> { }))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo("COMMAND_EXECUTOR_SATURATED"));
        } finally {
            scheduler.shutdownNow();
        }
    }
}
