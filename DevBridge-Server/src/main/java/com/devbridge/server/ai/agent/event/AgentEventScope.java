package com.devbridge.server.ai.agent.event;

/**
 * Agent Event 归属范围。
 *
 * <p>by AI.Coding</p>
 */
public enum AgentEventScope {
    TASK,
    TURN,
    STEP,
    MODEL_CALL,
    TOOL_CALL,
    CONFIRMATION,
    OUTPUT
}
