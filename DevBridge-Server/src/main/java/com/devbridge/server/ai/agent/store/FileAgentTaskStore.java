package com.devbridge.server.ai.agent.store;

import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.config.DevBridgeProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 基于本地文件的 Agent Task Store，使用独立任务目录和可重建索引。
 *
 * <p>by AI.Coding</p>
 */
@Repository
public class FileAgentTaskStore implements AgentTaskStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileAgentTaskStore.class);
    private static final Pattern SAFE_TASK_ID = Pattern.compile("[A-Za-z0-9-]{1,128}");
    private static final int LOCK_STRIPES = 64;

    private final Path root;
    private final Path tasksRoot;
    private final Path indexFile;
    private final Path quarantineRoot;
    private final AgentTaskFileCodec codec;
    private final Map<String, AgentTaskIndexRecord> index = new ConcurrentHashMap<>();
    private final Object[] locks = createLocks();

    /**
     * 初始化本地文件 Store 并重建可查询索引。
     *
     * @param properties 应用配置
     * @param codec 文件编解码器
     */
    public FileAgentTaskStore(DevBridgeProperties properties, AgentTaskFileCodec codec) {
        this.root = Path.of(properties.getAiAgentDataRoot()).toAbsolutePath().normalize();
        this.tasksRoot = root.resolve("tasks");
        this.indexFile = root.resolve("indexes").resolve("tasks.ndjson");
        this.quarantineRoot = root.resolve("quarantine");
        this.codec = codec;
        initialize();
    }

    /**
     * 原子保存新任务并追加历史与索引记录。
     *
     * @param task 新任务快照
     * @return 已保存任务
     */
    @Override
    public AgentTask save(AgentTask task) {
        validateTaskId(task.taskId());
        synchronized (lockFor(task.taskId())) {
            Path snapshot = taskFile(task.taskId());
            if (Files.exists(snapshot)) {
                throw new IllegalStateException("Agent 任务已经存在: " + task.taskId());
            }
            persist(snapshot, task);
            return task;
        }
    }

    /**
     * 使用磁盘当前版本进行乐观更新，防止跨请求覆盖。
     *
     * @param task 新任务快照
     * @param expectedVersion 预期旧版本
     * @return 已更新任务
     */
    @Override
    public AgentTask update(AgentTask task, long expectedVersion) {
        validateTaskId(task.taskId());
        synchronized (lockFor(task.taskId())) {
            Path snapshot = taskFile(task.taskId());
            if (!Files.exists(snapshot)) {
                throw new IllegalArgumentException("Agent 任务不存在: " + task.taskId());
            }
            AgentTask current = codec.readTask(snapshot);
            if (current.version() != expectedVersion || task.version() != expectedVersion + 1) {
                throw new IllegalStateException("Agent 任务版本冲突: " + task.taskId());
            }
            persist(snapshot, task);
            return task;
        }
    }

    /**
     * 读取任务当前完整快照；损坏任务会被隔离并从索引移除。
     *
     * @param taskId 任务标识
     * @return 任务存在且完整时返回快照
     */
    @Override
    public Optional<AgentTask> findById(String taskId) {
        validateTaskId(taskId);
        Path snapshot = taskFile(taskId);
        if (!Files.exists(snapshot)) {
            return Optional.empty();
        }
        try {
            return Optional.of(codec.readTask(snapshot));
        } catch (AgentTaskStoreException ex) {
            quarantine(taskId, ex);
            return Optional.empty();
        }
    }

    /**
     * 通过内存元数据索引分页读取任务，仅加载当前页快照。
     *
     * @param page 从零开始的页码
     * @param size 每页数量
     * @return 任务分页
     */
    @Override
    public AgentTaskPage list(int page, int size) {
        validatePage(page, size);
        List<AgentTaskIndexRecord> sorted = index.values().stream()
                .sorted(Comparator.comparing(AgentTaskIndexRecord::updatedAt).reversed()
                        .thenComparing(AgentTaskIndexRecord::taskId))
                .toList();
        int from = Math.min(page * size, sorted.size());
        int to = Math.min(from + size, sorted.size());
        List<AgentTask> items = new ArrayList<>(to - from);
        for (AgentTaskIndexRecord record : sorted.subList(from, to)) {
            findById(record.taskId()).ifPresent(items::add);
        }
        return new AgentTaskPage(items, page, size, index.size());
    }

    /** 从恢复后的完整任务快照重建内存和磁盘索引。 */
    @Override
    public void recoverIndex() {
        try {
            rebuildIndex();
        } catch (IOException ex) {
            throw new AgentTaskStoreException("Agent Task 索引恢复失败", ex);
        }
    }

    /**
     * 初始化目录并从完整任务快照重建索引。
     */
    private void initialize() {
        try {
            Files.createDirectories(tasksRoot);
            Files.createDirectories(indexFile.getParent());
            Files.createDirectories(quarantineRoot);
            rebuildIndex();
        } catch (IOException ex) {
            throw new AgentTaskStoreException("Agent Task Store 初始化失败", ex);
        }
    }

    /**
     * 写入当前快照后追加历史和索引；当前快照始终是恢复真相源。
     *
     * @param snapshot 快照文件
     * @param task 任务快照
     */
    private void persist(Path snapshot, AgentTask task) {
        codec.writeTaskAtomic(snapshot, task);
        codec.appendTask(snapshot.resolveSibling("history.ndjson"), task);
        AgentTaskIndexRecord record = indexRecord(task);
        codec.appendIndex(indexFile, record);
        index.put(task.taskId(), record);
    }

    /**
     * 扫描任务快照重建索引，并原子压缩索引文件。
     *
     * @throws IOException 文件扫描失败时抛出
     */
    private void rebuildIndex() throws IOException {
        index.clear();
        try (Stream<Path> paths = Files.walk(tasksRoot)) {
            paths.filter(path -> path.getFileName().toString().equals("task.json"))
                    .forEach(this::loadIndexEntry);
        }
        codec.writeIndexAtomic(indexFile, index.values());
    }

    /**
     * 加载一个任务索引记录，损坏任务只隔离自身。
     *
     * @param snapshot 任务快照文件
     */
    private void loadIndexEntry(Path snapshot) {
        String taskId = snapshot.getParent().getFileName().toString();
        try {
            AgentTask task = codec.readTask(snapshot);
            index.put(task.taskId(), indexRecord(task));
        } catch (RuntimeException ex) {
            quarantine(taskId, ex);
        }
    }

    /**
     * 记录损坏任务并从可运行索引中隔离，保留原文件用于恢复。
     *
     * @param taskId 任务标识
     * @param cause 损坏原因
     */
    private void quarantine(String taskId, RuntimeException cause) {
        index.remove(taskId);
        String safeMessage = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        Path marker = quarantineRoot.resolve(taskId + "-" + System.nanoTime() + ".error");
        try {
            Files.writeString(marker, safeMessage, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            LOGGER.warn("Agent Task 损坏隔离记录写入失败, taskId={}", taskId);
        }
        LOGGER.warn("Agent Task 已隔离, taskId={}, reason={}", taskId, safeMessage);
    }

    /**
     * 生成任务分片目录，防止单目录文件数量过多。
     *
     * @param taskId 任务标识
     * @return 任务快照文件
     */
    private Path taskFile(String taskId) {
        String compact = taskId.replace("-", "");
        String first = compact.substring(0, Math.min(2, compact.length()));
        String second = compact.length() > 2 ? compact.substring(2, Math.min(4, compact.length())) : "00";
        return tasksRoot.resolve(first).resolve(second).resolve(taskId).resolve("task.json");
    }

    /**
     * 构造可重建任务索引记录。
     *
     * @param task 任务快照
     * @return 索引记录
     */
    private AgentTaskIndexRecord indexRecord(AgentTask task) {
        return new AgentTaskIndexRecord(task.taskId(), task.state(), task.version(), task.updatedAt());
    }

    /**
     * 校验任务标识，阻断路径穿越和异常文件名。
     *
     * @param taskId 任务标识
     */
    private void validateTaskId(String taskId) {
        if (taskId == null || !SAFE_TASK_ID.matcher(taskId).matches()) {
            throw new IllegalArgumentException("Agent 任务标识不合法");
        }
    }

    /**
     * 校验分页范围，防止一次加载过多任务。
     *
     * @param page 从零开始的页码
     * @param size 每页数量
     */
    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("分页参数不合法");
        }
    }

    /**
     * 获取稳定锁分片，避免为每个历史任务永久保存独立锁对象。
     *
     * @param taskId 任务标识
     * @return 锁对象
     */
    private Object lockFor(String taskId) {
        return locks[Math.floorMod(taskId.hashCode(), locks.length)];
    }

    /**
     * 初始化固定数量锁分片，保持长期运行内存有界。
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
