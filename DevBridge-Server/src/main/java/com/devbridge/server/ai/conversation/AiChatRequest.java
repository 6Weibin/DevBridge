package com.devbridge.server.ai.conversation;

import java.util.List;

/**
 * 普通 AI 对话请求。
 *
 * <p>by AI.Coding</p>
 *
 * @param message 用户问题
 * @param deviceContext 当前设备上下文
 * @param conversationId 前端对话 ID，用于绑定工具确认令牌
 * @param history 旧客户端和确认恢复使用的兼容历史；普通前端请求为空，后端从 Conversation Store 构造上下文
 * @param taskId 后端确认续跑使用的 Agent Task ID，外部普通请求为空
 * @param confirmationToken 后端确认续跑使用的原工具调用身份，外部普通请求为空
 * @param summaryContext 确认恢复使用的较早历史摘要，普通前端请求为空
 * @param ragContext 确认恢复使用的本地知识引用，普通前端请求为空
 * @param webSearchEnabled 当前请求是否允许模型使用联网工具，缺省为关闭
 */
public record AiChatRequest(
        String message,
        AiDeviceContext deviceContext,
        String conversationId,
        List<AiChatHistoryMessage> history,
        String taskId,
        String confirmationToken,
        SummaryContext summaryContext,
        RagContext ragContext,
        boolean webSearchEnabled) {

    /**
     * 兼容旧前端请求，历史和任务标识为空时按新的普通对话处理。
     */
    public AiChatRequest {
        if (history == null) {
            history = List.of();
        }
        if (taskId == null) {
            taskId = "";
        }
        if (confirmationToken == null) {
            confirmationToken = "";
        }
        if (summaryContext == null) {
            summaryContext = SummaryContext.empty();
        }
        if (ragContext == null) {
            ragContext = RagContext.empty();
        }
    }

    /**
     * 兼容尚未传入 Agent Task ID 的 Java 调用方。
     *
     * @param message 用户问题
     * @param deviceContext 设备上下文
     * @param conversationId 会话标识
     * @param history 对话历史
     */
    public AiChatRequest(
            String message,
            AiDeviceContext deviceContext,
            String conversationId,
            List<AiChatHistoryMessage> history) {
        this(message, deviceContext, conversationId, history, "", "", SummaryContext.empty(), RagContext.empty(), false);
    }

    /**
     * 兼容只传入 Agent Task ID 的调用方。
     *
     * @param message 用户问题
     * @param deviceContext 设备上下文
     * @param conversationId 会话标识
     * @param history 对话历史
     * @param taskId Agent Task ID
     */
    public AiChatRequest(
            String message,
            AiDeviceContext deviceContext,
            String conversationId,
            List<AiChatHistoryMessage> history,
            String taskId) {
        this(message, deviceContext, conversationId, history, taskId, "", SummaryContext.empty(), RagContext.empty(), false);
    }

    /**
     * 兼容尚未携带摘要上下文的确认恢复调用方。
     *
     * @param message 用户问题
     * @param deviceContext 设备上下文
     * @param conversationId 会话标识
     * @param history 对话历史
     * @param taskId Agent Task ID
     * @param confirmationToken 确认身份
     */
    public AiChatRequest(
            String message,
            AiDeviceContext deviceContext,
            String conversationId,
            List<AiChatHistoryMessage> history,
            String taskId,
            String confirmationToken) {
        this(message, deviceContext, conversationId, history,
                taskId, confirmationToken, SummaryContext.empty(), RagContext.empty(), false);
    }

    /**
     * 兼容尚未携带 RAG 上下文的确认恢复调用方。
     *
     * @param message 用户问题
     * @param deviceContext 设备上下文
     * @param conversationId 会话标识
     * @param history 对话历史
     * @param taskId Agent Task ID
     * @param confirmationToken 确认身份
     * @param summaryContext 较早摘要
     */
    public AiChatRequest(
            String message,
            AiDeviceContext deviceContext,
            String conversationId,
            List<AiChatHistoryMessage> history,
            String taskId,
            String confirmationToken,
            SummaryContext summaryContext) {
        this(message, deviceContext, conversationId, history, taskId, confirmationToken,
                summaryContext, RagContext.empty(), false);
    }

    /**
     * 兼容尚未传入请求级联网开关的完整 Java 调用方。
     *
     * @param message 用户问题
     * @param deviceContext 设备上下文
     * @param conversationId 会话标识
     * @param history 对话历史
     * @param taskId Agent Task ID
     * @param confirmationToken 确认身份
     * @param summaryContext 较早摘要
     * @param ragContext RAG 上下文
     */
    public AiChatRequest(
            String message,
            AiDeviceContext deviceContext,
            String conversationId,
            List<AiChatHistoryMessage> history,
            String taskId,
            String confirmationToken,
            SummaryContext summaryContext,
            RagContext ragContext) {
        this(message, deviceContext, conversationId, history, taskId, confirmationToken,
                summaryContext, ragContext, false);
    }

    /**
     * 确认恢复使用的有界摘要上下文。
     *
     * <p>by AI.Coding</p>
     *
     * @param content 摘要正文
     * @param version 摘要版本
     * @param sourceMessageCount 来源消息数量
     */
    public record SummaryContext(String content, long version, int sourceMessageCount) {

        /** 兼容空字段并创建不可变摘要。 */
        public SummaryContext {
            content = content == null ? "" : content;
        }

        /** 创建空摘要。 */
        public static SummaryContext empty() {
            return new SummaryContext("", 0, 0);
        }
    }

    /**
     * 确认恢复使用的有界 RAG 上下文。
     *
     * <p>by AI.Coding</p>
     *
     * @param content 不可信知识证据正文
     * @param citations 引用标识
     */
    public record RagContext(String content, List<String> citations) {

        /** 兼容空字段并固化引用。 */
        public RagContext {
            content = content == null ? "" : content;
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        /** 创建空 RAG 上下文。 */
        public static RagContext empty() {
            return new RagContext("", List.of());
        }
    }
}
