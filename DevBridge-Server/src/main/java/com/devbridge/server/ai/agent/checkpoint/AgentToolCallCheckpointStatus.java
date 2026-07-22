package com.devbridge.server.ai.agent.checkpoint;

/**
 * Checkpoint 中的工具调用状态，用于恢复时判断是否允许重试。
 *
 * <p>by AI.Coding</p>
 */
public enum AgentToolCallCheckpointStatus {
    RESERVED,
    REQUESTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED,
    UNKNOWN
}
