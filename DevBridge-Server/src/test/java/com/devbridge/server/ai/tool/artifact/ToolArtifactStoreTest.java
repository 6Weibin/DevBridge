package com.devbridge.server.ai.tool.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.tool.artifact.ToolArtifactStore.ArtifactWriteRequest;
import com.devbridge.server.ai.storage.AiDataMaintenanceLock;
import com.devbridge.server.ai.storage.StorageManager;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Artifact Store 测试，覆盖 100 MiB 流式写入、分段压缩和有界范围读取。
 *
 * <p>by AI.Coding</p>
 */
class ToolArtifactStoreTest {

    @TempDir
    Path tempDir;

    /**
     * 验证 100 MiB 输入按分段流式写入，并能跨分段读取原始字节范围。
     */
    @Test
    void writeShouldStreamLargeCompressedArtifactAndReadRange() {
        ToolArtifactStore store = new ToolArtifactStore(
                tempDir, new ObjectMapper().findAndRegisterModules());
        long size = 100L * 1024L * 1024L;
        ArtifactWriteRequest request = new ArtifactWriteRequest(
                "tool-output", "application/octet-stream", "INTERNAL",
                Instant.now().plusSeconds(3600), false, "gzip");

        var metadata = store.write(request, new RepeatingInputStream(size));
        var range = store.readRange(metadata.identity().artifactId(), ToolArtifactStore.SEGMENT_BYTES - 8L, 32);

        assertThat(metadata.storage().sizeBytes()).isEqualTo(size);
        assertThat(metadata.storage().segmentCount()).isEqualTo(25);
        assertThat(metadata.storage().sha256()).hasSize(64);
        assertThat(range.bytes()).hasSize(32);
        assertThat(range.bytes()[0]).isEqualTo((byte) ((ToolArtifactStore.SEGMENT_BYTES - 8L) % 251));
        assertThat(range.bytes()[31]).isEqualTo((byte) ((ToolArtifactStore.SEGMENT_BYTES + 23L) % 251));
        assertThat(store.reference(metadata).artifactId()).isEqualTo(metadata.identity().artifactId());
        Path restored = tempDir.resolve("restored.bin");
        store.copyTo(metadata.identity().artifactId(), restored);
        assertThat(restored).hasSize(size);
    }

    /**
     * 验证单次范围读取严格限制为 1 MiB。
     */
    @Test
    void readRangeShouldRejectUnboundedRequest() {
        ToolArtifactStore store = new ToolArtifactStore(
                tempDir, new ObjectMapper().findAndRegisterModules());
        var metadata = store.write(new ArtifactWriteRequest(
                "text", "text/plain", "INTERNAL", Instant.now().plusSeconds(60), true, "none"),
                new RepeatingInputStream(16));

        assertThatThrownBy(() -> store.readRange(
                metadata.identity().artifactId(), 0, ToolArtifactStore.MAX_RANGE_BYTES + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("范围参数无效");
    }

    /** 维护写锁持有期间 Artifact 写入必须等待，避免备份捕获半写分段。 */
    @Test
    void writeShouldWaitForMaintenanceLock() throws Exception {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setToolArtifactRoot(tempDir.resolve("artifacts").toString());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AiDataMaintenanceLock lock = new AiDataMaintenanceLock();
        ToolArtifactStore store = new ToolArtifactStore(
                properties, mapper, new StorageManager(properties, mapper), lock);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> maintenance = CompletableFuture.runAsync(() -> lock.write(() -> {
            locked.countDown();
            await(release);
            return null;
        }));
        assertThat(locked.await(1, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<?> write = CompletableFuture.supplyAsync(() -> store.write(
                new ArtifactWriteRequest("text", "text/plain", "INTERNAL",
                        Instant.now().plusSeconds(60), true, "none"),
                new ByteArrayInputStream("content".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThatThrownBy(() -> write.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        maintenance.get(1, TimeUnit.SECONDS);
        assertThat(write.get(1, TimeUnit.SECONDS)).isNotNull();
    }

    /** 等待测试锁并保留中断状态。 */
    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 不分配完整内容的确定性大输入流。
     *
     * <p>by AI.Coding</p>
     */
    private static class RepeatingInputStream extends InputStream {

        private final long size;
        private long position;

        /**
         * 创建指定逻辑长度的输入流。
         *
         * @param size 字节数
         */
        RepeatingInputStream(long size) {
            this.size = size;
        }

        /**
         * 读取单字节。
         *
         * @return 字节或 EOF
         */
        @Override
        public int read() {
            if (position >= size) {
                return -1;
            }
            return (int) (position++ % 251);
        }

        /**
         * 批量生成确定性字节，测试过程内存保持有界。
         *
         * @param buffer 目标数组
         * @param offset 数组偏移
         * @param length 请求长度
         * @return 实际长度或 EOF
         */
        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= size) {
                return -1;
            }
            int actual = (int) Math.min(length, size - position);
            for (int index = 0; index < actual; index++) {
                buffer[offset + index] = (byte) ((position + index) % 251);
            }
            position += actual;
            return actual;
        }
    }
}
