package com.devbridge.server.ai.agent.runtime;

import com.devbridge.server.ai.agent.checkpoint.AgentToolCallCheckpoint;

/**
 * 工具幂等预留决策。
 *
 * <p>by AI.Coding</p>
 *
 * @param execute 是否允许首次执行
 * @param checkpoint 当前工具调用快照
 */
public record AgentToolExecutionDecision(boolean execute, AgentToolCallCheckpoint checkpoint) {
}
