package com.devbridge.server.ai.agent.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Agent Event 文件编解码关键回归测试。
 *
 * <p>by AI.Coding</p>
 */
class AgentEventFileCodecTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AgentEventFileCodec codec = new AgentEventFileCodec(mapper);

    /**
     * 校验应基于文件中的 payload JSON，不受反序列化后 Map 顺序影响。
     */
    @Test
    void shouldDecodeChecksumCalculatedFromStoredPayload() throws Exception {
        AgentEvent event = event();
        JsonNode payload = mapper.valueToTree(event);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(payload)));
        String line = mapper.writeValueAsString(Map.of(
                "schemaVersion", "1.0.0", "payload", payload, "sha256", sha256));

        assertThat(codec.decode(line)).isEqualTo(event);
    }

    /** 真实的磁盘内容篡改仍必须被拒绝。 */
    @Test
    void shouldRejectTamperedPayload() throws Exception {
        Path file = Files.createTempFile("agent-event", ".ndjson");
        codec.append(file, event());
        String line = Files.readString(file).replace("原始值", "篡改值").trim();

        assertThatThrownBy(() -> codec.decode(line))
                .isInstanceOf(AgentEventStoreException.class)
                .hasRootCauseMessage("Agent Event 校验和不匹配");
    }

    /** 构造包含有序载荷的测试事件。 */
    private AgentEvent event() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("zKey", "原始值");
        payload.put("aKey", "辅助值");
        return new AgentEvent(
                "1.0.0",
                new AgentEventIdentity("task-1", 1L, AgentEventType.TASK_CREATED, AgentEventScope.TASK),
                new AgentEventContext("conversation-1", null, null, null, null, null, 1L),
                new AgentEventTiming(Instant.parse("2026-07-21T01:00:00Z"), Instant.parse("2026-07-21T01:00:01Z")),
                "test",
                payload);
    }
}
