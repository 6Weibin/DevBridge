package com.devbridge.server.ai.conversation;

/**
 * AI 普通对话流式事件。
 *
 * <p>by AI.Coding</p>
 *
 * @param type 事件类型：chunk、done、error
 * @param content 增量内容或错误提示
 * @param code 错误码，正常事件为空
 * @param detail 诊断详情，正常事件为空
 */
public record AiChatStreamEvent(String type, String content, String code, String detail) {

    /**
     * 兼容普通流式事件构造；正常 chunk/done 不需要携带诊断详情。
     *
     * @param type 事件类型
     * @param content 事件内容
     * @param code 错误码
     */
    public AiChatStreamEvent(String type, String content, String code) {
        this(type, content, code, "");
    }
}
