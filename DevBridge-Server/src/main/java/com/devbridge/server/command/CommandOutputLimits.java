package com.devbridge.server.command;

/**
 * 一次命令的 stdout/stderr 读取上限组合。
 *
 * <p>by AI.Coding</p>
 *
 * @param stdout 标准输出上限
 * @param stderr 错误输出上限
 */
public record CommandOutputLimits(ProcessOutputLimit stdout, ProcessOutputLimit stderr) {

    private static final ProcessOutputLimit DEFAULT_STDOUT = new ProcessOutputLimit(100_000, 16 * 1_024 * 1_024);
    private static final ProcessOutputLimit DEFAULT_STDERR = new ProcessOutputLimit(20_000, 4 * 1_024 * 1_024);

    /**
     * 校验两个输出流都具备明确上限。
     */
    public CommandOutputLimits {
        if (stdout == null || stderr == null) {
            throw new IllegalArgumentException("stdout and stderr limits must not be null");
        }
    }

    /**
     * 返回通用命令默认上限，兼顾现有设备解析和堆内存安全。
     *
     * @return 默认上限
     */
    public static CommandOutputLimits defaults() {
        return new CommandOutputLimits(DEFAULT_STDOUT, DEFAULT_STDERR);
    }

    /**
     * 根据工具定义中的行数和字节数创建上限。
     *
     * @param stdoutLines stdout 最大行数
     * @param stderrLines stderr 最大行数
     * @param stdoutBytes stdout 最大字节数
     * @param stderrBytes stderr 最大字节数
     * @return 命令输出上限
     */
    public static CommandOutputLimits of(
            int stdoutLines,
            int stderrLines,
            int stdoutBytes,
            int stderrBytes) {
        return new CommandOutputLimits(
                new ProcessOutputLimit(stdoutLines, stdoutBytes),
                new ProcessOutputLimit(stderrLines, stderrBytes));
    }
}
