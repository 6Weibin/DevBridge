package com.devbridge.server.ai.agent.runtime;

/**
 * Agent 可取消资源类型，用于观测取消传播范围。
 *
 * <p>by AI.Coding</p>
 */
public enum AgentCancellationHandleType {
    MODEL,
    TOOL,
    PROCESS
}
