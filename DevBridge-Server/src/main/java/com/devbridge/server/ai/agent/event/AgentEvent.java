package com.devbridge.server.ai.agent.event;

import java.util.Map;

/**
 * 已持久化并分配单任务序号的 Agent Event。
 *
 * <p>by AI.Coding</p>
 *
 * @param schemaVersion 事件格式版本
 * @param identity 事件身份
 * @param context 关联标识
 * @param timing 事件时间
 * @param producer 事件生产者
 * @param payload 有界结构化载荷
 */
public record AgentEvent(
        String schemaVersion,
        AgentEventIdentity identity,
        AgentEventContext context,
        AgentEventTiming timing,
        String producer,
        Map<String, Object> payload) {

    /**
     * 固化事件载荷副本。
     */
    public AgentEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /**
     * 生成可用于 SSE 去重的稳定事件标识。
     *
     * @return 事件标识
     */
    public String eventId() {
        return taskId() + ":" + eventSequence();
    }

    /**
     * 获取任务标识，便于 Store 和 API 使用。
     *
     * @return 任务标识
     */
    public String taskId() {
        return identity.taskId();
    }

    /**
     * 获取单任务事件序号。
     *
     * @return 事件序号
     */
    public long eventSequence() {
        return identity.eventSequence();
    }

    /**
     * 获取事件类型。
     *
     * @return 事件类型
     */
    public AgentEventType eventType() {
        return identity.eventType();
    }

    /**
     * 获取事件作用域。
     *
     * @return 事件作用域
     */
    public AgentEventScope scope() {
        return identity.scope();
    }
}
