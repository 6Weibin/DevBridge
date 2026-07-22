package com.devbridge.server.ai.tool;

/**
 * AI 工具调用范围，本期只预留日志分析范围，后续 Agent 编排按范围扩展。
 *
 * <p>by AI.Coding</p>
 */
public enum AiToolScope {
    NONE,
    LOG_ANALYSIS,
    ADB_DEVICE_MANAGEMENT,
    GENERAL_ASSISTANT,
    LOCAL_SHELL,
    LOCAL_DEVELOPMENT
}
