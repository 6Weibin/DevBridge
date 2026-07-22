package com.devbridge.server.ai.mcp.model;

import java.time.Instant;

/**
 * 内存中的确认令牌记录，只保存 hash 和绑定摘要，不保存令牌明文。
 *
 * <p>by AI.Coding</p>
 *
 * @param tokenHash 令牌 hash
 * @param conversationId 对话 ID
 * @param deviceSerialHash 设备序列号 hash
 * @param adbArgsHash ADB 参数 hash
 * @param riskLevel 风险级别
 * @param expiresAt 过期时间
 * @param status 当前状态
 */
public record AdbConfirmationEntry(
        String tokenHash,
        String conversationId,
        String deviceSerialHash,
        String adbArgsHash,
        AdbRiskLevel riskLevel,
        Instant expiresAt,
        AdbConfirmationStatus status) {
}
