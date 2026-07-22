package com.devbridge.server.ai.mcp.model;

import java.time.Duration;

/**
 * 创建敏感操作确认令牌的请求。
 *
 * <p>by AI.Coding</p>
 *
 * @param plan ADB 命令计划
 * @param assessment 风险评估
 * @param ttl 令牌有效期
 */
public record AdbConfirmationRequest(
        AdbCommandPlan plan,
        AdbRiskAssessment assessment,
        Duration ttl) {
}
