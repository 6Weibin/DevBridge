package com.devbridge.server.ai.mcp.model;

/**
 * 前端确认或取消敏感操作时提交的对话绑定信息。
 *
 * <p>by AI.Coding</p>
 *
 * @param conversationId 对话 ID
 * @param requestId 请求 ID
 */
public record AdbConfirmationDecisionRequest(String conversationId, String requestId) {
}
