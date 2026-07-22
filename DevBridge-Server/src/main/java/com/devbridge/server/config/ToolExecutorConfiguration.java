package com.devbridge.server.config;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

/**
 * 工具执行线程池配置，所有线程池由 Spring 负责创建和关闭。
 *
 * <p>by AI.Coding</p>
 */
@Configuration
public class ToolExecutorConfiguration {

    public static final String TOOL_EXECUTION_EXECUTOR = "toolExecutionExecutor";
    public static final String COMMAND_IO_EXECUTOR = "commandIoExecutor";
    public static final String COMMAND_TIMEOUT_EXECUTOR = "commandTimeoutExecutor";
    public static final String AGENT_EVENT_EXECUTOR = "agentEventExecutor";
    public static final String AGENT_CANCELLATION_EXECUTOR = "agentCancellationExecutor";

    /**
     * 创建工具编排执行器，饱和时明确拒绝而不是继续扩张线程或队列。
     *
     * @param properties 执行器配置
     * @return 工具执行器
     */
    @Bean(name = TOOL_EXECUTION_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService toolExecutionExecutor(DevBridgeExecutorProperties properties) {
        return boundedExecutor(
                "ai-tool-",
                properties.getToolCorePoolSize(),
                properties.getToolMaxPoolSize(),
                properties.getToolQueueCapacity(),
                properties.getKeepAlive());
    }

    /**
     * 创建命令 IO 执行器，独立于工具编排线程以避免进程管道读取死锁。
     *
     * @param properties 执行器配置
     * @return 命令 IO 执行器
     */
    @Bean(name = COMMAND_IO_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService commandIoExecutor(DevBridgeExecutorProperties properties) {
        return boundedExecutor(
                "command-io-",
                properties.getCommandIoCorePoolSize(),
                properties.getCommandIoMaxPoolSize(),
                properties.getCommandIoQueueCapacity(),
                properties.getKeepAlive());
    }

    /**
     * 创建 Agent Event 发送执行器，隔离慢客户端与工具执行线程。
     *
     * @param properties 执行器配置
     * @return Agent Event 执行器
     */
    @Bean(name = AGENT_EVENT_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService agentEventExecutor(DevBridgeExecutorProperties properties) {
        return boundedExecutor(
                "agent-event-",
                properties.getAgentEventCorePoolSize(),
                properties.getAgentEventMaxPoolSize(),
                properties.getAgentEventQueueCapacity(),
                properties.getKeepAlive());
    }

    /**
     * 创建独立取消执行器，避免阻塞型资源清理占用模型或工具执行线程。
     *
     * @return Agent 取消执行器
     */
    @Bean(name = AGENT_CANCELLATION_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService agentCancellationExecutor() {
        return boundedExecutor("agent-cancel-", 2, 4, 32, Duration.ofSeconds(30));
    }

    /**
     * 创建命令超时调度器，并在任务取消后立即移除对应延迟任务。
     *
     * @param properties 执行器配置
     * @return 超时调度器
     */
    @Bean(name = COMMAND_TIMEOUT_EXECUTOR, destroyMethod = "shutdown")
    public ScheduledExecutorService commandTimeoutExecutor(DevBridgeExecutorProperties properties) {
        int poolSize = positive(properties.getTimeoutPoolSize(), "timeoutPoolSize");
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                poolSize,
                new CustomizableThreadFactory("command-timeout-"),
                new ThreadPoolExecutor.AbortPolicy());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    /**
     * 创建使用有界队列和拒绝策略的线程池。
     *
     * @param threadPrefix 线程名前缀
     * @param corePoolSize 核心线程数
     * @param maxPoolSize 最大线程数
     * @param queueCapacity 队列容量
     * @param keepAlive 空闲回收时间
     * @return 有界线程池
     */
    private ExecutorService boundedExecutor(
            String threadPrefix,
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            Duration keepAlive) {
        int core = positive(corePoolSize, "corePoolSize");
        int max = positive(maxPoolSize, "maxPoolSize");
        int capacity = positive(queueCapacity, "queueCapacity");
        if (max < core) {
            throw new IllegalArgumentException("maxPoolSize must be greater than or equal to corePoolSize");
        }
        long keepAliveSeconds = Math.max(1, keepAlive == null ? 30 : keepAlive.toSeconds());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                core,
                max,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity),
                new CustomizableThreadFactory(threadPrefix),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * 校验线程池数值必须为正数。
     *
     * @param value 配置值
     * @param name 配置名
     * @return 原配置值
     */
    private int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }
}
