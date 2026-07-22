package com.devbridge.server.ai.agent.runtime;

/**
 * Agent 资源锁获取异常，携带当前阻塞资源的安全诊断摘要。
 *
 * <p>by AI.Coding</p>
 */
public class AgentResourceLockException extends RuntimeException {

    /**
     * 创建资源锁异常。
     *
     * @param message 诊断消息
     */
    public AgentResourceLockException(String message) {
        super(message);
    }
}
