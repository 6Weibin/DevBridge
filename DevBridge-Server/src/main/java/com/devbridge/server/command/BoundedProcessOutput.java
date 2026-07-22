package com.devbridge.server.command;

import java.util.List;

/**
 * 有界进程输出，包含保留行和完整消费统计。
 *
 * <p>by AI.Coding</p>
 *
 * @param lines 内存中保留的输出行
 * @param stats 输出读取统计
 */
public record BoundedProcessOutput(List<String> lines, ProcessOutputStats stats) {

    /**
     * 固化不可变输出，防止调用方修改读取结果。
     */
    public BoundedProcessOutput {
        lines = lines == null ? List.of() : List.copyOf(lines);
        stats = stats == null ? ProcessOutputStats.empty() : stats;
    }

    /**
     * 返回空输出。
     *
     * @return 空输出
     */
    public static BoundedProcessOutput empty() {
        return new BoundedProcessOutput(List.of(), ProcessOutputStats.empty());
    }

    /**
     * 将读取异常转换为有界错误输出。
     *
     * @param message 错误消息
     * @return 单行错误输出
     */
    public static BoundedProcessOutput error(String message) {
        List<String> lines = List.of(message == null ? "command output unavailable" : message);
        return new BoundedProcessOutput(lines, ProcessOutputStats.retained(lines));
    }
}
