package com.devbridge.server.ai.agent.checkpoint;

/**
 * Agent Checkpoint 文件存储异常。
 *
 * <p>by AI.Coding</p>
 */
public class AgentCheckpointStoreException extends RuntimeException {

    /**
     * 创建 Checkpoint 存储异常。
     *
     * @param message 错误摘要
     * @param cause 原始异常
     */
    public AgentCheckpointStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
