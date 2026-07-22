package com.devbridge.server.ai.agent.api;

import com.devbridge.server.ai.agent.store.AgentTaskPage;
import java.util.List;

/**
 * Agent Task 分页 REST 响应。
 *
 * <p>by AI.Coding</p>
 *
 * @param items 当前页任务
 * @param page 页码
 * @param size 每页数量
 * @param total 总数
 */
public record AgentTaskPageResponse(
        List<AgentTaskResponse> items, int page, int size, long total) {

    /**
     * 从中立分页模型构造 REST 响应。
     *
     * @param source 任务分页
     * @return REST 分页响应
     */
    public static AgentTaskPageResponse from(AgentTaskPage source) {
        List<AgentTaskResponse> items = source.items().stream().map(AgentTaskResponse::from).toList();
        return new AgentTaskPageResponse(items, source.page(), source.size(), source.total());
    }
}
