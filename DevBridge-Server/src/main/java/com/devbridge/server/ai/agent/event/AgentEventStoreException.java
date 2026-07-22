package com.devbridge.server.ai.agent.event;

/**
 * Agent Event 文件存储异常。
 *
 * <p>by AI.Coding</p>
 */
public class AgentEventStoreException extends RuntimeException {

    /**
     * 创建事件存储异常。
     *
     * @param message 错误摘要
     * @param cause 原始异常
     */
    public AgentEventStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
