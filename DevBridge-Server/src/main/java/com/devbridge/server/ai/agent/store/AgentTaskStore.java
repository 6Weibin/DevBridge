package com.devbridge.server.ai.agent.store;

import com.devbridge.server.ai.agent.model.AgentTask;
import java.util.Optional;

/**
 * Agent Task 存储端口，Runtime 只依赖该中立接口。
 *
 * <p>by AI.Coding</p>
 */
public interface AgentTaskStore {

    /**
     * 保存一个新任务；相同任务标识不能覆盖既有任务。
     *
     * @param task 新任务快照
     * @return 已保存任务
     */
    AgentTask save(AgentTask task);

    /**
     * 使用乐观版本更新任务，防止并发推进覆盖彼此状态。
     *
     * @param task 新任务快照
     * @param expectedVersion 预期旧版本
     * @return 已更新任务
     */
    AgentTask update(AgentTask task, long expectedVersion);

    /**
     * 按任务标识读取当前状态。
     *
     * @param taskId 任务标识
     * @return 任务存在时返回当前快照
     */
    Optional<AgentTask> findById(String taskId);

    /**
     * 分页读取任务当前快照。
     *
     * @param page 从零开始的页码
     * @param size 每页数量
     * @return 任务分页
     */
    AgentTaskPage list(int page, int size);

    /** 恢复或重建实现维护的任务索引。 */
    void recoverIndex();
}
