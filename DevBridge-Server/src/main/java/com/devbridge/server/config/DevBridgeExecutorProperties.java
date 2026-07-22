package com.devbridge.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具执行器配置，集中管理并发、排队和线程回收边界。
 *
 * <p>by AI.Coding</p>
 */
@ConfigurationProperties(prefix = "devbridge.executors")
public class DevBridgeExecutorProperties {

    private int toolCorePoolSize = 4;
    private int toolMaxPoolSize = 8;
    private int toolQueueCapacity = 64;
    private int commandIoCorePoolSize = 8;
    private int commandIoMaxPoolSize = 16;
    private int commandIoQueueCapacity = 128;
    private int agentEventCorePoolSize = 2;
    private int agentEventMaxPoolSize = 4;
    private int agentEventQueueCapacity = 128;
    private int eventSubscriberQueueCapacity = 512;
    private int timeoutPoolSize = 2;
    private Duration keepAlive = Duration.ofSeconds(30);

    /**
     * 获取工具调度核心线程数。
     *
     * @return 核心线程数
     */
    public int getToolCorePoolSize() {
        return toolCorePoolSize;
    }

    /**
     * 设置工具调度核心线程数。
     *
     * @param toolCorePoolSize 核心线程数
     */
    public void setToolCorePoolSize(int toolCorePoolSize) {
        this.toolCorePoolSize = toolCorePoolSize;
    }

    /**
     * 获取工具调度最大线程数。
     *
     * @return 最大线程数
     */
    public int getToolMaxPoolSize() {
        return toolMaxPoolSize;
    }

    /**
     * 设置工具调度最大线程数。
     *
     * @param toolMaxPoolSize 最大线程数
     */
    public void setToolMaxPoolSize(int toolMaxPoolSize) {
        this.toolMaxPoolSize = toolMaxPoolSize;
    }

    /**
     * 获取工具调度队列容量。
     *
     * @return 队列容量
     */
    public int getToolQueueCapacity() {
        return toolQueueCapacity;
    }

    /**
     * 设置工具调度队列容量。
     *
     * @param toolQueueCapacity 队列容量
     */
    public void setToolQueueCapacity(int toolQueueCapacity) {
        this.toolQueueCapacity = toolQueueCapacity;
    }

    /**
     * 获取命令 IO 核心线程数。
     *
     * @return 核心线程数
     */
    public int getCommandIoCorePoolSize() {
        return commandIoCorePoolSize;
    }

    /**
     * 设置命令 IO 核心线程数。
     *
     * @param commandIoCorePoolSize 核心线程数
     */
    public void setCommandIoCorePoolSize(int commandIoCorePoolSize) {
        this.commandIoCorePoolSize = commandIoCorePoolSize;
    }

    /**
     * 获取命令 IO 最大线程数。
     *
     * @return 最大线程数
     */
    public int getCommandIoMaxPoolSize() {
        return commandIoMaxPoolSize;
    }

    /**
     * 设置命令 IO 最大线程数。
     *
     * @param commandIoMaxPoolSize 最大线程数
     */
    public void setCommandIoMaxPoolSize(int commandIoMaxPoolSize) {
        this.commandIoMaxPoolSize = commandIoMaxPoolSize;
    }

    /**
     * 获取命令 IO 队列容量。
     *
     * @return 队列容量
     */
    public int getCommandIoQueueCapacity() {
        return commandIoQueueCapacity;
    }

    /**
     * 设置命令 IO 队列容量。
     *
     * @param commandIoQueueCapacity 队列容量
     */
    public void setCommandIoQueueCapacity(int commandIoQueueCapacity) {
        this.commandIoQueueCapacity = commandIoQueueCapacity;
    }

    /**
     * 获取 Agent Event 核心发送线程数。
     *
     * @return 核心线程数
     */
    public int getAgentEventCorePoolSize() {
        return agentEventCorePoolSize;
    }

    /**
     * 设置 Agent Event 核心发送线程数。
     *
     * @param agentEventCorePoolSize 核心线程数
     */
    public void setAgentEventCorePoolSize(int agentEventCorePoolSize) {
        this.agentEventCorePoolSize = agentEventCorePoolSize;
    }

    /**
     * 获取 Agent Event 最大发送线程数。
     *
     * @return 最大发送线程数
     */
    public int getAgentEventMaxPoolSize() {
        return agentEventMaxPoolSize;
    }

    /**
     * 设置 Agent Event 最大发送线程数。
     *
     * @param agentEventMaxPoolSize 最大发送线程数
     */
    public void setAgentEventMaxPoolSize(int agentEventMaxPoolSize) {
        this.agentEventMaxPoolSize = agentEventMaxPoolSize;
    }

    /**
     * 获取 Agent Event 执行器队列容量。
     *
     * @return 执行器队列容量
     */
    public int getAgentEventQueueCapacity() {
        return agentEventQueueCapacity;
    }

    /**
     * 设置 Agent Event 执行器队列容量。
     *
     * @param agentEventQueueCapacity 执行器队列容量
     */
    public void setAgentEventQueueCapacity(int agentEventQueueCapacity) {
        this.agentEventQueueCapacity = agentEventQueueCapacity;
    }

    /**
     * 获取单个 SSE 订阅者的实时事件队列容量。
     *
     * @return 订阅队列容量
     */
    public int getEventSubscriberQueueCapacity() {
        return eventSubscriberQueueCapacity;
    }

    /**
     * 设置单个 SSE 订阅者的实时事件队列容量。
     *
     * @param eventSubscriberQueueCapacity 订阅队列容量
     */
    public void setEventSubscriberQueueCapacity(int eventSubscriberQueueCapacity) {
        this.eventSubscriberQueueCapacity = eventSubscriberQueueCapacity;
    }

    /**
     * 获取超时调度线程数。
     *
     * @return 调度线程数
     */
    public int getTimeoutPoolSize() {
        return timeoutPoolSize;
    }

    /**
     * 设置超时调度线程数。
     *
     * @param timeoutPoolSize 调度线程数
     */
    public void setTimeoutPoolSize(int timeoutPoolSize) {
        this.timeoutPoolSize = timeoutPoolSize;
    }

    /**
     * 获取非核心工作线程空闲回收时间。
     *
     * @return 空闲回收时间
     */
    public Duration getKeepAlive() {
        return keepAlive;
    }

    /**
     * 设置非核心工作线程空闲回收时间。
     *
     * @param keepAlive 空闲回收时间
     */
    public void setKeepAlive(Duration keepAlive) {
        this.keepAlive = keepAlive;
    }
}
