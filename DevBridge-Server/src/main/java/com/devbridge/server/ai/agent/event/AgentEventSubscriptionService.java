package com.devbridge.server.ai.agent.event;

import static com.devbridge.server.config.ToolExecutorConfiguration.AGENT_EVENT_EXECUTOR;

import com.devbridge.server.ai.agent.api.AgentEventResponse;
import com.devbridge.server.config.DevBridgeExecutorProperties;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent Event SSE 订阅服务，使用订阅级有界队列隔离慢客户端。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AgentEventSubscriptionService implements AgentEventBroadcaster {

    private static final long STREAM_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final int REPLAY_BATCH_SIZE = 1000;
    private static final int DRAIN_BATCH_SIZE = 64;

    private final AgentEventStore eventStore;
    private final Executor eventExecutor;
    private final int subscriberQueueCapacity;
    private final Supplier<SseEmitter> emitterFactory;
    private final Map<String, CopyOnWriteArrayList<EventSubscriber>> subscribers = new ConcurrentHashMap<>();
    private final AtomicLong sentEvents = new AtomicLong();
    private final AtomicLong slowSubscriptionsClosed = new AtomicLong();

    /**
     * 注入事件 Store、受管发送执行器和队列配置。
     *
     * @param eventStore 事件 Store
     * @param eventExecutor Agent Event 执行器
     * @param properties 执行器配置
     */
    @Autowired
    public AgentEventSubscriptionService(
            AgentEventStore eventStore,
            @Qualifier(AGENT_EVENT_EXECUTOR) Executor eventExecutor,
            DevBridgeExecutorProperties properties) {
        this(
                eventStore,
                eventExecutor,
                properties.getEventSubscriberQueueCapacity(),
                () -> new SseEmitter(STREAM_TIMEOUT_MILLIS));
    }

    /**
     * 创建可注入 Emitter 的测试实例。
     *
     * @param eventStore 事件 Store
     * @param eventExecutor Agent Event 执行器
     * @param subscriberQueueCapacity 单订阅队列容量
     * @param emitterFactory Emitter 工厂
     */
    AgentEventSubscriptionService(
            AgentEventStore eventStore,
            Executor eventExecutor,
            int subscriberQueueCapacity,
            Supplier<SseEmitter> emitterFactory) {
        if (subscriberQueueCapacity <= 0) {
            throw new IllegalArgumentException("subscriberQueueCapacity must be greater than zero");
        }
        this.eventStore = eventStore;
        this.eventExecutor = eventExecutor;
        this.subscriberQueueCapacity = subscriberQueueCapacity;
        this.emitterFactory = emitterFactory;
    }

    /**
     * 建立任务 SSE 订阅，历史回放和实时发送均在独立事件线程执行。
     *
     * @param taskId 任务标识
     * @param afterSequence 最后已处理序号
     * @return SSE Emitter
     */
    public SseEmitter subscribe(String taskId, long afterSequence) {
        SseEmitter emitter = emitterFactory.get();
        EventSubscriber subscriber = new EventSubscriber(taskId, emitter, afterSequence);
        subscribers.computeIfAbsent(taskId, key -> new CopyOnWriteArrayList<>()).add(subscriber);
        emitter.onCompletion(() -> remove(subscriber, false, false));
        emitter.onTimeout(() -> remove(subscriber, false, false));
        emitter.onError(error -> remove(subscriber, false, false));
        if (!subscriber.startReplay()) {
            remove(subscriber, true, true);
        }
        return emitter;
    }

    /**
     * 非阻塞广播已持久化事件；慢订阅只关闭自身连接。
     *
     * @param event 已持久化事件
     */
    @Override
    public void broadcast(AgentEvent event) {
        List<EventSubscriber> current = subscribers.getOrDefault(event.taskId(), new CopyOnWriteArrayList<>());
        for (EventSubscriber subscriber : current) {
            if (!subscriber.offer(event)) {
                remove(subscriber, true, true);
            }
        }
    }

    /**
     * 主动关闭一个任务的全部订阅，只影响连接，不取消持久任务。
     *
     * @param taskId 任务标识
     */
    public void closeTask(String taskId) {
        List<EventSubscriber> current = subscribers.remove(taskId);
        if (current != null) {
            current.forEach(subscriber -> closeSubscriber(subscriber, true));
        }
    }

    /**
     * 获取当前背压指标快照。
     *
     * @return 背压指标
     */
    public AgentEventBackpressureSnapshot metrics() {
        int active = subscribers.values().stream().mapToInt(List::size).sum();
        int queued = subscribers.values().stream()
                .flatMap(List::stream)
                .mapToInt(EventSubscriber::queueSize)
                .sum();
        return new AgentEventBackpressureSnapshot(
                active,
                queued,
                sentEvents.get(),
                slowSubscriptionsClosed.get());
    }

    /**
     * 移除订阅并按需完成连接和记录慢客户端指标。
     *
     * @param subscriber 订阅者
     * @param completeEmitter 是否主动完成连接
     * @param slow 是否因背压关闭
     */
    private void remove(EventSubscriber subscriber, boolean completeEmitter, boolean slow) {
        if (!subscriber.close()) {
            return;
        }
        CopyOnWriteArrayList<EventSubscriber> current = subscribers.get(subscriber.taskId());
        if (current != null) {
            current.remove(subscriber);
            if (current.isEmpty()) {
                subscribers.remove(subscriber.taskId(), current);
            }
        }
        if (slow) {
            slowSubscriptionsClosed.incrementAndGet();
        }
        if (completeEmitter) {
            subscriber.completeEmitter();
        }
    }

    /**
     * 关闭已从任务映射移除的订阅。
     *
     * @param subscriber 订阅者
     * @param completeEmitter 是否完成连接
     */
    private void closeSubscriber(EventSubscriber subscriber, boolean completeEmitter) {
        if (subscriber.close() && completeEmitter) {
            subscriber.completeEmitter();
        }
    }

    /**
     * 单个 SSE 订阅者，持有有界实时事件队列和唯一发送任务。
     *
     * <p>by AI.Coding</p>
     */
    private final class EventSubscriber {

        private final String taskId;
        private final SseEmitter emitter;
        private final ArrayDeque<AgentEvent> pending = new ArrayDeque<>();
        private volatile long lastSentSequence;
        private boolean replaying = true;
        private boolean draining;
        private boolean closed;

        /**
         * 创建订阅者。
         *
         * @param taskId 任务标识
         * @param emitter SSE Emitter
         * @param lastSentSequence 最后已处理序号
         */
        private EventSubscriber(String taskId, SseEmitter emitter, long lastSentSequence) {
            this.taskId = taskId;
            this.emitter = emitter;
            this.lastSentSequence = lastSentSequence;
        }

        /**
         * 异步启动历史事件回放。
         *
         * @return 成功提交返回 true
         */
        private boolean startReplay() {
            return execute(this::replay);
        }

        /**
         * 向订阅级有界队列加入实时事件。
         *
         * @param event Agent Event
         * @return 接收成功返回 true，队列满或已关闭返回 false
         */
        private boolean offer(AgentEvent event) {
            boolean schedule = false;
            synchronized (this) {
                if (closed) {
                    return false;
                }
                if (event.eventSequence() <= lastSentSequence) {
                    return true;
                }
                if (pending.size() >= subscriberQueueCapacity) {
                    return false;
                }
                pending.addLast(event);
                if (!replaying && !draining) {
                    draining = true;
                    schedule = true;
                }
            }
            return !schedule || execute(this::drainBatch);
        }

        /**
         * 分批补发游标后的持久事件，实时事件在回放期间进入有界队列。
         */
        private void replay() {
            try {
                while (!closed) {
                    List<AgentEvent> events = eventStore.readAfter(taskId, lastSentSequence, REPLAY_BATCH_SIZE);
                    for (AgentEvent event : events) {
                        if (!send(event)) {
                            remove(this, true, false);
                            return;
                        }
                    }
                    if (events.size() < REPLAY_BATCH_SIZE) {
                        finishReplay();
                        return;
                    }
                }
            } catch (RuntimeException ex) {
                remove(this, true, false);
            }
        }

        /**
         * 完成回放并在当前事件线程继续处理已排队实时事件。
         */
        private void finishReplay() {
            boolean drain;
            synchronized (this) {
                replaying = false;
                drain = !pending.isEmpty();
                draining = drain;
            }
            if (drain) {
                drainBatch();
            }
        }

        /**
         * 每次最多发送固定数量事件，避免单个活跃订阅长期独占线程。
         */
        private void drainBatch() {
            int sent = 0;
            while (sent < DRAIN_BATCH_SIZE) {
                AgentEvent event = poll();
                if (event == null) {
                    return;
                }
                if (!send(event)) {
                    remove(this, true, false);
                    return;
                }
                sent++;
            }
            synchronized (this) {
                if (pending.isEmpty()) {
                    draining = false;
                    return;
                }
            }
            if (!execute(this::drainBatch)) {
                remove(this, true, true);
            }
        }

        /**
         * 从队列获取下一事件；队列为空时结束当前发送任务。
         *
         * @return 下一事件或 null
         */
        private AgentEvent poll() {
            synchronized (this) {
                AgentEvent event = pending.pollFirst();
                if (event == null) {
                    draining = false;
                }
                return event;
            }
        }

        /**
         * 发送一个事件并更新已确认游标。
         *
         * @param event Agent Event
         * @return 发送成功返回 true
         */
        private boolean send(AgentEvent event) {
            if (event.eventSequence() <= lastSentSequence) {
                return true;
            }
            try {
                emitter.send(SseEmitter.event()
                        .id(event.eventId())
                        .name(event.eventType().name())
                        .data(AgentEventResponse.from(event), MediaType.APPLICATION_JSON));
                lastSentSequence = event.eventSequence();
                sentEvents.incrementAndGet();
                return true;
            } catch (IOException | IllegalStateException ex) {
                return false;
            }
        }

        /**
         * 向受管事件执行器提交发送任务。
         *
         * @param action 发送任务
         * @return 提交成功返回 true
         */
        private boolean execute(Runnable action) {
            try {
                eventExecutor.execute(action);
                return true;
            } catch (RejectedExecutionException ex) {
                return false;
            }
        }

        /**
         * 标记订阅关闭并释放队列。
         *
         * @return 首次关闭返回 true
         */
        private synchronized boolean close() {
            if (closed) {
                return false;
            }
            closed = true;
            pending.clear();
            return true;
        }

        /**
         * 完成 SSE 连接。
         */
        private void completeEmitter() {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // 客户端已经断开时无需再次写入响应。
            }
        }

        /**
         * 获取当前排队事件数。
         *
         * @return 排队事件数
         */
        private synchronized int queueSize() {
            return pending.size();
        }

        /**
         * 获取任务标识。
         *
         * @return 任务标识
         */
        private String taskId() {
            return taskId;
        }
    }
}
