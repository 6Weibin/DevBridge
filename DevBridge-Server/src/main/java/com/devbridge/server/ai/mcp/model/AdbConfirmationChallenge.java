package com.devbridge.server.ai.mcp.model;

import java.time.Instant;

/**
 * 敏感操作确认挑战，返回给前端和模型用于二次确认。
 *
 * <p>by AI.Coding</p>
 *
 * @param token 确认令牌
 * @param commandSummary 命令摘要
 * @param deviceSerialMasked 脱敏设备序列号
 * @param riskLevel 风险级别
 * @param reason 风险原因
 * @param impact 影响范围
 * @param expiresAt 过期时间
 */
public record AdbConfirmationChallenge(
        String token,
        String commandSummary,
        String deviceSerialMasked,
        AdbRiskLevel riskLevel,
        String reason,
        String impact,
        Instant expiresAt) {
}
