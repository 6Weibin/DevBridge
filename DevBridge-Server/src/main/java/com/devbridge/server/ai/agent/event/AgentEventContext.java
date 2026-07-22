package com.devbridge.server.ai.agent.event;

/**
 * Agent Event 关联标识，确保事件可唯一归属任务执行单元。
 *
 * <p>by AI.Coding</p>
 *
 * @param conversationId 会话标识
 * @param turnId Turn 标识，可空
 * @param stepId 步骤标识，可空
 * @param toolCallId 工具调用标识，可空
 * @param confirmationId 确认标识，可空
 * @param modelCallId 模型调用标识，可空
 * @param taskVersion 任务版本
 */
public record AgentEventContext(
        String conversationId,
        String turnId,
        String stepId,
        String toolCallId,
        String confirmationId,
        String modelCallId,
        long taskVersion) {
}
