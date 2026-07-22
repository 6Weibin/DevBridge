package com.devbridge.server.ai.agent.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Agent Checkpoint 文件编解码器，负责版本、校验和和原子替换。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AgentCheckpointFileCodec {

    private static final String SCHEMA_VERSION = "1.0.0";

    private final ObjectMapper objectMapper;

    /**
     * 注入统一 JSON 序列化器。
     *
     * @param objectMapper JSON 序列化器
     */
    public AgentCheckpointFileCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 原子写入带校验和的 Checkpoint。
     *
     * @param target 目标文件
     * @param checkpoint Checkpoint
     */
    public void writeCheckpoint(Path target, AgentCheckpoint checkpoint) {
        JsonNode payload = canonicalPayload(checkpoint);
        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("schemaVersion", SCHEMA_VERSION);
        stored.set("payload", payload);
        stored.put("sha256", checksum(payload));
        writeAtomic(target, serialize(stored));
    }

    /**
     * 通过一次完整 JSON 往返生成纯树节点，避免时间模块留下序列化行为不同的 POJO 节点。
     *
     * @param checkpoint Checkpoint
     * @return 可稳定计算摘要的 payload
     */
    private JsonNode canonicalPayload(AgentCheckpoint checkpoint) {
        try {
            return objectMapper.readTree(objectMapper.writeValueAsBytes(checkpoint));
        } catch (IOException ex) {
            throw new AgentCheckpointStoreException("Checkpoint payload 生成失败", ex);
        }
    }

    /**
     * 读取并校验 Checkpoint。
     *
     * @param source Checkpoint 文件
     * @return 完整 Checkpoint
     */
    public AgentCheckpoint readCheckpoint(Path source) {
        try {
            JsonNode stored = objectMapper.readTree(source.toFile());
            String schemaVersion = stored.path("schemaVersion").asText("");
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalStateException("不支持的 Checkpoint Schema: " + schemaVersion);
            }
            JsonNode payload = stored.path("payload");
            if (!checksum(payload).equals(stored.path("sha256").asText(""))) {
                throw new IllegalStateException("Checkpoint 校验和不匹配");
            }
            // 先校验磁盘原始 payload 再转换模型，新增可选字段不会使旧文件校验和失效。
            return objectMapper.treeToValue(payload, AgentCheckpoint.class);
        } catch (IOException | RuntimeException ex) {
            throw new AgentCheckpointStoreException("Checkpoint 读取失败", ex);
        }
    }

    /**
     * 原子写入当前 Checkpoint 指针。
     *
     * @param target 指针文件
     * @param pointer 当前指针
     */
    public void writePointer(Path target, AgentCheckpointPointer pointer) {
        writeAtomic(target, serialize(pointer));
    }

    /**
     * 读取当前 Checkpoint 指针。
     *
     * @param source 指针文件
     * @return 当前指针
     */
    public AgentCheckpointPointer readPointer(Path source) {
        try {
            return objectMapper.readValue(source.toFile(), AgentCheckpointPointer.class);
        } catch (IOException ex) {
            throw new AgentCheckpointStoreException("Checkpoint 指针读取失败", ex);
        }
    }

    /**
     * 对磁盘中的原始 payload JSON 计算摘要，兼容新增可选字段前的旧文件。
     *
     * @param payload Checkpoint JSON
     * @return SHA-256 十六进制摘要
     */
    private String checksum(JsonNode payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(objectMapper.writeValueAsBytes(payload)));
        } catch (NoSuchAlgorithmException | IOException ex) {
            throw new AgentCheckpointStoreException("Checkpoint 校验和计算失败", ex);
        }
    }

    /**
     * 序列化文件对象。
     *
     * @param value 文件对象
     * @return JSON 字节
     */
    private byte[] serialize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (IOException ex) {
            throw new AgentCheckpointStoreException("Checkpoint 序列化失败", ex);
        }
    }

    /**
     * 使用同目录临时文件强制落盘并原子替换。
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
            throw new AgentCheckpointStoreException("Checkpoint 原子写入失败", ex);
        } finally {
            tryDelete(temporary);
        }
    }

    /**
     * 完整写入文件通道。
     *
     * @param channel 文件通道
     * @param bytes 文件字节
     * @throws IOException 写入失败时抛出
     */
    private void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * 优先使用原子移动，不支持时回退到同文件系统替换。
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
     * 清理失败写入留下的临时文件。
     *
     * @param path 临时文件
     */
    private void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 启动恢复会忽略临时文件，清理失败不能覆盖真正写入异常。
        }
    }

}
