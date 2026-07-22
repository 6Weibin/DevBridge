package com.devbridge.server.ai.agent.event;

/**
 * 已持久化 Agent Event 的实时广播端口。
 *
 * <p>by AI.Coding</p>
 */
public interface AgentEventBroadcaster {

    /**
     * 向当前任务订阅者广播已持久化事件。
     *
     * @param event 已持久化事件
     */
    void broadcast(AgentEvent event);
}
