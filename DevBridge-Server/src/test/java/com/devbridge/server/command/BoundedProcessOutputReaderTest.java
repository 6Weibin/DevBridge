package com.devbridge.server.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 有界进程输出读取器测试，验证超大输出不会按原始体量驻留堆内存。
 *
 * <p>by AI.Coding</p>
 */
class BoundedProcessOutputReaderTest {

    /**
     * 验证单行超过字节上限时只保留有界前缀，同时继续消费完整输入流。
     */
    @Test
    void readShouldBoundSingleVeryLongLine() throws IOException {
        BoundedProcessOutputReader reader = new BoundedProcessOutputReader();
        ProcessOutputLimit limit = new ProcessOutputLimit(10, 64);

        BoundedProcessOutput output = reader.read(
                new RepeatingInputStream(1_024, (byte) 'x'),
                limit);

        assertThat(output.lines()).containsExactly("x".repeat(64));
        assertThat(output.stats().totalBytes()).isEqualTo(1_024);
        assertThat(output.stats().retainedBytes()).isEqualTo(64);
        assertThat(output.stats().discardedBytes()).isEqualTo(960);
        assertThat(output.stats().truncated()).isTrue();
    }

    /**
     * 验证达到行数上限后仍消费后续行，并准确记录丢弃行数。
     */
    @Test
    void readShouldBoundLineCountAndTrackDiscardedLines() throws IOException {
        BoundedProcessOutputReader reader = new BoundedProcessOutputReader();
        ProcessOutputLimit limit = new ProcessOutputLimit(2, 1_024);
        InputStream input = new java.io.ByteArrayInputStream("one\ntwo\nthree\nfour\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        BoundedProcessOutput output = reader.read(input, limit);

        assertThat(output.lines()).containsExactly("one", "two");
        assertThat(output.stats().totalLines()).isEqualTo(4);
        assertThat(output.stats().retainedLines()).isEqualTo(2);
        assertThat(output.stats().discardedLines()).isEqualTo(2);
        assertThat(output.stats().truncated()).isTrue();
    }

    /**
     * 验证 CR、LF 和 CRLF 都按逻辑行处理，防止特殊换行绕过行数上限。
     */
    @Test
    void readShouldTreatCommonLineSeparatorsConsistently() throws IOException {
        BoundedProcessOutputReader reader = new BoundedProcessOutputReader();
        ProcessOutputLimit limit = new ProcessOutputLimit(3, 1_024);
        InputStream input = new java.io.ByteArrayInputStream(
                "one\rtwo\r\nthree\nfour".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        BoundedProcessOutput output = reader.read(input, limit);

        assertThat(output.lines()).containsExactly("one", "two", "three");
        assertThat(output.stats().totalLines()).isEqualTo(4);
        assertThat(output.stats().retainedLines()).isEqualTo(3);
        assertThat(output.stats().discardedLines()).isEqualTo(1);
        assertThat(output.stats().truncated()).isTrue();
    }

    /**
     * 验证逻辑体量为 1 GiB 的输入只保留配置上限，防止堆占用随输出线性增长。
     */
    @Test
    void readShouldKeepOneGibibyteVirtualOutputBounded() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            long oneGibibyte = 1_024L * 1_024L * 1_024L;
            BoundedProcessOutputReader reader = new BoundedProcessOutputReader();

            BoundedProcessOutput output = reader.read(
                    new RepeatingInputStream(oneGibibyte, (byte) 'x'),
                    new ProcessOutputLimit(10, 64 * 1_024));

            assertThat(output.stats().totalBytes()).isEqualTo(oneGibibyte);
            assertThat(output.stats().retainedBytes()).isEqualTo(64 * 1_024);
            assertThat(output.stats().discardedBytes()).isEqualTo(oneGibibyte - 64 * 1_024);
            assertThat(output.lines()).hasSize(1);
            assertThat(output.stats().truncated()).isTrue();
        });
    }

    /**
     * 按需生成固定字节的虚拟输入流，避免测试自身分配超大字节数组。
     *
     * <p>by AI.Coding</p>
     */
    private static final class RepeatingInputStream extends InputStream {

        private long remaining;
        private final byte value;

        /**
         * 创建指定逻辑长度的虚拟输入流。
         *
         * @param size 逻辑字节数
         * @param value 重复字节
         */
        private RepeatingInputStream(long size, byte value) {
            this.remaining = size;
            this.value = value;
        }

        /**
         * 读取单个字节，供 InputStream 基础契约使用。
         *
         * @return 字节值，流结束返回 -1
         */
        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            return Byte.toUnsignedInt(value);
        }

        /**
         * 批量填充调用方缓冲区，确保 1 GiB 测试不创建同等体量对象。
         *
         * @param buffer 目标缓冲区
         * @param offset 起始位置
         * @param length 最大读取长度
         * @return 实际读取字节数，流结束返回 -1
         */
        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (remaining <= 0) {
                return -1;
            }
            int count = (int) Math.min(remaining, length);
            Arrays.fill(buffer, offset, offset + count, value);
            remaining -= count;
            return count;
        }
    }
}
