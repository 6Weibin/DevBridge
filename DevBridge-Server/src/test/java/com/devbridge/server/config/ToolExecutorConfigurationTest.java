package com.devbridge.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.command.StreamingCommandRunner;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 工具执行线程池配置测试，验证并发、队列和关闭边界。
 *
 * <p>by AI.Coding</p>
 */
class ToolExecutorConfigurationTest {

    /**
     * 验证工作线程和队列均占满后明确拒绝新任务。
     */
    @Test
    void toolExecutorShouldRejectWhenPoolAndQueueAreFull() throws InterruptedException {
        DevBridgeExecutorProperties properties = minimalProperties();
        ExecutorService executor = new ToolExecutorConfiguration().toolExecutionExecutor(properties);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> await(running, release));
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> { });

            assertThatThrownBy(() -> executor.execute(() -> { }))
                    .isInstanceOf(RejectedExecutionException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 验证命令 IO 线程池使用配置的最大线程数和有界队列。
     */
    @Test
    void commandIoExecutorShouldUseConfiguredBounds() {
        DevBridgeExecutorProperties properties = minimalProperties();
        properties.setCommandIoMaxPoolSize(3);
        properties.setCommandIoQueueCapacity(5);
        ExecutorService executor = new ToolExecutorConfiguration().commandIoExecutor(properties);
        try {
            ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;

            assertThat(pool.getMaximumPoolSize()).isEqualTo(3);
            assertThat(pool.getQueue().remainingCapacity()).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 验证 Spring 可按限定名注入执行器，并在上下文关闭时统一关闭线程池。
     */
    @Test
    void springContextShouldInjectAndCloseManagedExecutors() {
        ExecutorService toolExecutor;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DevBridgeExecutorProperties.class);
            context.registerBean(DevBridgeProperties.class);
            context.register(ToolExecutorConfiguration.class);
            context.registerBean(CommandRunner.class);
            context.registerBean(StreamingCommandRunner.class);
            context.refresh();
            toolExecutor = context.getBean(
                    ToolExecutorConfiguration.TOOL_EXECUTION_EXECUTOR,
                    ExecutorService.class);

            assertThat(context.getBean(CommandRunner.class)).isNotNull();
            assertThat(context.getBean(StreamingCommandRunner.class)).isNotNull();
            assertThat(toolExecutor.isShutdown()).isFalse();
        }

        assertThat(toolExecutor.isShutdown()).isTrue();
    }

    /**
     * 创建最小线程池配置，便于稳定触发饱和边界。
     *
     * @return 测试配置
     */
    private DevBridgeExecutorProperties minimalProperties() {
        DevBridgeExecutorProperties properties = new DevBridgeExecutorProperties();
        properties.setToolCorePoolSize(1);
        properties.setToolMaxPoolSize(1);
        properties.setToolQueueCapacity(1);
        properties.setCommandIoCorePoolSize(1);
        properties.setCommandIoMaxPoolSize(1);
        properties.setCommandIoQueueCapacity(1);
        properties.setKeepAlive(Duration.ofSeconds(1));
        return properties;
    }

    /**
     * 阻塞工作线程直到测试释放，确保队列饱和状态可重复。
     *
     * @param running 已开始信号
     * @param release 释放信号
     */
    private void await(CountDownLatch running, CountDownLatch release) {
        running.countDown();
        try {
            release.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
