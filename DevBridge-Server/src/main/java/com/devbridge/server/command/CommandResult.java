package com.devbridge.server.command;

import java.util.List;

/**
 * 外部命令执行结果，区分标准输出、错误输出和超时状态。
 *
 * <p>by AI.Coding</p>
 *
 * @param exitCode 进程退出码
 * @param stdout 标准输出行
 * @param stderr 错误输出行
 * @param timedOut 是否超时
 * @param stdoutStats 标准输出读取统计
 * @param stderrStats 错误输出读取统计
 */
public record CommandResult(
        int exitCode,
        List<String> stdout,
        List<String> stderr,
        boolean timedOut,
        ProcessOutputStats stdoutStats,
        ProcessOutputStats stderrStats) {

    /**
     * 固化不可变结果并补齐缺省统计。
     */
    public CommandResult {
        stdout = stdout == null ? List.of() : List.copyOf(stdout);
        stderr = stderr == null ? List.of() : List.copyOf(stderr);
        stdoutStats = stdoutStats == null ? ProcessOutputStats.retained(stdout) : stdoutStats;
        stderrStats = stderrStats == null ? ProcessOutputStats.retained(stderr) : stderrStats;
    }

    /**
     * 保留历史四参数构造方式，避免无界读取治理影响现有服务和测试替身。
     *
     * @param exitCode 进程退出码
     * @param stdout 标准输出行
     * @param stderr 错误输出行
     * @param timedOut 是否超时
     */
    public CommandResult(int exitCode, List<String> stdout, List<String> stderr, boolean timedOut) {
        this(
                exitCode,
                stdout,
                stderr,
                timedOut,
                ProcessOutputStats.retained(stdout),
                ProcessOutputStats.retained(stderr));
    }

    /**
     * 根据两个有界读取结果创建命令结果，完整保留截断统计。
     *
     * @param exitCode 进程退出码
     * @param stdout 标准输出
     * @param stderr 错误输出
     * @param timedOut 是否超时
     * @return 命令结果
     */
    public static CommandResult fromBoundedOutputs(
            int exitCode,
            BoundedProcessOutput stdout,
            BoundedProcessOutput stderr,
            boolean timedOut) {
        BoundedProcessOutput safeStdout = stdout == null ? BoundedProcessOutput.empty() : stdout;
        BoundedProcessOutput safeStderr = stderr == null ? BoundedProcessOutput.empty() : stderr;
        return new CommandResult(
                exitCode,
                safeStdout.lines(),
                safeStderr.lines(),
                timedOut,
                safeStdout.stats(),
                safeStderr.stats());
    }

    /**
     * 判断命令是否正常结束，调用方据此决定是否解析输出。
     *
     * @return 正常结束返回 true
     */
    public boolean successful() {
        return exitCode == 0 && !timedOut;
    }

    /**
     * 判断任一输出流是否在读取阶段被截断。
     *
     * @return 发生截断返回 true
     */
    public boolean outputTruncated() {
        return stdoutStats.truncated() || stderrStats.truncated();
    }

    /**
     * 提取第一行可读输出，用于工具状态展示。
     *
     * @return 输出摘要
     */
    public String firstOutputLine() {
        if (!stdout.isEmpty()) {
            return stdout.get(0);
        }
        return stderr.isEmpty() ? "" : stderr.get(0);
    }
}
