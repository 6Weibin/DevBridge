package com.devbridge.server.ai.security.untrusted;

import java.util.List;

/**
 * 不可信内容领域模型，集中定义来源、封装请求和脱敏安全事件。
 *
 * <p>by AI.Coding</p>
 */
public final class AiUntrustedContent {

    private AiUntrustedContent() {
    }

    /**
     * 默认不可信的模型上下文来源类型。
     */
    public enum SourceType {
        DEVICE_LOG,
        FILE_CONTENT,
        WEB_CONTENT,
        SCREENSHOT_OCR,
        TOOL_OUTPUT,
        RAG_DOCUMENT,
        AGENT_RESULT
    }

    /**
     * 不可信内容封装请求，正文只用于本次 Prompt 组装。
     *
     * @param sourceType 来源类型
     * @param sourceId 脱敏来源标识
     * @param purpose 使用目的
     * @param contentRange 内容范围
     * @param content 不可信正文
     */
    public record Envelope(
            SourceType sourceType,
            String sourceId,
            String purpose,
            String contentRange,
            String content) {
    }

    /**
     * 提示注入安全事件，只保留来源、长度、摘要和命中特征。
     *
     * @param sourceType 来源类型
     * @param sourceId 脱敏来源标识
     * @param contentLength 内容字符数
     * @param contentDigest 内容 SHA-256 摘要
     * @param signals 命中的提示注入信号
     */
    public record SecurityEvent(
            SourceType sourceType,
            String sourceId,
            int contentLength,
            String contentDigest,
            List<String> signals) {

        /**
         * 固化信号集合，防止记录过程中被修改。
         */
        public SecurityEvent {
            signals = signals == null ? List.of() : List.copyOf(signals);
        }
    }
}
