package com.devbridge.server.ai.agent.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Agent Event 行式文件编解码器，每行带独立 SHA-256 校验和。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AgentEventFileCodec {

    private static final String SCHEMA_VERSION = "1.0.0";

    private final ObjectMapper objectMapper;

    /**
     * 注入统一 JSON 序列化器。
     *
     * @param objectMapper JSON 序列化器
     */
    public AgentEventFileCodec(ObjectMapper objectMapper) {
        // Event 文件使用固定的 ISO 时间格式，避免全局 Jackson 配置变化破坏摘要稳定性。
        this.objectMapper = objectMapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * 追加事件并强制刷新，确保返回前事件已经持久化。
     *
     * @param target 事件文件
     * @param event Agent Event
     */
    public void append(Path target, AgentEvent event) {
        JsonNode payload = objectMapper.valueToTree(event);
        StoredAgentEvent stored = new StoredAgentEvent(SCHEMA_VERSION, payload, checksum(payload));
        byte[] json = serialize(stored);
        byte[] line = new byte[json.length + 1];
        System.arraycopy(json, 0, line, 0, json.length);
        line[line.length - 1] = '\n';
        try {
            Files.createDirectories(target.getParent());
            try (FileChannel channel = FileChannel.open(
                    target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                writeFully(channel, line);
                channel.force(false);
            }
        } catch (IOException ex) {
            throw new AgentEventStoreException("Agent Event 追加失败", ex);
        }
    }

    /**
     * 解码并校验一行事件记录。
     *
     * @param line 单行 JSON
     * @return Agent Event
     */
    public AgentEvent decode(String line) {
        try {
            StoredAgentEvent stored = objectMapper.readValue(line, StoredAgentEvent.class);
            if (!SCHEMA_VERSION.equals(stored.schemaVersion())) {
                throw new IllegalStateException("不支持的 Agent Event Schema: " + stored.schemaVersion());
            }
            // 校验磁盘中的原始 payload JSON，避免 Map 反序列化后键顺序变化造成假损坏。
            if (!checksum(stored.payload()).equals(stored.sha256())) {
                throw new IllegalStateException("Agent Event 校验和不匹配");
            }
            return objectMapper.treeToValue(stored.payload(), AgentEvent.class);
        } catch (IOException | RuntimeException ex) {
            throw new AgentEventStoreException("Agent Event 解码失败", ex);
        }
    }

    /**
     * 计算事件内容摘要。
     *
     * @param payload 已持久化的事件 JSON
     * @return SHA-256 十六进制摘要
     */
    private String checksum(JsonNode payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(objectMapper.writeValueAsBytes(payload)));
        } catch (NoSuchAlgorithmException | IOException ex) {
            throw new AgentEventStoreException("Agent Event 校验和计算失败", ex);
        }
    }

    /**
     * 序列化事件文件对象。
     *
     * @param value 文件对象
     * @return JSON 字节
     */
    private byte[] serialize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (IOException ex) {
            throw new AgentEventStoreException("Agent Event 序列化失败", ex);
        }
    }

    /**
     * 完整写入事件字节。
     *
     * @param channel 文件通道
     * @param bytes 事件字节
     * @throws IOException 写入失败时抛出
     */
    private void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * Agent Event 磁盘 Envelope。
     *
     * @param schemaVersion 文件格式版本
     * @param payload Agent Event JSON
     * @param sha256 内容摘要
     */
    private record StoredAgentEvent(String schemaVersion, JsonNode payload, String sha256) {
    }
}
