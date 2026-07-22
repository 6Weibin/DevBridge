package com.devbridge.server.ai.agent.store;

/**
 * Agent Task 文件存储异常，保留稳定错误边界而不暴露底层文件细节。
 *
 * <p>by AI.Coding</p>
 */
public class AgentTaskStoreException extends RuntimeException {

    /**
     * 创建文件存储异常。
     *
     * @param message 错误摘要
     * @param cause 原始异常
     */
    public AgentTaskStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
