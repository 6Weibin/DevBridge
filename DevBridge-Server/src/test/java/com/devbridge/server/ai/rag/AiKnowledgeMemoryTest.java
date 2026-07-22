package com.devbridge.server.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.config.AiConfigCrypto;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory.DeviceSnapshotRequest;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory.IncidentDetails;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory.IncidentRequest;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory.MemoryQuery;
import com.devbridge.server.ai.storage.StorageManager;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地 Memory 与 RAG 加密持久化业务测试。
 *
 * <p>by AI.Coding</p>
 */
class AiKnowledgeMemoryTest {

    /** 验证设备快照和故障案例可查询、重启恢复且磁盘不出现明文。 */
    @Test
    void shouldPersistEncryptedDeviceAndIncidentMemory(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        fixture.memory().recordSnapshot(new DeviceSnapshotRequest(
                "serial-secret", "ANDROID", "Pixel", "14",
                fixture.mapper().createObjectNode().put("battery", 18), Instant.now()));
        var incident = fixture.memory().recordIncident(new IncidentRequest(
                "serial-secret", "ANDROID", "14",
                new IncidentDetails("ANR", "主线程阻塞", List.of("Input dispatch timeout"),
                        "检查主线程", "VERIFIED", List.of("anr")), Instant.now()));

        assertThat(fixture.memory().searchIncidents(new MemoryQuery("serial-secret", "14", "ANR", 10)))
                .extracting(value -> value.details().signature()).containsExactly("ANR");
        String encrypted = Files.readString(root.resolve("ai/memory/incident/" + incident.id() + ".enc"));
        assertThat(encrypted).doesNotContain("serial-secret", "主线程阻塞");

        Fixture restarted = fixture(root);
        assertThat(restarted.memory().snapshots("serial-secret", 10)).hasSize(1);
        assertThat(restarted.memory().delete(incident.id())).isTrue();
    }

    /** 验证文档可导入、引用检索、重建并在重启后继续命中。 */
    @Test
    void shouldSearchCitedRagKnowledgeAfterRebuild(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        var metadata = fixture.rag().importDocument(new AiRagBoundary.ImportRequest(
                "Android ANR 手册", "manual://android-anr", "ANR 常见原因是主线程阻塞，应检查锁竞争和耗时 IO。"));

        var result = fixture.rag().search(new AiRagBoundary.SearchRequest("ANR 主线程阻塞", "", "", 5));
        assertThat(result.hasMatches()).isTrue();
        assertThat(result.citations().get(0).source()).isEqualTo("manual://android-anr");
        assertThat(Files.readString(root.resolve("ai/rag/documents/" + metadata.id() + ".enc")))
                .doesNotContain("主线程阻塞");
        assertThat(fixture.rag().rebuild()).isEqualTo(1);

        Fixture restarted = fixture(root);
        assertThat(restarted.rag().search(new AiRagBoundary.SearchRequest("锁竞争", "", "", 5)).hasMatches())
                .isTrue();
    }

    /** 创建共享本地存储依赖。 */
    private Fixture fixture(Path root) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiConfigRoot(root.resolve("ai").toString());
        properties.setAiAgentDataRoot(root.resolve("agent").toString());
        properties.setToolArtifactRoot(root.resolve("artifacts").toString());
        properties.setToolAuditRoot(root.resolve("audit").toString());
        properties.setLogCaptureRoot(root.resolve("logs").toString());
        properties.setDownloadTempRoot(root.resolve("downloads").toString());
        properties.setStorageQuotaBytes(1024L * 1024L * 1024L);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StorageManager storage = new StorageManager(properties, mapper);
        AiDeviceIncidentMemory memory = new AiDeviceIncidentMemory(
                properties, mapper, new AiConfigCrypto(), storage);
        AiRagBoundary rag = new AiRagBoundary(
                properties, mapper, new AiConfigCrypto(), storage, memory);
        return new Fixture(memory, rag, mapper);
    }

    /** 测试依赖。by AI.Coding */
    private record Fixture(AiDeviceIncidentMemory memory, AiRagBoundary rag, ObjectMapper mapper) {
    }
}
