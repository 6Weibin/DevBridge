package com.devbridge.server.ai.agent.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 取消句柄注册，资源正常结束后关闭注册可避免重复取消。
 *
 * <p>by AI.Coding</p>
 */
public final class AgentCancellationRegistration implements AutoCloseable {

    private final AtomicBoolean active;
    private final Runnable unregister;

    /**
     * 创建取消句柄注册。
     *
     * @param active 初始是否有效
     * @param unregister 注销回调
     */
    AgentCancellationRegistration(boolean active, Runnable unregister) {
        this.active = new AtomicBoolean(active);
        this.unregister = unregister == null ? () -> { } : unregister;
    }

    /**
     * 判断注册是否仍有效。
     *
     * @return 有效返回 true
     */
    public boolean active() {
        return active.get();
    }

    /**
     * 幂等注销句柄。
     */
    @Override
    public void close() {
        if (active.compareAndSet(true, false)) {
            unregister.run();
        }
    }
}
