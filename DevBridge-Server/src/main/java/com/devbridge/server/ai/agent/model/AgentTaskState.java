package com.devbridge.server.ai.agent.model;

/**
 * Agent Task 生命周期状态，状态转换规则由后续状态机统一控制。
 *
 * <p>by AI.Coding</p>
 */
public enum AgentTaskState {
    CREATED,
    PLANNING,
    RUNNING,
    WAITING_CONFIRMATION,
    WAITING_INPUT,
    PAUSED,
    RETRYING,
    COMPLETED,
    FAILED,
    CANCELED
}
