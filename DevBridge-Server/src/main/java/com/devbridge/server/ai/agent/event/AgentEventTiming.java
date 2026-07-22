package com.devbridge.server.ai.agent.event;

import java.time.Instant;

/**
 * Agent Event 的发生和记录时间。
 *
 * <p>by AI.Coding</p>
 *
 * @param occurredAt 事实发生时间
 * @param recordedAt 持久化记录时间
 */
public record AgentEventTiming(Instant occurredAt, Instant recordedAt) {
}
