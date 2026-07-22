package com.devbridge.server.ai.agent.runtime;

/**
 * Agent 资源访问模式，共享用于只读，独占用于有副作用操作。
 *
 * <p>by AI.Coding</p>
 */
public enum AgentResourceMode {
    SHARED,
    EXCLUSIVE
}
