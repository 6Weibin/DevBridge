package com.devbridge.server.ai.agent.api;

/**
 * 创建 Agent Task 的 REST 请求。
 *
 * <p>by AI.Coding</p>
 *
 * @param conversationId 历史会话标识
 * @param goal 用户业务目标
 * @param idempotencyKey 客户端创建幂等键
 */
public record AgentTaskCreateRequest(String conversationId, String goal, String idempotencyKey) {
}
