package com.devbridge.server.ai.agent.checkpoint;

import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.ai.storage.AiDataMaintenanceLock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 本地文件 Checkpoint Store，保留历史恢复点并原子更新当前指针。
 *
 * <p>by AI.Coding</p>
 */
@Repository
public class FileAgentCheckpointStore implements AgentCheckpointStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileAgentCheckpointStore.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,128}");
    private static final int LOCK_STRIPES = 64;

    private final Path root;
    private final Path quarantineRoot;
    private final AgentCheckpointFileCodec codec;
    private final AiDataMaintenanceLock maintenanceLock;
    private final Object[] locks = createLocks();

    /**
     * 初始化 Checkpoint Store 目录。
     *
     * @param properties 应用配置
     * @param codec Checkpoint 编解码器
     */
    @Autowired
    public FileAgentCheckpointStore(
            DevBridgeProperties properties,
            AgentCheckpointFileCodec codec,
            AiDataMaintenanceLock maintenanceLock) {
        this.root = Path.of(properties.getAiAgentDataRoot()).toAbsolutePath().normalize();
        this.quarantineRoot = root.resolve("quarantine");
        this.codec = codec;
        this.maintenanceLock = maintenanceLock;
        try {
            Files.createDirectories(quarantineRoot);
        } catch (IOException ex) {
            throw new AgentCheckpointStoreException("Checkpoint Store 初始化失败", ex);
        }
    }

    /** 创建兼容测试的 Checkpoint Store。 */
    public FileAgentCheckpointStore(DevBridgeProperties properties, AgentCheckpointFileCodec codec) {
        this(properties, codec, new AiDataMaintenanceLock());
    }

    /**
     * 原子保存 Checkpoint，再原子更新当前指针。
     *
     * @param checkpoint Checkpoint
     * @return 已保存 Checkpoint
     */
    @Override
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        return maintenanceLock.read(() -> saveUnlocked(checkpoint));
    }

    /** 在维护读锁内原子保存 Checkpoint。 */
    private AgentCheckpoint saveUnlocked(AgentCheckpoint checkpoint) {
        validateId(checkpoint.taskId(), "任务标识");
        validateId(checkpoint.checkpointId(), "Checkpoint 标识");
        synchronized (lockFor(checkpoint.taskId())) {
            Path directory = checkpointDirectory(checkpoint.taskId());
            Path target = directory.resolve(checkpoint.checkpointId() + ".json");
            if (Files.exists(target)) {
                throw new IllegalStateException("Checkpoint 已经存在: " + checkpoint.checkpointId());
            }
            codec.writeCheckpoint(target, checkpoint);
            codec.writePointer(directory.resolve("checkpoint-current.json"), new AgentCheckpointPointer(
                    checkpoint.checkpointId(), target.getFileName().toString(),
                    checkpoint.taskVersion(), checkpoint.createdAt()));
            return checkpoint;
        }
    }

    /**
     * 读取当前完整 Checkpoint；当前文件损坏时回退到上一个完整版本。
     *
     * @param taskId 任务标识
     * @return 可恢复 Checkpoint
     */
    @Override
    public Optional<AgentCheckpoint> findLatest(String taskId) {
        return maintenanceLock.read(() -> findLatestUnlocked(taskId));
    }

    /** 在维护读锁内读取最新可恢复 Checkpoint。 */
    private Optional<AgentCheckpoint> findLatestUnlocked(String taskId) {
        validateId(taskId, "任务标识");
        synchronized (lockFor(taskId)) {
            Path directory = checkpointDirectory(taskId);
            if (!Files.isDirectory(directory)) {
                return Optional.empty();
            }
            Optional<AgentCheckpoint> current = readCurrent(taskId, directory);
            return current.isPresent() ? current : scanLatest(taskId, directory);
        }
    }

    /**
     * 按当前指针读取 Checkpoint，损坏时隔离并触发历史扫描。
     *
     * @param taskId 任务标识
     * @param directory Checkpoint 目录
     * @return 当前 Checkpoint
     */
    private Optional<AgentCheckpoint> readCurrent(String taskId, Path directory) {
        Path pointerFile = directory.resolve("checkpoint-current.json");
        if (!Files.exists(pointerFile)) {
            return Optional.empty();
        }
        try {
            AgentCheckpointPointer pointer = codec.readPointer(pointerFile);
            return Optional.of(codec.readCheckpoint(directory.resolve(pointer.fileName())));
        } catch (RuntimeException ex) {
            quarantine(taskId, "current", ex);
            return Optional.empty();
        }
    }

    /**
     * 扫描历史文件并选择任务版本、事件序号和时间最新的完整 Checkpoint。
     *
     * @param taskId 任务标识
     * @param directory Checkpoint 目录
     * @return 最后完整 Checkpoint
     */
    private Optional<AgentCheckpoint> scanLatest(String taskId, Path directory) {
        Comparator<AgentCheckpoint> comparator = Comparator.comparingLong(AgentCheckpoint::taskVersion)
                .thenComparingLong(AgentCheckpoint::eventSequence)
                .thenComparing(AgentCheckpoint::createdAt);
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().startsWith("checkpoint-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("checkpoint-current.json"))
                    .map(path -> readCandidate(taskId, path))
                    .flatMap(Optional::stream)
                    .max(comparator);
        } catch (IOException ex) {
            throw new AgentCheckpointStoreException("Checkpoint 历史扫描失败", ex);
        }
    }

    /**
     * 读取候选 Checkpoint，损坏候选只隔离自身。
     *
     * @param taskId 任务标识
     * @param path 候选文件
     * @return 完整候选
     */
    private Optional<AgentCheckpoint> readCandidate(String taskId, Path path) {
        try {
            return Optional.of(codec.readCheckpoint(path));
        } catch (RuntimeException ex) {
            quarantine(taskId, path.getFileName().toString(), ex);
            return Optional.empty();
        }
    }

    /**
     * 记录损坏 Checkpoint，保留原文件供后续恢复。
     *
     * @param taskId 任务标识
     * @param source 损坏来源
     * @param cause 损坏原因
     */
    private void quarantine(String taskId, String source, RuntimeException cause) {
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        Path marker = quarantineRoot.resolve(
                taskId + "-checkpoint-" + System.nanoTime() + ".error");
        try {
            Files.writeString(marker, source + ": " + message,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            LOGGER.warn("Checkpoint 隔离记录写入失败, taskId={}", taskId);
        }
        LOGGER.warn("Checkpoint 已隔离, taskId={}, source={}, reason={}", taskId, source, message);
    }

    /**
     * 获取任务 Checkpoint 目录。
     *
     * @param taskId 任务标识
     * @return Checkpoint 目录
     */
    private Path checkpointDirectory(String taskId) {
        String compact = taskId.replace("-", "");
        String first = compact.substring(0, Math.min(2, compact.length()));
        String second = compact.length() > 2 ? compact.substring(2, Math.min(4, compact.length())) : "00";
        return root.resolve("tasks").resolve(first).resolve(second).resolve(taskId).resolve("checkpoints");
    }

    /**
     * 校验文件标识，阻断路径穿越。
     *
     * @param value 标识值
     * @param fieldName 字段名
     */
    private void validateId(String value, String fieldName) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + "不合法");
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
