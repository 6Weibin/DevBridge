package com.devbridge.server.ai.agent.event;

/**
 * Agent Event 的任务内身份信息。
 *
 * <p>by AI.Coding</p>
 *
 * @param taskId 任务标识
 * @param eventSequence 单任务序号
 * @param eventType 事件类型
 * @param scope 事件作用域
 */
public record AgentEventIdentity(
        String taskId, long eventSequence, AgentEventType eventType, AgentEventScope scope) {
}
