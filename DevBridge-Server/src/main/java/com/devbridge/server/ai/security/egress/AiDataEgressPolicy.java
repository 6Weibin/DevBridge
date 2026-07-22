package com.devbridge.server.ai.security.egress;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 模型数据外发领域模型，集中定义分类、数据摘要、调用上下文和决策结果。
 *
 * <p>这些类型只描述外发事实，不依赖 Spring AI、前端或具体 Provider 实现。</p>
 *
 * <p>by AI.Coding</p>
 */
public final class AiDataEgressPolicy {

    private AiDataEgressPolicy() {
    }

    /**
     * 模型输入数据分类。
     */
    public enum Classification {
        ALLOWED,
        CONFIRMATION_REQUIRED,
        LOCAL_MODEL_ONLY,
        PROHIBITED
    }

    /**
     * 可进入模型上下文的数据类型。
     */
    public enum DataType {
        USER_MESSAGE,
        DEVICE_CONTEXT,
        DEVICE_LOG,
        DEVICE_IDENTIFIER,
        APPLICATION_LIST,
        TOOL_OUTPUT,
        LOCAL_COMMAND_OUTPUT,
        FILE_CONTENT,
        SCREENSHOT,
        LOCATION,
        SOURCE_CODE,
        CREDENTIAL
    }

    /**
     * 模型数据外发决策。
     */
    public enum Decision {
        ALLOW,
        CONFIRM,
        BLOCK
    }

    /**
     * 单项模型输入数据摘要，不保存或审计原始正文。
     *
     * @param dataType 数据类型
     * @param classification 数据分类
     * @param sourceId 脱敏来源标识
     * @param byteCount UTF-8 字节数
     * @param masked 是否已执行脱敏
     * @param contentDigest 原始发送内容 SHA-256 摘要
     */
    public record Item(
            DataType dataType,
            Classification classification,
            String sourceId,
            long byteCount,
            boolean masked,
            String contentDigest) {

        /**
         * 从待发送文本生成只含摘要的分类项。
         *
         * @param dataType 数据类型
         * @param classification 数据分类
         * @param sourceId 脱敏来源标识
         * @param masked 是否已脱敏
         * @param content 实际待发送文本
         * @return 数据摘要项
         */
        public static Item fromText(
                DataType dataType,
                Classification classification,
                String sourceId,
                boolean masked,
                String content) {
            byte[] bytes = safe(content).getBytes(StandardCharsets.UTF_8);
            return new Item(dataType, classification, safe(sourceId), bytes.length, masked, digest(bytes));
        }
    }

    /**
     * 单次模型调用的数据外发上下文。
     *
     * @param taskId Agent 任务标识，兼容旧对话时可空
     * @param conversationId 会话标识
     * @param stepId 步骤标识
     * @param modelCallId 模型调用标识
     * @param purpose 外发目的摘要
     * @param items 待发送数据摘要项
     * @param confirmationId 已接受的数据外发确认标识，可空
     */
    public record Context(
            String taskId,
            String conversationId,
            String stepId,
            String modelCallId,
            String purpose,
            List<Item> items,
            String confirmationId) {

        /**
         * 固化数据项副本，避免异步 Provider 调用期间被修改。
         */
        public Context {
            items = items == null ? List.of() : List.copyOf(items);
        }

        /**
         * 为普通用户文本创建兼容上下文。
         *
         * @param userPrompt 用户文本
         * @return 仅包含允许数据的上下文
         */
        public static Context publicUserText(String userPrompt) {
            return new Context(
                    "",
                    "legacy-conversation",
                    "",
                    "",
                    "普通技术对话",
                    List.of(Item.fromText(
                            DataType.USER_MESSAGE,
                            Classification.ALLOWED,
                            "user-message",
                            false,
                            userPrompt)),
                    "");
        }
    }

    /**
     * 数据外发策略评估结果，正文不进入结果和审计。
     *
     * @param decision 最终决策
     * @param provider Provider
     * @param model 模型
     * @param dataTypes 数据类型集合
     * @param totalBytes 总字节数
     * @param maskedTypes 已脱敏的数据类型
     * @param dataDigest 外发集合摘要
     * @param reason 决策原因
     */
    public record Assessment(
            Decision decision,
            String provider,
            String model,
            List<String> dataTypes,
            long totalBytes,
            List<String> maskedTypes,
            String dataDigest,
            String reason) {

        /**
         * 固化摘要集合，防止审计和确认展示过程中被修改。
         */
        public Assessment {
            dataTypes = dataTypes == null ? List.of() : List.copyOf(dataTypes);
            maskedTypes = maskedTypes == null ? List.of() : List.copyOf(maskedTypes);
        }
    }

    /**
     * 计算发送内容摘要。
     *
     * @param bytes 待摘要字节
     * @return SHA-256 十六进制摘要
     */
    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    /**
     * 将空文本规范化为空字符串。
     *
     * @param value 原始文本
     * @return 非空文本
     */
    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
