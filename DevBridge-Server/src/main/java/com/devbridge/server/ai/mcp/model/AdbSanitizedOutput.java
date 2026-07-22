package com.devbridge.server.ai.mcp.model;

/**
 * ADB 输出脱敏和截断后的结果。
 *
 * <p>by AI.Coding</p>
 *
 * @param stdout 标准输出
 * @param stderr 错误输出
 * @param truncated 是否截断
 */
public record AdbSanitizedOutput(String stdout, String stderr, boolean truncated) {
}
