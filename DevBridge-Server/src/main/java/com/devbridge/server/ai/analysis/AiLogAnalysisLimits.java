package com.devbridge.server.ai.analysis;

/**
 * AI 日志分析上下文限制。
 *
 * <p>by AI.Coding</p>
 *
 * @param maxLines 最大日志行数
 * @param maxCharacters 最大字符数
 */
public record AiLogAnalysisLimits(Integer maxLines, Integer maxCharacters) {
}
