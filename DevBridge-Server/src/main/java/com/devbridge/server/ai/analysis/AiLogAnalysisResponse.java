package com.devbridge.server.ai.analysis;

import java.util.List;

/**
 * AI 日志分析响应。
 *
 * <p>by AI.Coding</p>
 *
 * @param summary 问题摘要
 * @param evidence 关键证据
 * @param cause 原因判断
 * @param actions 建议动作
 * @param confidence 置信度
 * @param context 上下文摘要
 */
public record AiLogAnalysisResponse(
        String summary,
        List<String> evidence,
        String cause,
        List<String> actions,
        String confidence,
        AiLogAnalysisContext context) {
}
