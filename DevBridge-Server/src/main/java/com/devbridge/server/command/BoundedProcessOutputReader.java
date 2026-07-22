package com.devbridge.server.command;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 字节级有界进程输出读取器，超限后继续排空管道但不再保留数据。
 *
 * <p>by AI.Coding</p>
 */
public final class BoundedProcessOutputReader {

    private static final int BUFFER_SIZE = 16 * 1_024;

    /**
     * 读取并排空进程输出，只在内存中保留配置允许的前缀。
     *
     * @param inputStream 进程输出流
     * @param limit 输出上限
     * @return 有界输出和完整消费统计
     * @throws IOException 读取失败时抛出
     */
    public BoundedProcessOutput read(InputStream inputStream, ProcessOutputLimit limit) throws IOException {
        if (inputStream == null || limit == null) {
            throw new IllegalArgumentException("inputStream and limit must not be null");
        }
        ByteArrayOutputStream retained = new ByteArrayOutputStream(Math.min(limit.maxBytes(), BUFFER_SIZE));
        OutputCounter counter = new OutputCounter();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            consume(buffer, read, retained, limit, counter);
        }
        List<String> lines = decodeLines(retained);
        ProcessOutputStats stats = counter.stats(lines.size(), retained.size());
        return new BoundedProcessOutput(lines, stats);
    }

    /**
     * 消费一个字节块；达到保留上限后仍统计并排空剩余数据。
     *
     * @param buffer 输入缓冲区
     * @param length 有效字节数
     * @param retained 保留缓冲区
     * @param limit 输出上限
     * @param counter 输出计数器
     */
    private void consume(
            byte[] buffer,
            int length,
            ByteArrayOutputStream retained,
            ProcessOutputLimit limit,
            OutputCounter counter) {
        counter.totalBytes += length;
        for (int index = 0; index < length; index++) {
            byte value = buffer[index];
            counter.observe(value);
            if (counter.canRetain(value, retained.size(), limit)) {
                retained.write(value);
                counter.observeRetained(value);
            }
        }
    }

    /**
     * 将有界 UTF-8 字节前缀转换为与 BufferedReader 兼容的行列表。
     *
     * @param retained 保留字节
     * @return 输出行
     */
    private List<String> decodeLines(ByteArrayOutputStream retained) {
        if (retained.size() == 0) {
            return List.of();
        }
        return new String(retained.toByteArray(), StandardCharsets.UTF_8).lines().toList();
    }

    /**
     * 记录总输出和保留边界，避免为每个数据块创建临时统计对象。
     *
     * <p>by AI.Coding</p>
     */
    private static final class OutputCounter {

        private long totalBytes;
        private long totalLines;
        private int retainedLineBreaks;
        private boolean totalLinePending;
        private boolean totalPreviousCarriageReturn;
        private boolean retainedPreviousCarriageReturn;

        /**
         * 统计实际消费的字节和逻辑行。
         *
         * @param value 当前字节
         */
        private void observe(byte value) {
            if (value == '\r') {
                totalLines++;
                totalLinePending = false;
                totalPreviousCarriageReturn = true;
                return;
            }
            if (value == '\n') {
                if (!totalPreviousCarriageReturn) {
                    totalLines++;
                }
                totalLinePending = false;
                totalPreviousCarriageReturn = false;
                return;
            }
            totalPreviousCarriageReturn = false;
            totalLinePending = true;
        }

        /**
         * 判断当前字节是否仍可进入保留缓冲区。
         *
         * @param value 当前字节
         * @param retainedBytes 已保留字节数
         * @param limit 输出上限
         * @return 可以保留返回 true
         */
        private boolean canRetain(byte value, int retainedBytes, ProcessOutputLimit limit) {
            if (retainedBytes >= limit.maxBytes()) {
                return false;
            }
            // CRLF 是同一个换行，达到行数上限后仍需保留紧随 CR 的 LF，避免误报截断。
            return retainedLineBreaks < limit.maxLines()
                    || (value == '\n' && retainedPreviousCarriageReturn);
        }

        /**
         * 统计已保留换行符，用于严格限制行数。
         *
         * @param value 已保留字节
         */
        private void observeRetained(byte value) {
            if (value == '\r') {
                retainedLineBreaks++;
                retainedPreviousCarriageReturn = true;
                return;
            }
            if (value == '\n') {
                if (!retainedPreviousCarriageReturn) {
                    retainedLineBreaks++;
                }
                retainedPreviousCarriageReturn = false;
                return;
            }
            retainedPreviousCarriageReturn = false;
        }

        /**
         * 生成最终统计；未以换行结束的尾部也计为一行。
         *
         * @param retainedLines 已保留逻辑行数
         * @param retainedBytes 已保留字节数
         * @return 输出统计
         */
        private ProcessOutputStats stats(long retainedLines, long retainedBytes) {
            long actualLines = totalLines + (totalLinePending ? 1 : 0);
            boolean truncated = totalBytes > retainedBytes || actualLines > retainedLines;
            return new ProcessOutputStats(actualLines, retainedLines, totalBytes, retainedBytes, truncated);
        }
    }
}
