package com.devbridge.server.ai.agent.api;

/**
 * 用户确认决策请求。
 *
 * <p>by AI.Coding</p>
 *
 * @param reason 决策原因，可空
 */
public record AgentConfirmationDecisionRequest(String reason) {
}
