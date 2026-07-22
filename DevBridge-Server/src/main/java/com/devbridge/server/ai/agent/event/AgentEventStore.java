package com.devbridge.server.ai.agent.event;

import java.util.List;

/**
 * Agent Event 持久化端口。
 *
 * <p>by AI.Coding</p>
 */
public interface AgentEventStore {

    /**
     * 追加已经分配序号的事件。
     *
     * @param event Agent Event
     * @return 已持久化事件
     */
    AgentEvent append(AgentEvent event);

    /**
     * 获取任务最后持久化事件序号。
     *
     * @param taskId 任务标识
     * @return 事件高水位，无事件时为零
     */
    long lastSequence(String taskId);

    /**
     * 从游标之后按序读取事件。
     *
     * @param taskId 任务标识
     * @param afterSequence 已处理序号
     * @param limit 最大返回数量
     * @return 严格有序事件
     */
    List<AgentEvent> readAfter(String taskId, long afterSequence, int limit);
}
