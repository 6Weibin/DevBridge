package com.devbridge.server.ai.agent.confirmation;

/**
 * Agent 确认文件存储异常。
 *
 * <p>by AI.Coding</p>
 */
public class AgentConfirmationStoreException extends RuntimeException {

    /**
     * 创建确认存储异常。
     *
     * @param message 错误摘要
     * @param cause 原始异常
     */
    public AgentConfirmationStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
