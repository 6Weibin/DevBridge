package com.devbridge.server.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Agent 资源锁管理器测试，覆盖设备共享读、独占写、超时和租约恢复。
 *
 * <p>by AI.Coding</p>
 */
class AgentResourceLockManagerTest {

    /**
     * 验证同一设备的共享读租约可并发获取。
     */
    @Test
    void sharedDeviceLocksShouldRunConcurrently() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        AgentResourceLockManager manager = new AgentResourceLockManager(scheduler);
        try (AgentResourceLease first = acquire(manager, "task-1", AgentResourceMode.SHARED);
                AgentResourceLease second = acquire(manager, "task-2", AgentResourceMode.SHARED)) {
            assertThat(first.closed()).isFalse();
            assertThat(second.closed()).isFalse();
            assertThat(manager.snapshot()).hasSize(2);
        } finally {
            scheduler.shutdownNow();
        }
    }

    /**
     * 验证独占写必须等待已有共享读释放后才能执行。
     */
    @Test
    void exclusiveDeviceLockShouldWaitForSharedReaders() throws Exception {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AgentResourceLockManager manager = new AgentResourceLockManager(scheduler);
        AgentResourceLease shared = acquire(manager, "reader", AgentResourceMode.SHARED);
        try {
            CompletableFuture<AgentResourceLease> waiting = CompletableFuture.supplyAsync(
                    () -> acquire(manager, "writer", AgentResourceMode.EXCLUSIVE),
                    worker);
            Thread.sleep(100);
            assertThat(waiting).isNotDone();

            shared.close();
            try (AgentResourceLease exclusive = waiting.get(2, TimeUnit.SECONDS)) {
                assertThat(exclusive.taskId()).isEqualTo("writer");
            }
        } finally {
            shared.close();
            worker.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    /**
     * 验证获取超时返回资源和持有任务诊断。
     */
    @Test
    void acquireShouldFailWithHolderDetailsWhenTimeoutExpires() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        AgentResourceLockManager manager = new AgentResourceLockManager(scheduler);
        try (AgentResourceLease ignored = acquire(manager, "owner-task", AgentResourceMode.EXCLUSIVE)) {
            assertThatThrownBy(() -> manager.acquire(
                    "waiting-task",
                    List.of(request(AgentResourceMode.EXCLUSIVE)),
                    Duration.ofMillis(100),
                    Duration.ofSeconds(5)))
                    .isInstanceOf(AgentResourceLockException.class)
                    .hasMessageContaining("owner-task")
                    .hasMessageContaining("device-1");
        } finally {
            scheduler.shutdownNow();
        }
    }

    /**
     * 验证租约过期后冲突任务可自动取得资源。
     */
    @Test
    void expiredLeaseShouldReleaseResourceAutomatically() throws Exception {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        AgentResourceLockManager manager = new AgentResourceLockManager(scheduler);
        AgentResourceLease expired = manager.acquire(
                "expired-task",
                List.of(request(AgentResourceMode.EXCLUSIVE)),
                Duration.ofSeconds(1),
                Duration.ofMillis(100));
        Thread.sleep(200);

        try (AgentResourceLease next = acquire(manager, "next-task", AgentResourceMode.EXCLUSIVE)) {
            assertThat(expired.closed()).isTrue();
            assertThat(next.taskId()).isEqualTo("next-task");
        } finally {
            expired.close();
            scheduler.shutdownNow();
        }
    }

    /**
     * 验证反向声明的多资源请求通过稳定排序完成，不发生交叉死锁。
     */
    @Test
    void multipleResourcesShouldUseStableOrderWithoutDeadlock() throws Exception {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        AgentResourceLockManager manager = new AgentResourceLockManager(scheduler);
        AgentResourceRequest device = request(AgentResourceMode.EXCLUSIVE);
        AgentResourceRequest path = new AgentResourceRequest(
                new AgentResourceKey(AgentResourceType.LOCAL_PATH, "/tmp/devbridge"),
                AgentResourceMode.EXCLUSIVE);
        try {
            CompletableFuture<Void> first = useResources(manager, workers, "task-a", List.of(device, path));
            CompletableFuture<Void> second = useResources(manager, workers, "task-b", List.of(path, device));

            CompletableFuture.allOf(first, second).get(3, TimeUnit.SECONDS);
            assertThat(manager.snapshot()).isEmpty();
        } finally {
            workers.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    /**
     * 验证任务取消作用域可直接释放资源租约，避免取消后继续占锁。
     */
    @Test
    void taskCancellationShouldReleaseResourceLease() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        AgentResourceLockManager manager = new AgentResourceLockManager(scheduler);
        AgentTaskCancellationCoordinator cancellations = new AgentTaskCancellationCoordinator();
        AgentCancellationScope scope = cancellations.open("task-cancel");
        AgentResourceLease lease = acquire(manager, "task-cancel", AgentResourceMode.EXCLUSIVE);
        scope.register(AgentCancellationHandleType.TOOL, lease.leaseId(), lease::close);
        try {
            cancellations.cancel("task-cancel", "用户取消");

            assertThat(lease.closed()).isTrue();
            assertThat(manager.snapshot()).isEmpty();
        } finally {
            lease.close();
            scheduler.shutdownNow();
        }
    }

    /**
     * 获取默认设备租约。
     *
     * @param manager 锁管理器
     * @param taskId 任务标识
     * @param mode 锁模式
     * @return 资源租约
     */
    private AgentResourceLease acquire(
            AgentResourceLockManager manager,
            String taskId,
            AgentResourceMode mode) {
        return manager.acquire(
                taskId,
                List.of(request(mode)),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }

    /**
     * 创建默认设备资源请求。
     *
     * @param mode 锁模式
     * @return 资源请求
     */
    private AgentResourceRequest request(AgentResourceMode mode) {
        return new AgentResourceRequest(
                new AgentResourceKey(AgentResourceType.DEVICE, "device-1"),
                mode);
    }

    /**
     * 异步获取并短暂使用多资源租约。
     *
     * @param manager 锁管理器
     * @param executor 工作线程池
     * @param taskId 任务标识
     * @param requests 资源请求
     * @return 异步完成标识
     */
    private CompletableFuture<Void> useResources(
            AgentResourceLockManager manager,
            ExecutorService executor,
            String taskId,
            List<AgentResourceRequest> requests) {
        return CompletableFuture.runAsync(() -> {
            try (AgentResourceLease ignored = manager.acquire(
                    taskId, requests, Duration.ofSeconds(2), Duration.ofSeconds(5))) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }, executor);
    }
}
