package com.devbridge.server.ai.analysis;

/**
 * AI 日志上下文行。
 *
 * <p>by AI.Coding</p>
 *
 * @param timestamp 时间
 * @param level 级别
 * @param pid 进程 ID
 * @param tag 日志 Tag
 * @param message 日志消息
 */
public record AiLogLine(String timestamp, String level, String pid, String tag, String message) {
}
