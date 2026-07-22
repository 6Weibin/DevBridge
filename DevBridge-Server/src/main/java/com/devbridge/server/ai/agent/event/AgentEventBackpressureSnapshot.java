package com.devbridge.server.ai.agent.event;

/**
 * Agent Event 背压指标快照，用于诊断慢客户端和队列压力。
 *
 * <p>by AI.Coding</p>
 *
 * @param activeSubscriptions 活动订阅数
 * @param queuedEvents 当前订阅队列事件数
 * @param sentEvents 已发送事件数
 * @param slowSubscriptionsClosed 因背压关闭的慢订阅数
 */
public record AgentEventBackpressureSnapshot(
        int activeSubscriptions,
        int queuedEvents,
        long sentEvents,
        long slowSubscriptionsClosed) {
}
