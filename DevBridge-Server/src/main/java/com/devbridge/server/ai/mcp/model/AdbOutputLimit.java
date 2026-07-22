package com.devbridge.server.ai.mcp.model;

/**
 * ADB 工具输出限制，避免大日志或 bugreport 摘要直接进入内存和 AI Prompt。
 *
 * <p>by AI.Coding</p>
 *
 * @param maxStdoutLines stdout 最大行数
 * @param maxStderrLines stderr 最大行数
 * @param maxStdoutCharacters stdout 最大字符数
 * @param maxStderrCharacters stderr 最大字符数
 */
public record AdbOutputLimit(
        int maxStdoutLines,
        int maxStderrLines,
        int maxStdoutCharacters,
        int maxStderrCharacters) {

    /**
     * 返回默认输出限制，兼顾日志分析上下文和侧边栏展示成本。
     *
     * @return 默认输出限制
     */
    public static AdbOutputLimit defaults() {
        return new AdbOutputLimit(500, 200, 60_000, 20_000);
    }
}
