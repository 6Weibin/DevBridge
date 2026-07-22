package com.devbridge.server.ai.mcp.model;

/**
 * ADB MCP 工具审计事件，只包含脱敏摘要，不记录完整日志正文和密钥。
 *
 * <p>by AI.Coding</p>
 *
 * @param toolName 工具名称
 * @param deviceSerialMasked 脱敏设备序列号
 * @param adbArgsSummary 参数摘要
 * @param riskLevel 风险级别
 * @param confirmationStatus 确认状态
 * @param durationMillis 耗时毫秒
 * @param exitCode 退出码
 * @param success 是否成功
 * @param errorSummary 错误摘要
 */
public record AdbToolAuditEvent(
        String toolName,
        String deviceSerialMasked,
        String adbArgsSummary,
        AdbRiskLevel riskLevel,
        AdbConfirmationStatus confirmationStatus,
        long durationMillis,
        Integer exitCode,
        boolean success,
        String errorSummary) {
}
