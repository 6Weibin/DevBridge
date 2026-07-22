package com.devbridge.server.ai.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.storage.StorageManager.StorageCategory;
import com.devbridge.server.ai.storage.StorageManager.StorageLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Storage Manager 测试，覆盖 80/95/100% 阈值、写入拒绝和受保护数据清理。
 *
 * <p>by AI.Coding</p>
 */
class StorageManagerTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * 验证 80%、95% 和 100% 使用率映射到明确状态。
     */
    @Test
    void snapshotShouldReportThresholdLevels() throws Exception {
        Path artifacts = tempDir.resolve("artifacts");
        Files.createDirectories(artifacts);
        Path file = artifacts.resolve("data.bin");
        StorageManager manager = manager(artifacts, tempDir.resolve("agent"), 100);

        Files.write(file, new byte[80]);
        assertThat(manager.snapshot().level()).isEqualTo(StorageLevel.WARNING);
        Files.write(file, new byte[95]);
        assertThat(manager.snapshot().level()).isEqualTo(StorageLevel.CRITICAL);
        Files.write(file, new byte[100]);
        assertThat(manager.snapshot().level()).isEqualTo(StorageLevel.FULL);
    }

    /**
     * 验证并发写入许可在超过配额前拒绝，而不是写满后再截断业务结果。
     */
    @Test
    void writePermitShouldRejectQuotaOverflow() throws Exception {
        Path artifacts = tempDir.resolve("artifacts");
        Files.createDirectories(artifacts);
        Files.write(artifacts.resolve("existing.bin"), new byte[90]);
        StorageManager manager = manager(artifacts, tempDir.resolve("agent"), 100);

        try (var permit = manager.openWrite(StorageCategory.ARTIFACTS)) {
            assertThatThrownBy(() -> permit.reserve(11))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("超过存储配额");
        }
    }

    /**
     * 验证临界状态只清理过期 Artifact，Agent Task 和受保护路径保持不变。
     */
    @Test
    void cleanupShouldKeepAgentAndProtectedData() throws Exception {
        Path artifacts = tempDir.resolve("artifacts");
        Path expired = artifacts.resolve("artifact-expired");
        Path agent = tempDir.resolve("agent");
        Files.createDirectories(expired);
        Files.createDirectories(agent);
        Files.write(expired.resolve("000000.part"), new byte[60]);
        Files.writeString(expired.resolve("metadata.json"),
                "{\"policy\":{\"retentionUntil\":\"" + Instant.EPOCH + "\"}}");
        Path task = agent.resolve("active-task.json");
        Files.write(task, new byte[40]);
        StorageManager manager = manager(artifacts, agent, 100);

        var result = manager.cleanup(Set.of(task));

        assertThat(Files.exists(expired)).isFalse();
        assertThat(Files.exists(task)).isTrue();
        assertThat(result.deletedFiles()).isEqualTo(2);
    }

    /**
     * 创建只配置 Artifact 和 Agent 分类的管理器。
     *
     * @param artifacts Artifact 根目录
     * @param agent Agent 根目录
     * @param quota 配额
     * @return 管理器
     */
    private StorageManager manager(Path artifacts, Path agent, long quota) {
        return new StorageManager(
                Map.of(StorageCategory.ARTIFACTS, artifacts, StorageCategory.AGENT, agent),
                quota,
                objectMapper);
    }
}
