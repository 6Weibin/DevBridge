package com.devbridge.server.ai.agent.event;

import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.ai.storage.AiDataMaintenanceLock;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Agent Event 本地文件 Store，按任务保存严格有序的 NDJSON 事件流。
 *
 * <p>by AI.Coding</p>
 */
@Repository
public class FileAgentEventStore implements AgentEventStore {

    private static final Pattern SAFE_TASK_ID = Pattern.compile("[A-Za-z0-9-]{1,128}");
    private static final int LOCK_STRIPES = 64;
    private static final long EVENTS_PER_SEGMENT = 10_000L;

    private final Path root;
    private final AgentEventFileCodec codec;
    private final AiDataMaintenanceLock maintenanceLock;
    private final Map<String, Long> highWaterMarks = new ConcurrentHashMap<>();
    private final Object[] locks = createLocks();

    /**
     * 初始化事件 Store。
     *
     * @param properties 应用配置
     * @param codec 事件编解码器
     */
    @Autowired
    public FileAgentEventStore(
            DevBridgeProperties properties,
            AgentEventFileCodec codec,
            AiDataMaintenanceLock maintenanceLock) {
        this.root = Path.of(properties.getAiAgentDataRoot()).toAbsolutePath().normalize();
        this.codec = codec;
        this.maintenanceLock = maintenanceLock;
    }

    /** 创建兼容测试的事件 Store。 */
    public FileAgentEventStore(DevBridgeProperties properties, AgentEventFileCodec codec) {
        this(properties, codec, new AiDataMaintenanceLock());
    }

    /**
     * 校验序号连续后追加事件，持久化失败时不更新高水位。
     *
     * @param event Agent Event
     * @return 已持久化事件
     */
    @Override
    public AgentEvent append(AgentEvent event) {
        return maintenanceLock.read(() -> appendUnlocked(event));
    }

    /** 在维护读锁内追加事件。 */
    private AgentEvent appendUnlocked(AgentEvent event) {
        validateTaskId(event.taskId());
        synchronized (lockFor(event.taskId())) {
            long current = loadHighWaterMark(event.taskId());
            if (event.eventSequence() != current + 1) {
                throw new IllegalStateException("Agent Event 序号不连续: expected="
                        + (current + 1) + ", actual=" + event.eventSequence());
            }
            codec.append(eventFile(event.taskId(), event.eventSequence()), event);
            highWaterMarks.put(event.taskId(), event.eventSequence());
            return event;
        }
    }

    /**
     * 获取磁盘事件高水位，首次访问时从文件恢复。
     *
     * @param taskId 任务标识
     * @return 最后事件序号
     */
    @Override
    public long lastSequence(String taskId) {
        return maintenanceLock.read(() -> lastSequenceUnlocked(taskId));
    }

    /** 在维护读锁内读取事件高水位。 */
    private long lastSequenceUnlocked(String taskId) {
        validateTaskId(taskId);
        synchronized (lockFor(taskId)) {
            return loadHighWaterMark(taskId);
        }
    }

    /**
     * 从游标之后读取严格有序事件，并限制单次返回数量。
     *
     * @param taskId 任务标识
     * @param afterSequence 已处理序号
     * @param limit 最大返回数量
     * @return 有序事件
     */
    @Override
    public List<AgentEvent> readAfter(String taskId, long afterSequence, int limit) {
        return maintenanceLock.read(() -> readAfterUnlocked(taskId, afterSequence, limit));
    }

    /** 在维护读锁内按游标读取事件。 */
    private List<AgentEvent> readAfterUnlocked(String taskId, long afterSequence, int limit) {
        validateTaskId(taskId);
        validateCursor(afterSequence, limit);
        List<AgentEvent> result = new ArrayList<>(Math.min(limit, 128));
        long segment = segmentNumber(Math.max(1L, afterSequence + 1L));
        while (result.size() < limit) {
            Path file = eventFileBySegment(taskId, segment);
            if (!Files.exists(file)) break;
            readSegment(taskId, file, afterSequence, limit, result);
            segment++;
        }
        return List.copyOf(result);
    }

    /** 读取单个事件分段并校验任务身份。 */
    private void readSegment(
            String taskId,
            Path file,
            long afterSequence,
            int limit,
            List<AgentEvent> result) {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null && result.size() < limit) {
                if (line.isBlank()) continue;
                AgentEvent event = codec.decode(line);
                if (!taskId.equals(event.taskId())) {
                    throw new IllegalStateException("Agent Event 任务标识不一致");
                }
                if (event.eventSequence() > afterSequence) result.add(event);
            }
        } catch (IOException | RuntimeException ex) {
            throw new AgentEventStoreException("Agent Event 游标读取失败", ex);
        }
    }

    /**
     * 从事件文件恢复高水位并校验序号连续性。
     *
     * @param taskId 任务标识
     * @return 事件高水位
     */
    private long loadHighWaterMark(String taskId) {
        Long cached = highWaterMarks.get(taskId);
        if (cached != null) {
            return cached;
        }
        long last = scanLastSequence(taskId);
        highWaterMarks.put(taskId, last);
        return last;
    }

    /**
     * 流式扫描事件文件高水位，避免重启时把完整事件历史加载到内存。
     *
     * @param taskId 任务标识
     * @return 最后连续事件序号
     */
    private long scanLastSequence(String taskId) {
        long last = 0L;
        long segment = 1L;
        while (true) {
            Path file = eventFileBySegment(taskId, segment);
            if (!Files.exists(file)) break;
            try (BufferedReader reader = Files.newBufferedReader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    AgentEvent event = codec.decode(line);
                    if (!taskId.equals(event.taskId()) || event.eventSequence() != last + 1) {
                        throw new IllegalStateException("Agent Event 文件存在任务标识错误或序号缺口");
                    }
                    last = event.eventSequence();
                }
            } catch (IOException | RuntimeException ex) {
                throw new AgentEventStoreException("Agent Event 高水位恢复失败", ex);
            }
            segment++;
        }
        return last;
    }

    /**
     * 获取任务事件文件。
     *
     * @param taskId 任务标识
     * @return 事件文件
     */
    private Path eventFile(String taskId, long sequence) {
        return eventFileBySegment(taskId, segmentNumber(sequence));
    }

    /** 根据任务和分段序号生成稳定事件文件路径。 */
    private Path eventFileBySegment(String taskId, long segment) {
        String compact = taskId.replace("-", "");
        String first = compact.substring(0, Math.min(2, compact.length()));
        String second = compact.length() > 2 ? compact.substring(2, Math.min(4, compact.length())) : "00";
        return root.resolve("tasks").resolve(first).resolve(second).resolve(taskId)
                .resolve("events").resolve("events-%06d.ndjson".formatted(segment));
    }

    /** 把事件序号映射到固定大小分段。 */
    private long segmentNumber(long sequence) {
        return Math.max(1L, (Math.max(1L, sequence) - 1L) / EVENTS_PER_SEGMENT + 1L);
    }

    /**
     * 校验任务标识，阻断路径穿越。
     *
     * @param taskId 任务标识
     */
    private void validateTaskId(String taskId) {
        if (taskId == null || !SAFE_TASK_ID.matcher(taskId).matches()) {
            throw new IllegalArgumentException("Agent 任务标识不合法");
        }
    }

    /**
     * 校验事件游标和单次读取上限。
     *
     * @param afterSequence 事件游标
     * @param limit 最大返回数量
     */
    private void validateCursor(long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Agent Event 游标参数不合法");
        }
    }

    /**
     * 获取固定锁分片。
     *
     * @param taskId 任务标识
     * @return 锁对象
     */
    private Object lockFor(String taskId) {
        return locks[Math.floorMod(taskId.hashCode(), locks.length)];
    }

    /**
     * 初始化固定数量锁对象。
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
