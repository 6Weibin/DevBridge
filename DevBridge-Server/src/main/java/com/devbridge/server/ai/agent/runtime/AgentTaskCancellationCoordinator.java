package com.devbridge.server.ai.agent.runtime;

import static com.devbridge.server.config.ToolExecutorConfiguration.AGENT_CANCELLATION_EXECUTOR;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Agent 任务取消协调器，按任务维护模型、工具和进程取消作用域。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AgentTaskCancellationCoordinator {

    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);

    private final ConcurrentMap<String, AgentCancellationScope> scopes = new ConcurrentHashMap<>();
    private final ExecutorService cancellationExecutor;

    /** 注入独立取消执行器，确保阻塞清理不会拖住请求线程。 */
    @Autowired
    public AgentTaskCancellationCoordinator(
            @Qualifier(AGENT_CANCELLATION_EXECUTOR) ExecutorService cancellationExecutor) {
        this.cancellationExecutor = cancellationExecutor;
    }

    /** 创建兼容现有单元测试的协调器。 */
    public AgentTaskCancellationCoordinator() {
        this(ForkJoinPool.commonPool());
    }

    /**
     * 为新任务建立或返回已有取消作用域。
     *
     * @param taskId 任务标识
     * @return 取消作用域
     */
    public AgentCancellationScope open(String taskId) {
        String normalized = normalizeTaskId(taskId);
        return scopes.computeIfAbsent(normalized,
                value -> new AgentCancellationScope(value, cancellationExecutor, CLEANUP_TIMEOUT));
    }

    /**
     * 查询活动任务取消作用域，供 Agent 执行器注册底层句柄。
     *
     * @param taskId 任务标识
     * @return 活动作用域
     */
    public Optional<AgentCancellationScope> find(String taskId) {
        return Optional.ofNullable(scopes.get(normalizeTaskId(taskId)));
    }

    /**
     * 向任务传播取消信号。
     *
     * @param taskId 任务标识
     * @param reason 取消原因
     * @return 取消传播结果
     */
    public AgentCancellationResult cancel(String taskId, String reason) {
        AgentCancellationScope scope = scopes.get(normalizeTaskId(taskId));
        return scope == null ? AgentCancellationResult.notActive() : scope.cancel(reason);
    }

    /**
     * 任务终止后移除作用域，保持长期运行内存有界。
     *
     * @param taskId 任务标识
     */
    public void close(String taskId) {
        AgentCancellationScope scope = scopes.remove(normalizeTaskId(taskId));
        if (scope != null) {
            scope.close();
        }
    }

    /**
     * 规范化并校验任务标识。
     *
     * @param taskId 原始任务标识
     * @return 规范任务标识
     */
    private String normalizeTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("任务标识不能为空");
        }
        return taskId.trim();
    }
}
