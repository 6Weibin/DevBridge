package com.devbridge.server.ai.agent.runtime;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 资源租约，支持主动释放和到期自动释放。
 *
 * <p>by AI.Coding</p>
 */
public final class AgentResourceLease implements AutoCloseable {

    private final String leaseId;
    private final String taskId;
    private final List<AgentResourceRequest> requests;
    private final Instant acquiredAt;
    private final Instant expiresAt;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ScheduledFuture<?> expiryFuture;

    /**
     * 创建资源租约。
     *
     * @param leaseId 租约标识
     * @param taskId 任务标识
     * @param requests 资源请求
     * @param acquiredAt 获取时间
     * @param expiresAt 过期时间
     * @param release 释放回调
     */
    AgentResourceLease(
            String leaseId,
            String taskId,
            List<AgentResourceRequest> requests,
            Instant acquiredAt,
            Instant expiresAt,
            Runnable release) {
        this.leaseId = leaseId;
        this.taskId = taskId;
        this.requests = List.copyOf(requests);
        this.acquiredAt = acquiredAt;
        this.expiresAt = expiresAt;
        this.release = release;
    }

    /**
     * 设置自动过期任务。
     *
     * @param expiryFuture 调度任务
     */
    void setExpiryFuture(ScheduledFuture<?> expiryFuture) {
        this.expiryFuture = expiryFuture;
    }

    /**
     * 获取租约标识。
     *
     * @return 租约标识
     */
    public String leaseId() {
        return leaseId;
    }

    /**
     * 获取任务标识。
     *
     * @return 任务标识
     */
    public String taskId() {
        return taskId;
    }

    /**
     * 获取资源请求。
     *
     * @return 资源请求
     */
    public List<AgentResourceRequest> requests() {
        return requests;
    }

    /**
     * 获取租约时间。
     *
     * @return 获取时间
     */
    public Instant acquiredAt() {
        return acquiredAt;
    }

    /**
     * 获取过期时间。
     *
     * @return 过期时间
     */
    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * 判断租约是否已关闭。
     *
     * @return 已关闭返回 true
     */
    public boolean closed() {
        return closed.get();
    }

    /**
     * 幂等释放全部资源并取消过期任务。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> future = expiryFuture;
        if (future != null) {
            future.cancel(false);
        }
        release.run();
    }
}
