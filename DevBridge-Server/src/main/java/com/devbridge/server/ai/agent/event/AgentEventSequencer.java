package com.devbridge.server.ai.agent.event;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Agent Event 序列器，在单任务临界区内分配序号并持久化事件。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AgentEventSequencer {

    private static final String SCHEMA_VERSION = "1.0.0";
    private static final int LOCK_STRIPES = 64;

    private final AgentEventStore eventStore;
    private final AgentEventBroadcaster broadcaster;
    private final Object[] locks = createLocks();

    /**
     * 注入事件 Store。
     *
     * @param eventStore 事件 Store
     * @param broadcaster 实时事件广播器
     */
    public AgentEventSequencer(AgentEventStore eventStore, AgentEventBroadcaster broadcaster) {
        this.eventStore = eventStore;
        this.broadcaster = broadcaster;
    }

    /**
     * 校验事件关联关系，分配下一序号并在返回前持久化。
     *
     * @param taskId 任务标识
     * @param request 事件请求
     * @return 已持久化事件
     */
    public AgentEvent publish(String taskId, AgentEventRequest request) {
        validateRequest(request);
        synchronized (lockFor(taskId)) {
            long nextSequence = eventStore.lastSequence(taskId) + 1;
            Instant recordedAt = Instant.now();
            AgentEvent event = new AgentEvent(
                    SCHEMA_VERSION,
                    new AgentEventIdentity(taskId, nextSequence, request.eventType(), request.scope()),
                    request.context(),
                    new AgentEventTiming(request.occurredAt(), recordedAt),
                    request.producer(),
                    request.payload());
            AgentEvent persisted = eventStore.append(event);
            broadcaster.broadcast(persisted);
            return persisted;
        }
    }

    /**
     * 查询任务当前最后事件序号，供 Checkpoint 与事件边界保持一致。
     *
     * @param taskId 任务标识
     * @return 最后事件序号，无事件时返回零
     */
    public long lastSequence(String taskId) {
        return eventStore.lastSequence(taskId);
    }

    /** 统计任务已持久化的指定事件类型，供跨流续跑恢复调用预算。 */
    public int count(String taskId, AgentEventType eventType) {
        if (eventStore == null) {
            return 0;
        }
        int count = 0;
        long cursor = 0L;
        while (true) {
            List<AgentEvent> events = eventStore.readAfter(taskId, cursor, 200);
            count += (int) events.stream().filter(event -> event.eventType() == eventType).count();
            if (events.size() < 200) {
                return count;
            }
            cursor = events.get(events.size() - 1).eventSequence();
        }
    }

    /**
     * 返回指定步骤最近完成的工具调用，供确认续跑精确重放。
     *
     * @param taskId 任务标识
     * @param stepId 步骤标识
     * @return 工具调用标识，无匹配时返回空字符串
     */
    public String latestCompletedToolCallId(String taskId, String stepId) {
        if (eventStore == null || !StringUtils.hasText(stepId)) {
            return "";
        }
        long last = eventStore.lastSequence(taskId);
        List<AgentEvent> events = eventStore.readAfter(taskId, Math.max(0L, last - 200L), 200);
        String latest = "";
        for (AgentEvent event : events) {
            if (event.eventType() == AgentEventType.TOOL_COMPLETED
                    && stepId.equals(event.context().stepId())
                    && StringUtils.hasText(event.context().toolCallId())) {
                latest = event.context().toolCallId();
            }
        }
        return latest;
    }

    /**
     * 校验事件公共字段和作用域必需标识。
     *
     * @param request 事件请求
     */
    private void validateRequest(AgentEventRequest request) {
        if (request == null || request.eventType() == null || request.scope() == null
                || request.context() == null || request.occurredAt() == null) {
            throw new IllegalArgumentException("Agent Event 请求字段不完整");
        }
        required(request.context().conversationId(), "conversationId");
        required(request.producer(), "producer");
        switch (request.scope()) {
            case TURN -> required(request.context().turnId(), "turnId");
            case STEP -> required(request.context().stepId(), "stepId");
            case MODEL_CALL -> validateModelContext(request.context());
            case TOOL_CALL -> validateToolContext(request.context());
            case CONFIRMATION -> validateConfirmationContext(request.context());
            case TASK, OUTPUT -> {
                // 任务和输出事件只要求公共会话关联；输出分块标识位于结构化载荷中。
            }
        }
    }

    /**
     * 校验模型事件关联标识。
     *
     * @param context 事件上下文
     */
    private void validateModelContext(AgentEventContext context) {
        required(context.stepId(), "stepId");
        required(context.modelCallId(), "modelCallId");
    }

    /**
     * 校验工具事件关联标识。
     *
     * @param context 事件上下文
     */
    private void validateToolContext(AgentEventContext context) {
        required(context.stepId(), "stepId");
        required(context.toolCallId(), "toolCallId");
    }

    /**
     * 校验确认事件关联标识。
     *
     * @param context 事件上下文
     */
    private void validateConfirmationContext(AgentEventContext context) {
        validateToolContext(context);
        required(context.confirmationId(), "confirmationId");
    }

    /**
     * 校验必填文本。
     *
     * @param value 文本值
     * @param fieldName 字段名
     */
    private void required(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Agent Event 缺少 " + fieldName);
        }
    }

    /**
     * 获取稳定任务锁分片。
     *
     * @param taskId 任务标识
     * @return 锁对象
     */
    private Object lockFor(String taskId) {
        return locks[Math.floorMod(taskId.hashCode(), locks.length)];
    }

    /**
     * 初始化固定数量锁分片。
     *
     * @return 锁分片
     */
    private static Object[] createLocks() {
        Object[] values = new Object[LOCK_STRIPES];
        for (int index = 0; index < values.length; index++) {
            values[index] = new Object();
        }
        return values;
    }
}
