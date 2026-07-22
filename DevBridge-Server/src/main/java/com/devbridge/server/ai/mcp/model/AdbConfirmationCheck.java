package com.devbridge.server.ai.mcp.model;

/**
 * 校验确认令牌时使用的绑定信息。
 *
 * <p>by AI.Coding</p>
 *
 * @param conversationId 对话 ID
 * @param deviceSerial 设备序列号
 * @param adbArgsHash ADB 参数 hash
 * @param riskLevel 风险级别
 */
public record AdbConfirmationCheck(
        String conversationId,
        String deviceSerial,
        String adbArgsHash,
        AdbRiskLevel riskLevel) {
}
