package com.devbridge.server.ai.agent.store;

import com.devbridge.server.ai.agent.model.AgentTask;
import java.util.Optional;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Agent Task 临时内存存储，使 Runtime 骨架可独立运行。
 *
 * <p>M1-03 将按文件存储契约替换该实现；这里不承诺跨进程持久化。</p>
 *
 * <p>by AI.Coding</p>
 */
public class InMemoryAgentTaskStore implements AgentTaskStore {

    private final ConcurrentMap<String, AgentTask> tasks = new ConcurrentHashMap<>();

    /**
     * 原子保存新任务，避免并发创建时覆盖相同任务标识。
     *
     * @param task 新任务快照
     * @return 已保存任务
     */
    @Override
    public AgentTask save(AgentTask task) {
        AgentTask existing = tasks.putIfAbsent(task.taskId(), task);
        if (existing != null) {
            throw new IllegalStateException("Agent 任务已经存在: " + task.taskId());
        }
        return task;
    }

    /**
     * 原子检查旧版本并替换任务快照，冲突时拒绝覆盖。
     *
     * @param task 新任务快照
     * @param expectedVersion 预期旧版本
     * @return 已更新任务
     */
    @Override
    public AgentTask update(AgentTask task, long expectedVersion) {
        return tasks.compute(task.taskId(), (taskId, current) -> {
            if (current == null) {
                throw new IllegalArgumentException("Agent 任务不存在: " + taskId);
            }
            if (current.version() != expectedVersion || task.version() != expectedVersion + 1) {
                throw new IllegalStateException("Agent 任务版本冲突: " + taskId);
            }
            return task;
        });
    }

    /**
     * 从内存中读取任务当前快照。
     *
     * @param taskId 任务标识
     * @return 任务存在时返回快照
     */
    @Override
    public Optional<AgentTask> findById(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * 对内存任务执行确定性分页，供单元测试和开发期替代实现使用。
     *
     * @param page 从零开始的页码
     * @param size 每页数量
     * @return 任务分页
     */
    @Override
    public AgentTaskPage list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("分页参数不合法");
        }
        List<AgentTask> sorted = tasks.values().stream()
                .sorted(Comparator.comparing(AgentTask::updatedAt).reversed().thenComparing(AgentTask::taskId))
                .toList();
        int from = Math.min(page * size, sorted.size());
        int to = Math.min(from + size, sorted.size());
        return new AgentTaskPage(sorted.subList(from, to), page, size, sorted.size());
    }

    /** 内存实现没有持久索引需要恢复。 */
    @Override
    public void recoverIndex() {
        // 内存任务映射本身就是索引。
    }
}
