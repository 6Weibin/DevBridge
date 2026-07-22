package com.devbridge.server.ai.localshell.model;

import java.time.Duration;

/**
 * 创建 Local Shell 确认令牌的请求。
 *
 * <p>by AI.Coding</p>
 *
 * @param plan 命令计划
 * @param assessment 风险评估
 * @param ttl 有效期
 */
public record LocalShellConfirmationRequest(
        LocalShellCommandPlan plan,
        LocalShellRiskAssessment assessment,
        Duration ttl) {
}
