package com.devbridge.server.ai.mcp.model;

/**
 * 敏感操作确认令牌状态，用于审计和错误分支判断。
 *
 * <p>by AI.Coding</p>
 */
public enum AdbConfirmationStatus {
    PENDING,
    APPROVED,
    CANCELED,
    EXPIRED,
    USED,
    MISMATCH
}
