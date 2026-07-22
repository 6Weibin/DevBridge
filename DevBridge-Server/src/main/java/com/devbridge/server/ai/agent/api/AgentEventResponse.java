package com.devbridge.server.ai.agent.api;

import com.devbridge.server.ai.agent.event.AgentEvent;
import com.devbridge.server.ai.agent.event.AgentEventContext;
import com.devbridge.server.ai.agent.event.AgentEventScope;
import com.devbridge.server.ai.agent.event.AgentEventTiming;
import com.devbridge.server.ai.agent.event.AgentEventType;
import java.util.Map;

/**
 * Agent Event API 响应，序号使用字符串避免 JavaScript 精度损失。
 *
 * <p>by AI.Coding</p>
 *
 * @param eventId 稳定事件标识
 * @param eventSequence 十进制字符串序号
 * @param eventType 事件类型
 * @param scope 事件作用域
 * @param context 关联标识
 * @param timing 事件时间
 * @param producer 事件生产者
 * @param payload 结构化载荷
 */
public record AgentEventResponse(
        String eventId,
        String eventSequence,
        AgentEventType eventType,
        AgentEventScope scope,
        AgentEventContext context,
        AgentEventTiming timing,
        String producer,
        Map<String, Object> payload) {

    /**
     * 从持久 Agent Event 构造 API 响应。
     *
     * @param event Agent Event
     * @return API 响应
     */
    public static AgentEventResponse from(AgentEvent event) {
        return new AgentEventResponse(
                event.eventId(), Long.toString(event.eventSequence()), event.eventType(), event.scope(),
                event.context(), event.timing(), event.producer(), event.payload());
    }
}
