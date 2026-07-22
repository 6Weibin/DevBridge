package com.devbridge.server.ai.agent.runtime;

import static com.devbridge.server.config.ToolExecutorConfiguration.COMMAND_TIMEOUT_EXECUTOR;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Agent 资源锁管理器，通过共享/独占租约协调跨步骤设备和本机资源访问。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AgentResourceLockManager {

    private final Object monitor = new Object();
    private final Map<AgentResourceKey, ResourceState> resources = new LinkedHashMap<>();
    private final Map<String, AgentResourceLease> leases = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler;

    /**
     * 注入受管超时调度器。
     *
     * @param scheduler 超时调度器
     */
    public AgentResourceLockManager(
            @Qualifier(COMMAND_TIMEOUT_EXECUTOR) ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 在指定等待时间内原子获取全部资源并创建自动过期租约。
     *
     * @param taskId 任务标识
     * @param requests 资源请求
     * @param waitTimeout 最大等待时间
     * @param leaseDuration 租约有效期
     * @return 资源租约
     */
    public AgentResourceLease acquire(
            String taskId,
            List<AgentResourceRequest> requests,
            Duration waitTimeout,
            Duration leaseDuration) {
        String normalizedTaskId = normalizeTaskId(taskId);
        List<AgentResourceRequest> normalizedRequests = normalizeRequests(requests);
        validateDuration(waitTimeout, "waitTimeout");
        validateDuration(leaseDuration, "leaseDuration");
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        synchronized (monitor) {
            awaitAvailability(normalizedRequests, deadline);
            return createLease(normalizedTaskId, normalizedRequests, leaseDuration);
        }
    }

    /**
     * 返回当前活动资源锁快照。
     *
     * @return 活动锁列表
     */
    public List<AgentResourceLockSnapshot> snapshot() {
        synchronized (monitor) {
            List<AgentResourceLockSnapshot> snapshots = new ArrayList<>();
            for (AgentResourceLease lease : leases.values()) {
                for (AgentResourceRequest request : lease.requests()) {
                    snapshots.add(new AgentResourceLockSnapshot(
                            lease.leaseId(), lease.taskId(), request.resource(), request.mode(),
                            lease.acquiredAt(), lease.expiresAt()));
                }
            }
            return List.copyOf(snapshots);
        }
    }

    /**
     * 等待全部资源可用，超时或中断时返回阻塞诊断。
     *
     * @param requests 资源请求
     * @param deadline 纳秒截止时间
     */
    private void awaitAvailability(List<AgentResourceRequest> requests, long deadline) {
        while (!available(requests)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw lockException(requests, "资源锁等待超时");
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw lockException(requests, "资源锁等待被中断");
            }
        }
    }

    /**
     * 创建租约、占用资源并安排过期释放。
     *
     * @param taskId 任务标识
     * @param requests 资源请求
     * @param leaseDuration 租约时长
     * @return 资源租约
     */
    private AgentResourceLease createLease(
            String taskId,
            List<AgentResourceRequest> requests,
            Duration leaseDuration) {
        String leaseId = UUID.randomUUID().toString();
        Instant acquiredAt = Instant.now();
        AgentResourceLease lease = new AgentResourceLease(
                leaseId,
                taskId,
                requests,
                acquiredAt,
                acquiredAt.plus(leaseDuration),
                () -> release(leaseId));
        occupy(lease);
        leases.put(leaseId, lease);
        try {
            lease.setExpiryFuture(scheduler.schedule(
                    lease::close,
                    leaseDuration.toMillis(),
                    TimeUnit.MILLISECONDS));
            return lease;
        } catch (RejectedExecutionException ex) {
            release(leaseId);
            throw new AgentResourceLockException("资源租约调度器已满");
        }
    }

    /**
     * 标记租约持有的全部资源。
     *
     * @param lease 资源租约
     */
    private void occupy(AgentResourceLease lease) {
        for (AgentResourceRequest request : lease.requests()) {
            resources.computeIfAbsent(request.resource(), ignored -> new ResourceState())
                    .acquire(lease.leaseId(), lease.taskId(), request.mode());
        }
    }

    /**
     * 幂等释放租约并清理空闲资源状态。
     *
     * @param leaseId 租约标识
     */
    private void release(String leaseId) {
        synchronized (monitor) {
            AgentResourceLease lease = leases.remove(leaseId);
            if (lease == null) {
                return;
            }
            for (AgentResourceRequest request : lease.requests()) {
                ResourceState state = resources.get(request.resource());
                if (state != null) {
                    state.release(leaseId);
                    if (state.empty()) {
                        resources.remove(request.resource());
                    }
                }
            }
            monitor.notifyAll();
        }
    }

    /**
     * 判断全部资源当前是否可按请求模式获取。
     *
     * @param requests 资源请求
     * @return 可获取返回 true
     */
    private boolean available(List<AgentResourceRequest> requests) {
        for (AgentResourceRequest request : requests) {
            ResourceState state = resources.get(request.resource());
            if (state != null && !state.available(request.mode())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 生成包含资源和当前持有任务的诊断异常。
     *
     * @param requests 等待资源
     * @param reason 失败原因
     * @return 资源锁异常
     */
    private AgentResourceLockException lockException(
            List<AgentResourceRequest> requests,
            String reason) {
        List<String> details = new ArrayList<>();
        for (AgentResourceRequest request : requests) {
            ResourceState state = resources.get(request.resource());
            if (state != null && !state.available(request.mode())) {
                details.add(request.resource().displayName() + " holders=" + state.holders());
            }
        }
        return new AgentResourceLockException(reason + ": " + String.join(", ", details));
    }

    /**
     * 合并重复资源请求并按资源键稳定排序。
     *
     * @param requests 原始请求
     * @return 规范请求
     */
    private List<AgentResourceRequest> normalizeRequests(List<AgentResourceRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("资源请求不能为空");
        }
        Map<AgentResourceKey, AgentResourceMode> merged = new TreeMap<>();
        for (AgentResourceRequest request : requests) {
            if (request == null) {
                throw new IllegalArgumentException("资源请求不能为空");
            }
            merged.merge(request.resource(), request.mode(), (left, right) ->
                    left == AgentResourceMode.EXCLUSIVE || right == AgentResourceMode.EXCLUSIVE
                            ? AgentResourceMode.EXCLUSIVE
                            : AgentResourceMode.SHARED);
        }
        return merged.entrySet().stream()
                .map(entry -> new AgentResourceRequest(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 校验任务标识。
     *
     * @param taskId 原始任务标识
     * @return 规范任务标识
     */
    private String normalizeTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("任务标识不能为空");
        }
        return taskId.trim();
    }

    /**
     * 校验持续时间必须为正数。
     *
     * @param duration 持续时间
     * @param name 参数名
     */
    private void validateDuration(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }

    /**
     * 单资源持有状态。
     *
     * <p>by AI.Coding</p>
     */
    private static final class ResourceState {

        private final Map<String, String> shared = new LinkedHashMap<>();
        private String exclusiveLeaseId;
        private String exclusiveTaskId;

        /**
         * 判断资源是否可按指定模式获取。
         *
         * @param mode 访问模式
         * @return 可用返回 true
         */
        private boolean available(AgentResourceMode mode) {
            return mode == AgentResourceMode.SHARED
                    ? exclusiveLeaseId == null
                    : exclusiveLeaseId == null && shared.isEmpty();
        }

        /**
         * 记录资源持有者。
         *
         * @param leaseId 租约标识
         * @param taskId 任务标识
         * @param mode 访问模式
         */
        private void acquire(String leaseId, String taskId, AgentResourceMode mode) {
            if (mode == AgentResourceMode.SHARED) {
                shared.put(leaseId, taskId);
                return;
            }
            exclusiveLeaseId = leaseId;
            exclusiveTaskId = taskId;
        }

        /**
         * 释放指定租约。
         *
         * @param leaseId 租约标识
         */
        private void release(String leaseId) {
            shared.remove(leaseId);
            if (leaseId.equals(exclusiveLeaseId)) {
                exclusiveLeaseId = null;
                exclusiveTaskId = null;
            }
        }

        /**
         * 返回当前持有任务集合。
         *
         * @return 持有任务
         */
        private List<String> holders() {
            LinkedHashSet<String> holders = new LinkedHashSet<>(shared.values());
            if (exclusiveTaskId != null) {
                holders.add(exclusiveTaskId);
            }
            return List.copyOf(holders);
        }

        /**
         * 判断资源是否无持有者。
         *
         * @return 空闲返回 true
         */
        private boolean empty() {
            return shared.isEmpty() && exclusiveLeaseId == null;
        }
    }
}
