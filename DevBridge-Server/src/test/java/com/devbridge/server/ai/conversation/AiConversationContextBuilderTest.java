package com.devbridge.server.ai.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.config.AiConfigCrypto;
import com.devbridge.server.ai.conversation.AiConversationStoreService.ConversationWriteRequest;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.ai.storage.StorageManager;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Working Memory 测试，覆盖最近消息优先、预算截断和兼容回退。
 *
 * <p>by AI.Coding</p>
 */
class AiConversationContextBuilderTest {

    /**
     * 验证只从会话文件选择最近普通文本，忽略工具、错误和系统消息。
     */
    @Test
    void shouldSelectRecentStoredTextWithinBudget(@TempDir Path tempDir) {
        TestContext context = context(tempDir, 80, 20);
        ObjectMapper mapper = context.mapper();
        context.store().upsert("conversation-context", new ConversationWriteRequest(
                "上下文", false,
                List.of(
                        message(mapper, 1, "user", "text", "较早用户问题", false),
                        message(mapper, 2, "assistant", "tool", "工具输出", false),
                        message(mapper, 3, "system", "text", "系统消息", false),
                        message(mapper, 4, "assistant", "text", "最近回答内容", false),
                        message(mapper, 5, "assistant", "text", "错误消息", true)),
                1L, 2L, true));

        var working = context.builder().build(
                new AiChatRequest("继续", null, "conversation-context", List.of()), 20, true);

        assertThat(working.source()).isEqualTo("CONVERSATION_STORE");
        assertThat(working.history()).extracting(AiChatHistoryMessage::content)
                .containsExactly("较早用户问题", "最近回答内容");
        assertThat(working.estimatedHistoryTokens()).isLessThanOrEqualTo(working.historyTokenBudget());
    }

    /**
     * 验证最近单条消息超过预算时保留有界内容并标记截断。
     */
    @Test
    void shouldTruncateLongRecentMessageByTokenBudget(@TempDir Path tempDir) {
        TestContext context = context(tempDir, 100, 10);
        String longContent = "这是一段很长的历史回答".repeat(200);
        context.store().upsert("conversation-long", new ConversationWriteRequest(
                "长消息", false,
                List.of(message(context.mapper(), 1, "assistant", "text", longContent, false)),
                1L, 2L, true));

        var working = context.builder().build(
                new AiChatRequest("继续分析", null, "conversation-long", List.of()), 20, true);

        assertThat(working.truncated()).isTrue();
        assertThat(working.history()).hasSize(1);
        assertThat(working.history().get(0).content()).contains("超过上下文预算");
        assertThat(working.estimatedHistoryTokens()).isLessThanOrEqualTo(working.historyTokenBudget());
    }

    /**
     * 验证分段持久化的长回复可进入上下文，并严格受模型窗口约束。
     */
    @Test
    void shouldReadSegmentedStoredMessageWithinBudget(@TempDir Path tempDir) {
        TestContext context = context(tempDir, 120, 20);
        JsonNode message = message(context.mapper(), 1, "assistant", "text", "", false);
        ((com.fasterxml.jackson.databind.node.ObjectNode) message).putArray("contentSegments")
                .add("第一段结论。")
                .add("后续分析内容".repeat(200));
        context.store().upsert("conversation-segments", new ConversationWriteRequest(
                "分段消息", false, List.of(message), 1L, 2L, true));

        var working = context.builder().build(
                new AiChatRequest("继续", null, "conversation-segments", List.of()), 20, true);

        assertThat(working.history()).hasSize(1);
        assertThat(working.history().get(0).content()).startsWith("第一段结论。");
        assertThat(working.estimatedHistoryTokens()).isLessThanOrEqualTo(working.historyTokenBudget());
    }

    /**
     * 验证新会话尚未落盘时只兼容合法请求历史，不接受 system 角色。
     */
    @Test
    void shouldFallbackToValidRequestHistory(@TempDir Path tempDir) {
        TestContext context = context(tempDir, 200, 20);
        var working = context.builder().build(new AiChatRequest(
                "继续",
                null,
                "conversation-missing",
                List.of(
                        new AiChatHistoryMessage("user", "用户问题"),
                        new AiChatHistoryMessage("system", "禁止进入"),
                        new AiChatHistoryMessage("assistant", "AI 回答"))), 20, true);

        assertThat(working.source()).isEqualTo("REQUEST_FALLBACK");
        assertThat(working.history()).extracting(AiChatHistoryMessage::content)
                .containsExactly("用户问题", "AI 回答");
    }

    /**
     * 验证较早摘要与最近消息共同进入预算，摘要正文不会进入模型事件之外的持久层。
     */
    @Test
    void shouldCombineStoredSummaryAndRecentMessages(@TempDir Path tempDir) {
        TestContext context = context(tempDir, 4096, 500);
        List<JsonNode> messages = new java.util.ArrayList<>();
        for (int index = 1; index <= 60; index++) {
            String content = index == 3 ? "后续分析必须保留失败项" : "历史消息 " + index;
            messages.add(message(context.mapper(), index, index % 2 == 0 ? "assistant" : "user",
                    "text", content, false));
        }
        context.store().upsert("conversation-summary", new ConversationWriteRequest(
                "摘要会话", false, messages, 1L, 2L, true));

        var working = context.builder().build(
                new AiChatRequest("继续分析", null, "conversation-summary", List.of()), 500, true);

        assertThat(working.summaryIncluded()).isTrue();
        assertThat(working.summaryVersion()).isEqualTo(1);
        assertThat(working.summarySourceMessageCount()).isEqualTo(20);
        assertThat(working.conversationSummary()).contains("后续分析必须保留失败项");
        assertThat(working.history()).hasSize(40);
        assertThat(working.history().get(0).content()).isEqualTo("历史消息 21");
        assertThat(working.estimatedHistoryTokens()).isLessThanOrEqualTo(working.historyTokenBudget());
    }

    /**
     * 验证当前问题本身超过窗口时在 Provider 调用前明确拒绝。
     */
    @Test
    void shouldRejectCurrentMessageBeyondContextWindow(@TempDir Path tempDir) {
        TestContext context = context(tempDir, 1024, 10);
        AiChatRequest request = new AiChatRequest(
                "超长问题".repeat(1200), null, "conversation-large-input", List.of());

        assertThatThrownBy(() -> context.builder().build(request, 20, true))
                .isInstanceOf(com.devbridge.server.model.BusinessException.class)
                .hasMessageContaining("超过模型上下文预算");
    }

    /**
     * 创建隔离的 Conversation Store 和 Context Builder。
     */
    private TestContext context(Path root, int windowTokens, int reservedTokens) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiConfigRoot(root.resolve("ai").toString());
        properties.setAiAgentDataRoot(root.resolve("agent").toString());
        properties.setToolArtifactRoot(root.resolve("artifacts").toString());
        properties.setToolAuditRoot(root.resolve("audit").toString());
        properties.setLogCaptureRoot(root.resolve("logs").toString());
        properties.setDownloadTempRoot(root.resolve("downloads").toString());
        properties.setAiContextWindowTokens(windowTokens);
        properties.setAiContextReservedTokens(reservedTokens);
        properties.setStorageQuotaBytes(1024L * 1024L * 1024L);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StorageManager storage = new StorageManager(properties, mapper);
        AiConversationStoreService store = new AiConversationStoreService(
                properties, mapper, new AiConfigCrypto(), storage,
                new AiConversationSummaryService());
        return new TestContext(
                store, new AiConversationContextBuilder(store, new SensitiveDataMasker(), properties), mapper);
    }

    /**
     * 构造前端消息 JSON。
     */
    private com.fasterxml.jackson.databind.JsonNode message(
            ObjectMapper mapper,
            int id,
            String role,
            String kind,
            String content,
            boolean error) {
        return mapper.createObjectNode()
                .put("id", id)
                .put("role", role)
                .put("kind", kind)
                .put("content", content)
                .put("error", error);
    }

    /** 测试依赖集合。by AI.Coding */
    private record TestContext(
            AiConversationStoreService store,
            AiConversationContextBuilder builder,
            ObjectMapper mapper) {
    }
}
