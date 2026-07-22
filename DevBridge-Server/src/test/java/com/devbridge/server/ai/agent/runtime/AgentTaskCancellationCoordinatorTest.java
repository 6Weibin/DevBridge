package com.devbridge.server.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Agent 任务取消协调器测试，覆盖模型、工具和进程的统一传播边界。
 *
 * <p>by AI.Coding</p>
 */
class AgentTaskCancellationCoordinatorTest {

    /**
     * 验证一次任务取消会传播到全部已注册句柄且重复取消幂等。
     */
    @Test
    void cancelShouldPropagateToModelToolAndProcessExactlyOnce() {
        AgentTaskCancellationCoordinator coordinator = new AgentTaskCancellationCoordinator();
        AgentCancellationScope scope = coordinator.open("task-1");
        List<String> canceled = new ArrayList<>();
        scope.register(AgentCancellationHandleType.MODEL, "model-1", () -> canceled.add("model"));
        scope.register(AgentCancellationHandleType.TOOL, "tool-1", () -> canceled.add("tool"));
        scope.register(AgentCancellationHandleType.PROCESS, "process-1", () -> canceled.add("process"));

        AgentCancellationResult first = coordinator.cancel("task-1", "用户取消");
        AgentCancellationResult second = coordinator.cancel("task-1", "重复取消");

        assertThat(canceled).containsExactlyInAnyOrder("model", "tool", "process");
        assertThat(first.newlyCanceled()).isTrue();
        assertThat(first.registeredHandleCount()).isEqualTo(3);
        assertThat(first.canceledHandleCount()).isEqualTo(3);
        assertThat(second.newlyCanceled()).isFalse();
    }

    /**
     * 验证取消后的晚注册句柄立即取消，避免竞态下新启动的工具逃逸。
     */
    @Test
    void registerShouldCancelImmediatelyWhenScopeAlreadyCanceled() {
        AgentTaskCancellationCoordinator coordinator = new AgentTaskCancellationCoordinator();
        AgentCancellationScope scope = coordinator.open("task-2");
        coordinator.cancel("task-2", "用户取消");
        AtomicInteger canceled = new AtomicInteger();

        AgentCancellationRegistration registration = scope.register(
                AgentCancellationHandleType.TOOL,
                "late-tool",
                canceled::incrementAndGet);

        assertThat(canceled).hasValue(1);
        assertThat(registration.active()).isFalse();
        assertThat(scope.isCancellationRequested()).isTrue();
        assertThat(scope.reason()).isEqualTo("用户取消");
    }

    /**
     * 验证单个取消回调失败不会阻断其他句柄，并返回有界诊断信息。
     */
    @Test
    void cancelShouldIsolateHandleFailureAndContinue() {
        AgentTaskCancellationCoordinator coordinator = new AgentTaskCancellationCoordinator();
        AgentCancellationScope scope = coordinator.open("task-3");
        AtomicInteger canceled = new AtomicInteger();
        scope.register(AgentCancellationHandleType.MODEL, "broken-model", () -> {
            throw new IllegalStateException("provider failure");
        });
        scope.register(AgentCancellationHandleType.PROCESS, "process-1", canceled::incrementAndGet);

        AgentCancellationResult result = coordinator.cancel("task-3", "用户取消");

        assertThat(canceled).hasValue(1);
        assertThat(result.canceledHandleCount()).isEqualTo(1);
        assertThat(result.failures()).containsExactly("MODEL:broken-model:provider failure");
    }

    /**
     * 验证执行完成前主动注销的句柄不会在后续取消中重复调用。
     */
    @Test
    void closeRegistrationShouldRemoveCompletedHandle() {
        AgentTaskCancellationCoordinator coordinator = new AgentTaskCancellationCoordinator();
        AgentCancellationScope scope = coordinator.open("task-4");
        AtomicInteger canceled = new AtomicInteger();
        AgentCancellationRegistration registration = scope.register(
                AgentCancellationHandleType.TOOL,
                "completed-tool",
                canceled::incrementAndGet);

        registration.close();
        AgentCancellationResult result = coordinator.cancel("task-4", "用户取消");

        assertThat(canceled).hasValue(0);
        assertThat(result.registeredHandleCount()).isZero();
    }

    /** 阻塞清理达到期限后必须返回失败摘要，不能永久卡住取消终态。 */
    @Test
    void cancelShouldReturnAfterCleanupTimeout() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AgentCancellationScope scope = new AgentCancellationScope(
                    "task-timeout", executor, Duration.ofMillis(50));
            scope.register(AgentCancellationHandleType.PROCESS, "blocked", () -> {
                try {
                    Thread.sleep(10_000L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });

            AgentCancellationResult result = scope.cancel("用户取消");

            assertThat(result.failures()).singleElement().asString().contains("取消清理超时");
        } finally {
            executor.shutdownNow();
        }
    }
}
