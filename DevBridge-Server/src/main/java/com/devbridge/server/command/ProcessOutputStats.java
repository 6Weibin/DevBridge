package com.devbridge.server.command;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 单个进程输出流的读取统计，用于诊断截断和资源消耗。
 *
 * <p>by AI.Coding</p>
 *
 * @param totalLines 实际消费的总行数
 * @param retainedLines 内存中保留的行数
 * @param totalBytes 实际消费的总字节数
 * @param retainedBytes 内存中保留的字节数
 * @param truncated 是否发生截断
 */
public record ProcessOutputStats(
        long totalLines,
        long retainedLines,
        long totalBytes,
        long retainedBytes,
        boolean truncated) {

    /**
     * 返回空输出统计。
     *
     * @return 空统计
     */
    public static ProcessOutputStats empty() {
        return new ProcessOutputStats(0, 0, 0, 0, false);
    }

    /**
     * 为已有的小型行列表生成兼容统计，供历史构造调用继续使用。
     *
     * @param lines 已保留输出行
     * @return 未截断统计
     */
    public static ProcessOutputStats retained(List<String> lines) {
        List<String> values = lines == null ? List.of() : lines;
        long bytes = 0;
        for (int index = 0; index < values.size(); index++) {
            bytes += values.get(index).getBytes(StandardCharsets.UTF_8).length;
            if (index < values.size() - 1) {
                bytes++;
            }
        }
        return new ProcessOutputStats(values.size(), values.size(), bytes, bytes, false);
    }

    /**
     * 计算因上限被丢弃的行数。
     *
     * @return 丢弃行数
     */
    public long discardedLines() {
        return Math.max(0, totalLines - retainedLines);
    }

    /**
     * 计算因上限被丢弃的字节数。
     *
     * @return 丢弃字节数
     */
    public long discardedBytes() {
        return Math.max(0, totalBytes - retainedBytes);
    }
}
