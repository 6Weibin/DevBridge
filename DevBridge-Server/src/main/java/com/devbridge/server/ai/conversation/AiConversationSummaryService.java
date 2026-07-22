package com.devbridge.server.ai.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 对话与任务摘要服务，从较早历史中确定性提取用户约束、对话结论和工具结果。
 *
 * <p>摘要纯本地生成，不增加模型调用；来源范围和摘要校验值用于追溯及确定是否需要重建。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AiConversationSummaryService {

    private static final int SUMMARY_SCHEMA_VERSION = 1;
    private static final int RECENT_RAW_MESSAGES = 40;
    private static final int SUMMARY_BATCH_SIZE = 20;
    private static final int MAX_CONSTRAINT_LINES = 20;
    private static final int MAX_DIALOGUE_LINES = 30;
    private static final int MAX_TASK_LINES = 20;
    private static final int USER_SNIPPET_LENGTH = 260;
    private static final int ASSISTANT_SNIPPET_LENGTH = 220;
    private static final int TOOL_SNIPPET_LENGTH = 180;
    private static final List<String> CONSTRAINT_KEYWORDS = List.of(
            "必须", "不要", "不能", "需要", "请", "格式", "限制", "确认", "授权", "只允许",
            "must", "do not", "don't", "never", "require", "format", "confirm", "permission");

    /**
     * 根据当前消息列表生成或复用摘要；只汇总已离开最近消息窗口的完整批次。
     *
     * @param messages 会话完整消息
     * @param existing 已有摘要，可空
     * @return 当前有效摘要
     */
    public ConversationSummarySnapshot summarize(
            List<JsonNode> messages,
            ConversationSummarySnapshot existing) {
        List<JsonNode> values = messages == null ? List.of() : messages;
        int sourceCount = summarizedSourceCount(values.size());
        if (sourceCount == 0) {
            return ConversationSummarySnapshot.empty();
        }
        List<JsonNode> source = values.subList(0, sourceCount);
        String digest = sourceDigest(source);
        if (existing != null && existing.matches(sourceCount, digest)) {
            return existing;
        }
        SummaryLines lines = collectLines(source);
        long version = existing == null ? 1 : Math.max(1, existing.version() + 1);
        return new ConversationSummarySnapshot(
                SUMMARY_SCHEMA_VERSION,
                version,
                sourceCount,
                messageId(source.get(0)),
                messageId(source.get(source.size() - 1)),
                digest,
                conversationSummary(lines),
                taskSummary(lines),
                Instant.now().toEpochMilli());
    }

    /**
     * 每累计一个完整批次才扩大摘要来源，减少每轮会话重复重建。
     */
    private int summarizedSourceCount(int messageCount) {
        int olderMessages = Math.max(0, messageCount - RECENT_RAW_MESSAGES);
        return olderMessages / SUMMARY_BATCH_SIZE * SUMMARY_BATCH_SIZE;
    }

    /**
     * 扫描来源消息并分别收集约束、对话和任务工具行。
     */
    private SummaryLines collectLines(List<JsonNode> source) {
        Deque<String> constraints = new ArrayDeque<>();
        Deque<String> dialogue = new ArrayDeque<>();
        Deque<String> tasks = new ArrayDeque<>();
        for (JsonNode message : source) {
            String id = messageId(message);
            String role = message.path("role").asText("");
            String kind = message.path("kind").asText("text");
            String content = normalized(messageContent(message, USER_SNIPPET_LENGTH + 1));
            if ("text".equals(kind) && !message.path("error").asBoolean(false)) {
                collectTextLine(id, role, content, constraints, dialogue);
            }
            collectToolLines(id, message, tasks);
        }
        return new SummaryLines(List.copyOf(constraints), List.copyOf(dialogue), List.copyOf(tasks));
    }

    /**
     * 收集普通对话行，并额外保留用户明确约束。
     */
    private void collectTextLine(
            String id,
            String role,
            String content,
            Deque<String> constraints,
            Deque<String> dialogue) {
        if (!StringUtils.hasText(content) || !("user".equals(role) || "assistant".equals(role))) {
            return;
        }
        int maxLength = "user".equals(role) ? USER_SNIPPET_LENGTH : ASSISTANT_SNIPPET_LENGTH;
        String line = "[消息 " + id + "][" + ("user".equals(role) ? "用户" : "AI") + "] "
                + limit(content, maxLength);
        addBounded(dialogue, line, MAX_DIALOGUE_LINES);
        if ("user".equals(role) && isConstraint(content)) {
            addBounded(constraints, "[消息 " + id + "] " + limit(content, USER_SNIPPET_LENGTH),
                    MAX_CONSTRAINT_LINES);
        }
    }

    /**
     * 收集独立工具消息和过程卡片中的工具结果，不保存完整 stdout/stderr。
     */
    private void collectToolLines(String messageId, JsonNode message, Deque<String> tasks) {
        JsonNode direct = message.path("toolResult");
        if (direct.isObject()) {
            addBounded(tasks, toolLine(messageId, direct), MAX_TASK_LINES);
        }
        JsonNode entries = message.path("process").path("entries");
        if (!entries.isArray()) {
            return;
        }
        for (JsonNode entry : entries) {
            JsonNode result = entry.path("toolResult");
            if (result.isObject()) {
                String entryId = entry.path("id").asText(messageId);
                addBounded(tasks, toolLine(messageId + "/" + entryId, result), MAX_TASK_LINES);
            }
        }
    }

    /**
     * 把工具结果压缩为标题、命令、状态和有界结果摘要。
     */
    private String toolLine(String sourceId, JsonNode result) {
        String title = firstText(result, "toolTitle", "工具");
        String status = firstText(result, "status", "UNKNOWN");
        String command = firstText(result, "commandSummary", "");
        String outcome = firstText(result, "message", firstText(result, "errorCode", ""));
        if (!StringUtils.hasText(outcome)) {
            outcome = firstText(result, "stdout", firstText(result, "stderr", ""));
        }
        return "[消息 " + sourceId + "][不可信工具证据][" + limit(title, 80) + "] 状态=" + limit(status, 40)
                + (StringUtils.hasText(command) ? "，命令=" + limit(normalized(command), TOOL_SNIPPET_LENGTH) : "")
                + (StringUtils.hasText(outcome) ? "，结果=" + limit(normalized(outcome), TOOL_SNIPPET_LENGTH) : "");
    }

    /**
     * 构造对话摘要正文，用户约束层始终位于历史问答层之前。
     */
    private String conversationSummary(SummaryLines lines) {
        StringBuilder summary = new StringBuilder();
        appendSection(summary, "长期用户约束", lines.constraints());
        appendSection(summary, "较早对话要点", lines.dialogue());
        return summary.toString().trim();
    }

    /**
     * 构造任务和工具结果摘要。
     */
    private String taskSummary(SummaryLines lines) {
        StringBuilder summary = new StringBuilder();
        appendSection(summary, "较早任务与工具结果", lines.tasks());
        return summary.toString().trim();
    }

    /**
     * 对摘要来源生成 SHA-256，检测已摘要前缀内容是否发生变化。
     */
    private String sourceDigest(List<JsonNode> source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (JsonNode message : source) {
                updateDigest(digest, messageId(message));
                updateDigest(digest, message.path("role").asText(""));
                updateDigest(digest, message.path("kind").asText("text"));
                updateDigest(digest, message.path("error").asText("false"));
                updateMessageContentDigest(digest, message);
                updateDigest(digest, message.path("toolResult").toString());
                updateDigest(digest, message.path("process").path("entries").toString());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    /**
     * 判断用户文本是否包含需要长期保留的明确约束。
     */
    private boolean isConstraint(String content) {
        String lower = content.toLowerCase();
        return CONSTRAINT_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * 追加非空摘要分层。
     */
    private void appendSection(StringBuilder target, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append("\n\n");
        }
        target.append(title).append("：\n").append(String.join("\n", lines));
    }

    /**
     * 向固定大小队列追加最新行。
     */
    private void addBounded(Deque<String> values, String value, int maxSize) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        values.addLast(value);
        while (values.size() > maxSize) {
            values.removeFirst();
        }
    }

    /**
     * 获取 JSON 字段，空值使用回退文本。
     */
    private String firstText(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return StringUtils.hasText(value) ? value : fallback;
    }

    /**
     * 提取稳定消息 ID。
     */
    private String messageId(JsonNode message) {
        return message.path("id").asText("unknown");
    }

    /**
     * 有界读取普通正文或分段正文，摘要只需要短文本，不复制完整长回复。
     */
    private String messageContent(JsonNode message, int maxLength) {
        JsonNode segments = message.path("contentSegments");
        if (!segments.isArray() || segments.isEmpty()) {
            return limit(message.path("content").asText(""), maxLength);
        }
        StringBuilder content = new StringBuilder(Math.min(maxLength, 512));
        for (JsonNode segment : segments) {
            int remaining = maxLength - content.length();
            if (remaining <= 0) {
                break;
            }
            String value = segment.asText("");
            content.append(value, 0, Math.min(value.length(), remaining));
        }
        return content.toString();
    }

    /**
     * 分段更新摘要来源校验值，避免为了计算摘要版本而合并完整正文。
     */
    private void updateMessageContentDigest(MessageDigest digest, JsonNode message) {
        JsonNode segments = message.path("contentSegments");
        if (!segments.isArray() || segments.isEmpty()) {
            updateDigest(digest, message.path("content").asText(""));
            return;
        }
        for (JsonNode segment : segments) {
            digest.update(segment.asText("").getBytes(StandardCharsets.UTF_8));
        }
        digest.update((byte) 0);
    }

    /**
     * 将多行和连续空白压缩为适合摘要的一行。
     */
    private String normalized(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    /**
     * 限制摘要单行长度，完整正文仍保留在 Conversation Store。
     */
    private String limit(String value, int maxLength) {
        String text = value == null ? "" : value;
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    /**
     * 向摘要校验器追加带分隔符文本，避免字段拼接歧义。
     */
    private void updateDigest(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    /** 分层摘要临时行。by AI.Coding */
    private record SummaryLines(
            List<String> constraints,
            List<String> dialogue,
            List<String> tasks) {
    }

    /**
     * 持久化摘要快照，来源范围和校验值用于追溯与重建判断。
     *
     * <p>by AI.Coding</p>
     */
    public record ConversationSummarySnapshot(
            int schemaVersion,
            long version,
            int sourceMessageCount,
            String sourceFirstMessageId,
            String sourceLastMessageId,
            String sourceDigest,
            String conversationSummary,
            String taskSummary,
            long generatedAt) {

        /**
         * 判断摘要是否覆盖相同来源前缀。
         */
        public boolean matches(int messageCount, String digest) {
            return schemaVersion == SUMMARY_SCHEMA_VERSION
                    && sourceMessageCount == messageCount
                    && sourceDigest.equals(digest);
        }

        /**
         * 创建无摘要快照，兼容旧会话文件和短会话。
         */
        public static ConversationSummarySnapshot empty() {
            return new ConversationSummarySnapshot(
                    SUMMARY_SCHEMA_VERSION, 0, 0, "", "", "", "", "", 0);
        }

        /**
         * 规范化旧文件缺失字段。
         */
        public ConversationSummarySnapshot {
            sourceFirstMessageId = sourceFirstMessageId == null ? "" : sourceFirstMessageId;
            sourceLastMessageId = sourceLastMessageId == null ? "" : sourceLastMessageId;
            sourceDigest = sourceDigest == null ? "" : sourceDigest;
            conversationSummary = conversationSummary == null ? "" : conversationSummary;
            taskSummary = taskSummary == null ? "" : taskSummary;
        }
    }
}
