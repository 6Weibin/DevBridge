package com.devbridge.server.ai.agent.runtime;

/**
 * Agent Task 幂等键与请求摘要冲突异常。
 *
 * <p>by AI.Coding</p>
 */
public class AgentTaskIdempotencyException extends RuntimeException {

    /**
     * 创建幂等冲突异常。
     *
     * @param message 冲突摘要
     */
    public AgentTaskIdempotencyException(String message) {
        super(message);
    }
}
