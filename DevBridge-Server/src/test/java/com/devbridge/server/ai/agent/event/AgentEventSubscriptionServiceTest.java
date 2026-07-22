package com.devbridge.server.ai.agent.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.config.DevBridgeExecutorProperties;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.config.ToolExecutorConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent Event SSE 背压测试，验证慢客户端不会阻塞事件持久化和发布线程。
 *
 * <p>by AI.Coding</p>
 */
class AgentEventSubscriptionServiceTest {

    /**
     * 验证慢客户端队列满后只关闭该订阅，全部事件仍已持久化。
     *
     * @param tempDir 临时事件目录
     */
    @Test
    void slowSubscriberShouldBeClosedWithoutBlockingPublisher(@TempDir Path tempDir) throws Exception {
        TestRuntime runtime = runtime(tempDir, 8, true);
        try {
            runtime.subscriptions().subscribe("task-slow", 0);
            long started = System.nanoTime();
            for (int index = 0; index < 50; index++) {
                runtime.sequencer().publish("task-slow", request());
            }
            awaitSlowClose(runtime.subscriptions());

            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            AgentEventBackpressureSnapshot metrics = runtime.subscriptions().metrics();
            assertThat(elapsedMillis).isLessThan(2_000);
            assertThat(runtime.store().lastSequence("task-slow")).isEqualTo(50);
            assertThat(metrics.slowSubscriptionsClosed()).isEqualTo(1);
            assertThat(metrics.activeSubscriptions()).isZero();
            assertThat(metrics.queuedEvents()).isZero();
        } finally {
            runtime.releaseEmitter();
            runtime.executor().shutdownNow();
        }
    }

    /**
     * 验证正常客户端按批次持续发送且队列最终清空。
     *
     * @param tempDir 临时事件目录
     */
    @Test
    void fastSubscriberShouldDrainAllQueuedEvents(@TempDir Path tempDir) throws Exception {
        TestRuntime runtime = runtime(tempDir, 128, false);
        try {
            runtime.subscriptions().subscribe("task-fast", 0);
            for (int index = 0; index < 100; index++) {
                runtime.sequencer().publish("task-fast", request());
            }
            awaitSent(runtime.subscriptions(), 100);

            AgentEventBackpressureSnapshot metrics = runtime.subscriptions().metrics();
            assertThat(metrics.sentEvents()).isEqualTo(100);
            assertThat(metrics.queuedEvents()).isZero();
            assertThat(metrics.slowSubscriptionsClosed()).isZero();
        } finally {
            runtime.executor().shutdownNow();
        }
    }

    /**
     * 创建事件存储、序列器、订阅器和测试 Emitter。
     *
     * @param root 临时目录
     * @param subscriberCapacity 订阅队列容量
     * @param blocking 是否阻塞发送
     * @return 测试运行环境
     */
    private TestRuntime runtime(Path root, int subscriberCapacity, boolean blocking) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiAgentDataRoot(root.toString());
        FileAgentEventStore store = new FileAgentEventStore(
                properties,
                new AgentEventFileCodec(new ObjectMapper().findAndRegisterModules()));
        DevBridgeExecutorProperties executorProperties = new DevBridgeExecutorProperties();
        executorProperties.setAgentEventCorePoolSize(1);
        executorProperties.setAgentEventMaxPoolSize(1);
        executorProperties.setAgentEventQueueCapacity(8);
        ExecutorService executor = new ToolExecutorConfiguration().agentEventExecutor(executorProperties);
        CountDownLatch release = new CountDownLatch(blocking ? 1 : 0);
        SseEmitter emitter = blocking ? new BlockingEmitter(release) : new CountingEmitter();
        AgentEventSubscriptionService subscriptions = new AgentEventSubscriptionService(
                store,
                executor,
                subscriberCapacity,
                () -> emitter);
        return new TestRuntime(
                store,
                subscriptions,
                new AgentEventSequencer(store, subscriptions),
                executor,
                release);
    }

    /**
     * 创建合法输出增量事件请求。
     *
     * @return 事件请求
     */
    private AgentEventRequest request() {
        AgentEventContext context = new AgentEventContext(
                "conversation", "turn", "step", null, null, "model", 1L);
        return new AgentEventRequest(
                AgentEventType.OUTPUT_DELTA,
                AgentEventScope.OUTPUT,
                context,
                Map.of("content", "delta"),
                Instant.now(),
                "test");
    }

    /**
     * 等待慢订阅被背压策略关闭。
     *
     * @param subscriptions 订阅服务
     */
    private void awaitSlowClose(AgentEventSubscriptionService subscriptions) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (subscriptions.metrics().slowSubscriptionsClosed() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    /**
     * 等待指定数量事件完成发送。
     *
     * @param subscriptions 订阅服务
     * @param expected 预期数量
     */
    private void awaitSent(AgentEventSubscriptionService subscriptions, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (subscriptions.metrics().sentEvents() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    /**
     * 阻塞发送的测试 Emitter，用于模拟读取速度极慢的客户端。
     *
     * <p>by AI.Coding</p>
     */
    private static final class BlockingEmitter extends SseEmitter {

        private final CountDownLatch release;

        /**
         * 创建阻塞 Emitter。
         *
         * @param release 释放信号
         */
        private BlockingEmitter(CountDownLatch release) {
            this.release = release;
        }

        /**
         * 阻塞事件发送直到测试释放。
         *
         * @param builder SSE 事件
         * @throws IOException 等待中断时抛出
         */
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("send interrupted", ex);
            }
        }
    }

    /**
     * 立即接收事件的测试 Emitter。
     *
     * <p>by AI.Coding</p>
     */
    private static final class CountingEmitter extends SseEmitter {

        private final AtomicInteger sent = new AtomicInteger();

        /**
         * 记录发送次数。
         *
         * @param builder SSE 事件
         */
        @Override
        public void send(SseEventBuilder builder) {
            sent.incrementAndGet();
        }
    }

    /**
     * 测试运行依赖集合。
     *
     * @param store 事件 Store
     * @param subscriptions 订阅服务
     * @param sequencer 事件序列器
     * @param executor 事件执行器
     * @param emitterRelease Emitter 释放信号
     */
    private record TestRuntime(
            FileAgentEventStore store,
            AgentEventSubscriptionService subscriptions,
            AgentEventSequencer sequencer,
            ExecutorService executor,
            CountDownLatch emitterRelease) {

        /**
         * 释放可能阻塞的测试 Emitter。
         */
        private void releaseEmitter() {
            emitterRelease.countDown();
        }
    }
}
