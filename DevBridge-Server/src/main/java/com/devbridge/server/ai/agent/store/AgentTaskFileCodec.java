package com.devbridge.server.ai.agent.store;

import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Agent Task 文件编解码器，集中处理版本、校验和和原子写入。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AgentTaskFileCodec {

    static final String SCHEMA_VERSION = "1.1.0";
    private static final String LEGACY_SCHEMA_VERSION = "1.0.0";

    private final ObjectMapper objectMapper;

    /**
     * 注入统一 JSON 序列化器。
     *
     * @param objectMapper JSON 序列化器
     */
    public AgentTaskFileCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 原子写入任务当前快照，崩溃后只能看到旧完整文件或新完整文件。
     *
     * @param target 目标文件
     * @param task 任务快照
     */
    public void writeTaskAtomic(Path target, AgentTask task) {
        byte[] bytes = serialize(envelope(task));
        writeAtomic(target, bytes);
    }

    /**
     * 追加一条带校验和的任务历史记录；当前快照已强制落盘，历史尾部允许重建。
     *
     * @param target 历史文件
     * @param task 任务快照
     */
    public void appendTask(Path target, AgentTask task) {
        appendJsonLine(target, envelope(task));
    }

    /**
     * 读取并验证任务快照版本和 SHA-256。
     *
     * @param source 任务快照文件
     * @return 通过校验的任务
     */
    public AgentTask readTask(Path source) {
        try {
            JsonNode stored = objectMapper.readTree(source.toFile());
            String schemaVersion = stored.path("schemaVersion").asText("");
            if (!SCHEMA_VERSION.equals(schemaVersion) && !LEGACY_SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalStateException("不支持的 Agent Task Schema: " + schemaVersion);
            }
            JsonNode payload = stored.path("payload");
            if (LEGACY_SCHEMA_VERSION.equals(schemaVersion)) {
                return readLegacy(payload, stored.path("sha256").asText(""));
            }
            if (!checksum(payload).equals(stored.path("sha256").asText(""))) {
                throw new IllegalStateException("Agent Task 校验和不匹配");
            }
            return objectMapper.treeToValue(migrate(payload), AgentTask.class);
        } catch (IOException | RuntimeException ex) {
            throw new AgentTaskStoreException(
                    "Agent Task 文件读取失败: " + (ex.getMessage() == null
                            ? ex.getClass().getSimpleName()
                            : ex.getMessage()), ex);
        }
    }

    /**
     * 使用 1.0 原对象算法校验旧任务，再迁移为当前聚合时间模型。
     *
     * @param payload 旧任务 JSON
     * @param expectedChecksum 旧摘要
     * @return 当前任务模型
     * @throws IOException 旧任务解析失败
     */
    private AgentTask readLegacy(JsonNode payload, String expectedChecksum) throws IOException {
        LegacyAgentTask legacy = objectMapper.treeToValue(payload, LegacyAgentTask.class);
        if (!checksumValue(legacy).equals(expectedChecksum)) {
            throw new IllegalStateException("Agent Task 校验和不匹配");
        }
        return new AgentTask(
                legacy.taskId(), legacy.conversationId(), legacy.goal(), legacy.state(),
                legacy.stateReason(), legacy.version(), legacy.createdAt(), legacy.updatedAt());
    }

    /**
     * 将 1.0 任务时间字段迁移为聚合时间对象，原始文件校验在迁移前完成。
     *
     * @param payload 原始任务 JSON
     * @return 当前任务 JSON
     */
    private JsonNode migrate(JsonNode payload) {
        if (payload.has("timing")) {
            return payload;
        }
        ObjectNode migrated = payload.deepCopy();
        ObjectNode timing = objectMapper.createObjectNode();
        timing.set("createdAt", payload.path("createdAt"));
        timing.set("updatedAt", payload.path("updatedAt"));
        migrated.set("timing", timing);
        migrated.remove("createdAt");
        migrated.remove("updatedAt");
        return migrated;
    }

    /**
     * 追加一条任务索引变更记录，索引损坏时可以从任务快照重建。
     *
     * @param target 索引文件
     * @param record 索引记录
     */
    public void appendIndex(Path target, AgentTaskIndexRecord record) {
        appendJsonLine(target, record);
    }

    /**
     * 原子压缩当前索引，避免追加文件无限增长。
     *
     * @param target 索引文件
     * @param records 当前索引记录
     */
    public void writeIndexAtomic(Path target, Collection<AgentTaskIndexRecord> records) {
        StringBuilder content = new StringBuilder();
        for (AgentTaskIndexRecord record : records) {
            content.append(new String(serialize(record), StandardCharsets.UTF_8)).append('\n');
        }
        writeAtomic(target, content.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构造带格式版本和内容摘要的文件 Envelope。
     *
     * @param task 任务快照
     * @return 存储 Envelope
     */
    private ObjectNode envelope(AgentTask task) {
        JsonNode payload = canonicalPayload(task);
        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("schemaVersion", SCHEMA_VERSION);
        stored.set("payload", payload);
        stored.put("sha256", checksum(payload));
        return stored;
    }

    /**
     * 通过完整 JSON 往返规范化时间小数精度，保证写入前后摘要完全一致。
     *
     * @param task 任务快照
     * @return 规范任务 JSON
     */
    private JsonNode canonicalPayload(AgentTask task) {
        try {
            return objectMapper.readTree(objectMapper.writeValueAsBytes(task));
        } catch (IOException ex) {
            throw new AgentTaskStoreException("Agent Task payload 生成失败", ex);
        }
    }

    /**
     * 对磁盘原始任务 JSON 计算摘要，确保旧文件迁移前仍按原内容校验。
     *
     * @param payload 原始任务 JSON
     * @return 十六进制摘要
     */
    private String checksum(JsonNode payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(objectMapper.writeValueAsBytes(payload)));
        } catch (NoSuchAlgorithmException | IOException ex) {
            throw new AgentTaskStoreException("Agent Task 校验和计算失败", ex);
        }
    }

    /**
     * 按旧版对象序列化方式计算摘要，仅用于 1.0 任务迁移。
     *
     * @param value 旧版任务对象
     * @return 十六进制摘要
     */
    private String checksumValue(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(objectMapper.writeValueAsBytes(value)));
        } catch (NoSuchAlgorithmException | IOException ex) {
            throw new AgentTaskStoreException("Agent Task 校验和计算失败", ex);
        }
    }

    /**
     * 将对象序列化为有界文件字节。
     *
     * @param value 待序列化对象
     * @return JSON 字节
     */
    private byte[] serialize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (IOException ex) {
            throw new AgentTaskStoreException("Agent Task 序列化失败", ex);
        }
    }

    /**
     * 使用同目录临时文件和原子替换写入目标文件。
     *
     * @param target 目标文件
     * @param bytes 完整文件内容
     */
    private void writeAtomic(Path target, byte[] bytes) {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(target.getParent());
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writeFully(channel, bytes);
                channel.force(true);
            }
            moveAtomic(temporary, target);
        } catch (IOException ex) {
            throw new AgentTaskStoreException("Agent Task 原子写入失败", ex);
        } finally {
            tryDelete(temporary);
        }
    }

    /**
     * 缓冲追加单行 JSON，避免可重建历史和索引产生逐条 fsync 开销。
     *
     * @param target 目标文件
     * @param value 记录对象
     */
    private void appendJsonLine(Path target, Object value) {
        byte[] json = serialize(value);
        byte[] line = new byte[json.length + 1];
        System.arraycopy(json, 0, line, 0, json.length);
        line[line.length - 1] = '\n';
        try {
            Files.createDirectories(target.getParent());
            try (FileChannel channel = FileChannel.open(
                    target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                writeFully(channel, line);
            }
        } catch (IOException ex) {
            throw new AgentTaskStoreException("Agent Task 追加写入失败", ex);
        }
    }

    /**
     * 完整写入 ByteBuffer，防止一次 channel.write 未写完全部数据。
     *
     * @param channel 文件通道
     * @param bytes 待写入字节
     * @throws IOException 写入失败时抛出
     */
    private void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * 优先使用原子移动，不支持时回退为同文件系统替换。
     *
     * @param source 临时文件
     * @param target 目标文件
     * @throws IOException 移动失败时抛出
     */
    private void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 清理失败写入留下的临时文件，不覆盖原始异常。
     *
     * @param path 临时文件
     */
    private void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件会在启动扫描时忽略，不能因为清理失败覆盖真正写入异常。
        }
    }

    /**
     * Agent Task 1.0 磁盘模型，只用于读取旧文件并保持旧摘要算法。
     *
     * <p>by AI.Coding</p>
     *
     * @param taskId 任务标识
     * @param conversationId 会话标识
     * @param goal 任务目标
     * @param state 任务状态
     * @param stateReason 状态原因
     * @param version 版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    private record LegacyAgentTask(
            String taskId,
            String conversationId,
            String goal,
            AgentTaskState state,
            String stateReason,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

}
