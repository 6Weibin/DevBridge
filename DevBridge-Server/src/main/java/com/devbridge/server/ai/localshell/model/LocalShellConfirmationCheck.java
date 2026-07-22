package com.devbridge.server.ai.localshell.model;

import com.devbridge.server.ai.mcp.model.AdbRiskLevel;

/**
 * Local Shell 确认令牌校验信息。
 *
 * <p>by AI.Coding</p>
 *
 * @param conversationId 对话 ID
 * @param commandHash 命令哈希
 * @param workingDirectoryHash 工作目录哈希
 * @param riskLevel 风险级别
 */
public record LocalShellConfirmationCheck(
        String conversationId,
        String commandHash,
        String workingDirectoryHash,
        AdbRiskLevel riskLevel) {
}
