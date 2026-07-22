package com.devbridge.server.ai.agent.runtime;

/**
 * Agent 可取消资源句柄，隔离 Runtime 与 Provider、工具和进程具体类型。
 *
 * <p>by AI.Coding</p>
 */
@FunctionalInterface
public interface AgentCancellationHandle {

    /**
     * 取消底层资源。
     */
    void cancel();
}
