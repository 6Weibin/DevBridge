package com.devbridge.server.ai.agent.store;

import com.devbridge.server.ai.agent.model.AgentTask;
import java.util.List;

/**
 * Agent Task 分页结果，避免调用方一次加载全部任务。
 *
 * <p>by AI.Coding</p>
 *
 * @param items 当前页任务
 * @param page 从零开始的页码
 * @param size 每页数量
 * @param total 任务总数
 */
public record AgentTaskPage(List<AgentTask> items, int page, int size, long total) {

    /**
     * 固化分页结果为不可变集合。
     */
    public AgentTaskPage {
        items = List.copyOf(items);
    }
}
