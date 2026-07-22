package com.devbridge.server.ai.agent.event;

import java.time.Instant;
import java.util.Map;

/**
 * 待分配序号的 Agent Event 请求。
 *
 * <p>by AI.Coding</p>
 *
 * @param eventType 事件类型
 * @param scope 事件作用域
 * @param context 关联标识
 * @param payload 有界结构化载荷
 * @param occurredAt 事实发生时间
 * @param producer 事件生产者
 */
public record AgentEventRequest(
        AgentEventType eventType,
        AgentEventScope scope,
        AgentEventContext context,
        Map<String, Object> payload,
        Instant occurredAt,
        String producer) {

    /**
     * 固化事件载荷副本，避免发布过程中被修改。
     */
    public AgentEventRequest {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
