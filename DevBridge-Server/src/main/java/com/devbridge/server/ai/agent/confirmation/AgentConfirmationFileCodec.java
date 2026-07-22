package com.devbridge.server.ai.agent.confirmation;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Agent 确认文件编解码器，提供 Schema、校验和和原子替换。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AgentConfirmationFileCodec {

    private static final String SCHEMA_VERSION = "1.0.0";

    private final ObjectMapper objectMapper;

    /**
     * 注入统一 JSON 序列化器。
     *
     * @param objectMapper JSON 序列化器
     */
    public AgentConfirmationFileCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 原子写入确认记录。
     *
     * @param target 目标文件
     * @param confirmation 确认记录
     */
    public void write(Path target, AgentConfirmation confirmation) {
        StoredConfirmation stored = new StoredConfirmation(
                SCHEMA_VERSION, confirmation, checksum(confirmation));
        writeAtomic(target, serialize(stored));
    }

    /**
     * 读取并校验确认记录。
     *
     * @param source 确认文件
     * @return 确认记录
     */
    public AgentConfirmation read(Path source) {
        try {
            StoredConfirmation stored = objectMapper.readValue(source.toFile(), StoredConfirmation.class);
            if (!SCHEMA_VERSION.equals(stored.schemaVersion())) {
                throw new IllegalStateException("不支持的确认 Schema: " + stored.schemaVersion());
            }
            if (!checksum(stored.payload()).equals(stored.sha256())) {
                throw new IllegalStateException("确认记录校验和不匹配");
            }
            return stored.payload();
        } catch (IOException | RuntimeException ex) {
            throw new AgentConfirmationStoreException("确认记录读取失败", ex);
        }
    }

    /**
     * 计算确认记录摘要。
     *
     * @param confirmation 确认记录
     * @return SHA-256 摘要
     */
    private String checksum(AgentConfirmation confirmation) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(objectMapper.writeValueAsBytes(confirmation)));
        } catch (NoSuchAlgorithmException | IOException ex) {
            throw new AgentConfirmationStoreException("确认记录校验和计算失败", ex);
        }
    }

    /**
     * 序列化确认文件对象。
     *
     * @param value 文件对象
     * @return JSON 字节
     */
    private byte[] serialize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (IOException ex) {
            throw new AgentConfirmationStoreException("确认记录序列化失败", ex);
        }
    }

    /**
     * 使用同目录临时文件强制落盘并原子替换。
     *
     * @param target 目标文件
     * @param bytes 完整内容
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
            throw new AgentConfirmationStoreException("确认记录原子写入失败", ex);
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
     * 优先使用原子移动，不支持时回退到替换。
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
     * 清理临时文件，不覆盖主要异常。
     *
     * @param path 临时文件
     */
    private void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件不会被读取为确认记录。
        }
    }

    /**
     * 确认磁盘 Envelope。
     *
     * @param schemaVersion 格式版本
     * @param payload 确认记录
     * @param sha256 内容摘要
     */
    private record StoredConfirmation(
            String schemaVersion, AgentConfirmation payload, String sha256) {
    }
}
