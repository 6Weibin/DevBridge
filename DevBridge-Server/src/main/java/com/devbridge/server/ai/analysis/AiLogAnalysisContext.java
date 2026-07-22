package com.devbridge.server.ai.analysis;

/**
 * AI 日志分析上下文摘要。
 *
 * <p>by AI.Coding</p>
 *
 * @param platform 平台
 * @param device 设备摘要
 * @param logRange 日志时间范围
 * @param logCount 实际发送日志行数
 * @param truncated 是否截断
 */
public record AiLogAnalysisContext(String platform, String device, String logRange, int logCount, boolean truncated) {
}
