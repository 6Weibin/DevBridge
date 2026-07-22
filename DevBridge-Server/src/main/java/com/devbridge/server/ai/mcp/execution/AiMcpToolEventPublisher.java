package com.devbridge.server.ai.mcp.execution;

import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * AI MCP 工具事件发布器，把 Spring AI ToolCallback 的结果透传给当前对话 SSE。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AiMcpToolEventPublisher {

    private final Map<String, Consumer<AdbMcpToolResult>> subscribers = new ConcurrentHashMap<>();

    /**
     * 注册对话工具事件订阅者。
     *
     * @param subscriptionId 任务调用级订阅标识
     * @param consumer 事件消费者
     */
    public void register(String subscriptionId, Consumer<AdbMcpToolResult> consumer) {
        if (subscribers.putIfAbsent(subscriptionId, consumer) != null) {
            throw new IllegalStateException("工具事件订阅已经存在: " + subscriptionId);
        }
    }

    /**
     * 移除对话工具事件订阅者。
     *
     * @param subscriptionId 任务调用级订阅标识
     */
    public void unregister(String subscriptionId) {
        subscribers.remove(subscriptionId);
    }

    /**
     * 发布工具结果；没有订阅者时直接忽略，避免影响非流式调用。
     *
     * @param subscriptionId 任务调用级订阅标识
     * @param result 工具结果
     */
    public boolean publish(String subscriptionId, AdbMcpToolResult result) {
        Consumer<AdbMcpToolResult> consumer = subscribers.get(subscriptionId);
        if (consumer != null) {
            consumer.accept(result);
            return true;
        }
        return false;
    }
}
