package com.devbridge.server.ai.localshell.model;

import com.devbridge.server.ai.mcp.model.AdbConfirmationStatus;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;

/**
 * Local Shell 工具审计事件，只记录摘要，不记录完整输出。
 *
 * <p>by AI.Coding</p>
 *
 * @param toolName 工具名
 * @param commandSummary 命令摘要
 * @param workingDirectory 工作目录
 * @param riskLevel 风险级别
 * @param confirmationStatus 确认状态
 * @param durationMillis 耗时
 * @param exitCode 退出码
 * @param success 是否成功
 * @param error 错误摘要
 */
public record LocalShellAuditEvent(
        String toolName,
        String commandSummary,
        String workingDirectory,
        AdbRiskLevel riskLevel,
        AdbConfirmationStatus confirmationStatus,
        long durationMillis,
        Integer exitCode,
        boolean success,
        String error) {
}
