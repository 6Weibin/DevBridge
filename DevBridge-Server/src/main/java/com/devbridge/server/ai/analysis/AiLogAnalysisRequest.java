package com.devbridge.server.ai.analysis;

import com.devbridge.server.ai.conversation.AiDeviceContext;
import java.util.List;

/**
 * AI 日志分析请求。
 *
 * <p>by AI.Coding</p>
 *
 * @param question 用户问题
 * @param deviceContext 当前设备上下文
 * @param logs 日志快照
 * @param limits 上下文限制
 */
public record AiLogAnalysisRequest(
        String question,
        AiDeviceContext deviceContext,
        List<AiLogLine> logs,
        AiLogAnalysisLimits limits) {
}
