package com.devbridge.server.ai.localshell.model;

import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import java.time.Instant;

/**
 * Local Shell 敏感命令确认挑战，返回给前端展示。
 *
 * <p>by AI.Coding</p>
 *
 * @param token 确认令牌
 * @param commandSummary 命令摘要
 * @param workingDirectory 工作目录
 * @param riskLevel 风险级别
 * @param reason 风险原因
 * @param impact 影响范围
 * @param expiresAt 过期时间
 */
public record LocalShellConfirmationChallenge(
        String token,
        String commandSummary,
        String workingDirectory,
        AdbRiskLevel riskLevel,
        String reason,
        String impact,
        Instant expiresAt) {
}
