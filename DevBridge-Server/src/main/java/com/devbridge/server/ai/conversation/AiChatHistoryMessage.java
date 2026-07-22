package com.devbridge.server.ai.conversation;

/**
 * AI 对话历史消息，只承载当前窗口中的普通用户/助手文本。
 *
 * <p>by AI.Coding</p>
 *
 * @param role 消息角色，允许 user 或 assistant
 * @param content 消息内容
 */
public record AiChatHistoryMessage(String role, String content) {
}
