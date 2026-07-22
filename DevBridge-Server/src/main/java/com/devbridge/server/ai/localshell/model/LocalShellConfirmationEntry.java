package com.devbridge.server.ai.localshell.model;

import com.devbridge.server.ai.mcp.model.AdbConfirmationStatus;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import java.time.Instant;

/**
 * Local Shell 确认令牌绑定记录，内存中不保存令牌明文。
 *
 * <p>by AI.Coding</p>
 *
 * @param tokenHash 令牌哈希
 * @param conversationId 对话 ID
 * @param commandHash 命令绑定哈希
 * @param workingDirectoryHash 工作目录哈希
 * @param riskLevel 风险级别
 * @param expiresAt 过期时间
 * @param status 确认状态
 */
public record LocalShellConfirmationEntry(
        String tokenHash,
        String conversationId,
        String commandHash,
        String workingDirectoryHash,
        AdbRiskLevel riskLevel,
        Instant expiresAt,
        AdbConfirmationStatus status) {
}
