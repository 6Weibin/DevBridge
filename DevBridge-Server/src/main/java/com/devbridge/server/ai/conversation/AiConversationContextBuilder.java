package com.devbridge.server.ai.conversation;

import com.devbridge.server.ai.conversation.AiChatRequest.SummaryContext;
import com.devbridge.server.ai.conversation.AiChatRequest.RagContext;
import com.devbridge.server.ai.conversation.AiConversationStoreService.ConversationContextSnapshot;
import com.devbridge.server.ai.conversation.AiConversationSummaryService.ConversationSummarySnapshot;
import com.devbridge.server.ai.config.AiModelCapabilityRegistry.ModelLimits;
import com.devbridge.server.ai.config.AiRuntimeConfig;
import com.devbridge.server.ai.rag.AiRagBoundary;
import com.devbridge.server.ai.rag.AiRagBoundary.RagCitation;
import com.devbridge.server.ai.rag.AiRagBoundary.SearchRequest;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 对话 Working Memory 构造器，按 Token 预算从本地会话文件选择最近有效文本。
 *
 * <p>该组件只维护单次模型请求所需的内存对象，读取会话文件中的持久摘要但不创建第二份历史或 RAG 索引。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AiConversationContextBuilder {

    private static final int STORE_MESSAGE_LIMIT = 200;
    private static final int MESSAGE_OVERHEAD_TOKENS = 6;
    private static final int CURRENT_MESSAGE_OVERHEAD_TOKENS = 16;
    private static final String TRUNCATED_NOTICE = "\n[历史消息超过上下文预算，已截断]";
    private static final String SUMMARY_TRUNCATED_NOTICE = "\n[历史摘要超过上下文预算，已截断]";

    private final AiConversationStoreService conversationStore;
    private final SensitiveDataMasker sensitiveDataMasker;
    private final AiRagBoundary ragBoundary;
    private final int contextWindowTokens;
    private final int reservedTokens;

    /**
     * 注入会话文件、脱敏器和上下文窗口配置。
     *
     * @param conversationStore 历史聊天文件服务
     * @param sensitiveDataMasker 敏感文本脱敏器
     * @param properties 应用配置
     * @param ragBoundary 本地 RAG 边界
     */
    @Autowired
    public AiConversationContextBuilder(
            AiConversationStoreService conversationStore,
            SensitiveDataMasker sensitiveDataMasker,
            DevBridgeProperties properties,
            AiRagBoundary ragBoundary) {
        this.conversationStore = conversationStore;
        this.sensitiveDataMasker = sensitiveDataMasker;
        this.ragBoundary = ragBoundary;
        this.contextWindowTokens = Math.max(1024, properties.getAiContextWindowTokens());
        this.reservedTokens = Math.max(0, properties.getAiContextReservedTokens());
    }

    /**
     * 兼容不启用本地 RAG 的测试和显式创建方式。
     *
     * @param conversationStore 历史聊天文件服务
     * @param sensitiveDataMasker 敏感文本脱敏器
     * @param properties 应用配置
     */
    public AiConversationContextBuilder(
            AiConversationStoreService conversationStore,
            SensitiveDataMasker sensitiveDataMasker,
            DevBridgeProperties properties) {
        this(conversationStore, sensitiveDataMasker, properties, null);
    }

    /**
     * 构造一次模型调用的 Working Memory。
     *
     * @param request 当前对话请求
     * @param outputTokens 本次模型最大输出 Token
     * @param preferStoredHistory 是否优先读取 Conversation Store；确认恢复使用固定 Checkpoint 历史
     * @return 有界 Working Memory
     */
    public WorkingContext build(
            AiChatRequest request,
            int outputTokens,
            boolean preferStoredHistory) {
        RequestBudget budget = new RequestBudget(
                contextWindowTokens, Math.max(0, outputTokens), 24, 16, 1, 300,
                RequestExecutionBudget.defaults());
        return build(request, budget, preferStoredHistory);
    }

    /**
     * 按当前模型能力和部署上限构造 Working Memory。
     *
     * @param request 当前对话请求
     * @param config 当前模型运行时配置
     * @param preferStoredHistory 是否优先读取 Conversation Store
     * @return 有界 Working Memory
     */
    public WorkingContext build(
            AiChatRequest request,
            AiRuntimeConfig config,
            boolean preferStoredHistory) {
        ModelLimits limits = config == null || config.capability() == null
                ? ModelLimits.defaults()
                : config.capability().limits();
        RequestBudget budget = new RequestBudget(
                Math.min(contextWindowTokens, Math.max(1024, limits.contextWindowTokens())),
                Math.max(1, limits.maxOutputTokens()),
                Math.max(1, limits.maxToolCalls()),
                Math.max(1, limits.maxModelCalls()),
                Math.max(0, limits.maxRetries()),
                Math.max(1, limits.maxDurationSeconds()),
                new RequestExecutionBudget(
                        Math.max(1, limits.maxPlanSteps()),
                        Math.max(1L, limits.maxToolOutputBytes()),
                        Math.max(1, limits.maxConcurrentTools()),
                        Math.max(1L, limits.maxCostMicros())));
        return build(request, budget, preferStoredHistory);
    }

    /**
     * 使用已解析请求预算执行上下文选择。
     */
    private WorkingContext build(
            AiChatRequest request,
            RequestBudget budget,
            boolean preferStoredHistory) {
        int currentTokens = estimateTokens(request == null ? "" : request.message())
                + CURRENT_MESSAGE_OVERHEAD_TOKENS;
        int fixedTokens = reservedTokens + budget.maxOutputTokens() + currentTokens;
        if (fixedTokens > budget.contextWindowTokens()) {
            throw new BusinessException(
                    "AI_CONTEXT_BUDGET_EXCEEDED",
                    "当前问题超过模型上下文预算",
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "contextWindowTokens=" + budget.contextWindowTokens()
                            + ", estimatedFixedTokens=" + fixedTokens);
        }
        int historyBudget = Math.max(
                0, budget.contextWindowTokens() - fixedTokens);
        CandidateHistory candidates = loadCandidates(request, preferStoredHistory);
        RagSelection rag = selectRag(request, historyBudget / 4);
        return selectContext(candidates, historyBudget, budget, rag);
    }

    /**
     * 对当前问题、错误摘要和任务目标仅执行认证凭据保护。
     *
     * @param text 原始文本
     * @return 保留业务数据并隐藏认证凭据的文本
     */
    public String protectCredentials(String text) {
        return sensitiveDataMasker.protectCredentials(text);
    }

    /**
     * 优先读取后端会话文件；新会话尚未落盘时才兼容请求内历史。
     */
    private CandidateHistory loadCandidates(AiChatRequest request, boolean preferStoredHistory) {
        if (preferStoredHistory && request != null && StringUtils.hasText(request.conversationId())
                && conversationStore != null) {
            try {
                ConversationContextSnapshot snapshot = conversationStore.context(
                        request.conversationId().trim(), STORE_MESSAGE_LIMIT);
                return new CandidateHistory(
                        storedMessages(snapshot.messages()), "CONVERSATION_STORE",
                        summaryContext(snapshot.summary()));
            } catch (BusinessException ex) {
                if (!"AI_CONVERSATION_NOT_FOUND".equals(ex.getErrorCode())) {
                    throw ex;
                }
            }
        }
        List<AiChatHistoryMessage> fallback = request == null
                ? List.of() : validHistory(request.history());
        SummaryContext summary = request == null ? SummaryContext.empty() : request.summaryContext();
        String source = fallback.isEmpty() && !StringUtils.hasText(summary.content())
                ? "NONE" : "REQUEST_FALLBACK";
        return new CandidateHistory(fallback, source, summary);
    }

    /**
     * 在历史预算中先为较早摘要保留至多三分之一，再从最近消息向前选择。
     */
    private WorkingContext selectContext(
            CandidateHistory candidates,
            int historyBudget,
            RequestBudget budget,
            RagSelection rag) {
        SummarySelection summary = selectSummary(candidates.summary(), historyBudget / 3);
        List<AiChatHistoryMessage> selected = new ArrayList<>();
        int recentTokens = 0;
        boolean truncated = false;
        List<AiChatHistoryMessage> values = candidates.messages();
        int recentBudget = Math.max(
                0, historyBudget - summary.estimatedTokens() - rag.estimatedTokens());
        for (int index = values.size() - 1; index >= 0; index--) {
            AiChatHistoryMessage message = values.get(index);
            int messageTokens = estimateMessageTokens(message);
            int remaining = recentBudget - recentTokens;
            if (messageTokens <= remaining) {
                selected.add(message);
                recentTokens += messageTokens;
                continue;
            }
            AiChatHistoryMessage limited = truncateMessage(message, remaining);
            if (limited != null) {
                selected.add(limited);
                recentTokens += estimateMessageTokens(limited);
            }
            truncated = true;
            break;
        }
        if (selected.size() < values.size()) {
            truncated = true;
        }
        Collections.reverse(selected);
        return new WorkingContext(
                selected, budget.contextWindowTokens(), historyBudget,
                recentTokens + summary.estimatedTokens() + rag.estimatedTokens(),
                truncated || summary.truncated() || rag.truncated(), candidates.source(),
                new ContextSupplement(
                        new SummaryContext(
                                summary.content(), candidates.summary().version(),
                                candidates.summary().sourceMessageCount()),
                        new RagContext(rag.content(), rag.citations())),
                budget);
    }

    /**
     * 优先复用 Checkpoint 中的 RAG 快照，否则按当前问题查询本地知识。
     */
    private RagSelection selectRag(AiChatRequest request, int tokenBudget) {
        if (request == null || tokenBudget <= 0) {
            return RagSelection.empty();
        }
        RagContext saved = request.ragContext();
        if (StringUtils.hasText(saved.content())) {
            return limitRag(saved.content(), saved.citations(), tokenBudget);
        }
        if (ragBoundary == null || !ragBoundary.enabled() || !StringUtils.hasText(request.message())) {
            return RagSelection.empty();
        }
        String deviceId = request.deviceContext() == null ? "" : request.deviceContext().serial();
        String osVersion = request.deviceContext() == null ? "" : request.deviceContext().osVersion();
        var result = ragBoundary.search(new SearchRequest(request.message(), deviceId, osVersion, 5));
        if (!result.hasMatches()) {
            return RagSelection.empty();
        }
        StringBuilder content = new StringBuilder();
        List<String> citations = new ArrayList<>();
        for (RagCitation citation : result.citations()) {
            String marker = citation.title() + " | " + citation.source() + " | " + citation.chunkId();
            citations.add(marker);
            content.append("[来源: ").append(marker).append("]\n")
                    .append(citation.excerpt()).append("\n\n");
        }
        return limitRag(content.toString().trim(), citations, tokenBudget);
    }

    /**
     * 对 RAG 证据执行脱敏和 Token 限制。
     */
    private RagSelection limitRag(String content, List<String> citations, int tokenBudget) {
        String masked = sensitiveDataMasker.protectCredentials(content == null ? "" : content.trim());
        int tokens = estimateTokens(masked);
        if (tokens <= tokenBudget) {
            return new RagSelection(masked, citations, tokens, false);
        }
        String notice = "\n[RAG 证据超过上下文预算，已截断]";
        int contentBudget = tokenBudget - estimateTokens(notice);
        String limited = contentBudget > 0 ? truncateToTokens(masked, contentBudget) + notice : "";
        return new RagSelection(limited, citations, estimateTokens(limited), true);
    }

    /**
     * 在摘要预算中保留完整或有明确标记的有界摘要。
     */
    private SummarySelection selectSummary(SummaryContext summary, int tokenBudget) {
        if (summary == null || !StringUtils.hasText(summary.content()) || tokenBudget <= 0) {
            return new SummarySelection("", 0, StringUtils.hasText(summary == null ? "" : summary.content()));
        }
        String content = sensitiveDataMasker.protectCredentials(summary.content().trim());
        int tokens = estimateTokens(content);
        if (tokens <= tokenBudget) {
            return new SummarySelection(content, tokens, false);
        }
        int contentBudget = tokenBudget - estimateTokens(SUMMARY_TRUNCATED_NOTICE);
        String limited = contentBudget > 0 ? truncateToTokens(content, contentBudget) : "";
        String selected = StringUtils.hasText(limited) ? limited + SUMMARY_TRUNCATED_NOTICE : "";
        return new SummarySelection(selected, estimateTokens(selected), true);
    }

    /**
     * 将持久摘要转换为带来源范围的可追溯上下文。
     */
    private SummaryContext summaryContext(ConversationSummarySnapshot summary) {
        if (summary == null || summary.version() <= 0) {
            return SummaryContext.empty();
        }
        StringBuilder content = new StringBuilder()
                .append("摘要版本：").append(summary.version())
                .append("；来源消息：").append(summary.sourceMessageCount())
                .append("；范围：").append(summary.sourceFirstMessageId())
                .append(" 至 ").append(summary.sourceLastMessageId())
                .append("；校验：").append(summary.sourceDigest(), 0, Math.min(12, summary.sourceDigest().length()));
        if (StringUtils.hasText(summary.conversationSummary())) {
            content.append("\n\n").append(summary.conversationSummary());
        }
        if (StringUtils.hasText(summary.taskSummary())) {
            content.append("\n\n").append(summary.taskSummary());
        }
        return new SummaryContext(content.toString(), summary.version(), summary.sourceMessageCount());
    }

    /**
     * 将会话 JSON 转换为普通用户/AI 文本，工具、过程、错误和系统消息全部忽略。
     */
    private List<AiChatHistoryMessage> storedMessages(List<JsonNode> messages) {
        List<AiChatHistoryMessage> values = new ArrayList<>();
        for (JsonNode message : messages) {
            String role = message.path("role").asText("");
            String kind = message.path("kind").asText("text");
            String content = storedContent(message);
            if (("user".equals(role) || "assistant".equals(role))
                    && "text".equals(kind) && !message.path("error").asBoolean(false)
                    && StringUtils.hasText(content)) {
                values.add(new AiChatHistoryMessage(
                        role, sensitiveDataMasker.protectCredentials(content.trim())));
            }
        }
        return List.copyOf(values);
    }

    /**
     * 读取普通正文或前端分段正文，并把单条候选限制在模型窗口内。
     *
     * <p>完整分段仍保存在 Conversation Store；这里只构造本次模型调用可能使用的有界副本。</p>
     */
    private String storedContent(JsonNode message) {
        JsonNode segments = message.path("contentSegments");
        if (!segments.isArray() || segments.isEmpty()) {
            return message.path("content").asText("");
        }
        StringBuilder content = new StringBuilder();
        int remainingTokens = contextWindowTokens;
        for (JsonNode segment : segments) {
            String value = segment.asText("");
            int segmentTokens = estimateTokens(value);
            if (segmentTokens <= remainingTokens) {
                content.append(value);
                remainingTokens -= segmentTokens;
                continue;
            }
            content.append(truncateToTokens(value, remainingTokens));
            break;
        }
        return content.toString();
    }

    /**
     * 清理兼容请求中的历史，只接受普通 user/assistant 文本。
     */
    private List<AiChatHistoryMessage> validHistory(List<AiChatHistoryMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
                .filter(item -> item != null && StringUtils.hasText(item.content()))
                .filter(item -> "user".equals(item.role()) || "assistant".equals(item.role()))
                .map(item -> new AiChatHistoryMessage(
                        item.role(), sensitiveDataMasker.protectCredentials(item.content().trim())))
                .toList();
    }

    /**
     * 在剩余预算中保留最近超长消息的开头，并附加明确截断标记。
     */
    private AiChatHistoryMessage truncateMessage(AiChatHistoryMessage message, int remainingTokens) {
        int contentBudget = remainingTokens - MESSAGE_OVERHEAD_TOKENS - estimateTokens(TRUNCATED_NOTICE);
        if (contentBudget <= 0) {
            return null;
        }
        String content = truncateToTokens(message.content(), contentBudget);
        return StringUtils.hasText(content)
                ? new AiChatHistoryMessage(message.role(), content + TRUNCATED_NOTICE)
                : null;
    }

    /**
     * 使用与预算估算一致的规则截取文本，避免先按字符截取后再次超出 Token。
     */
    private String truncateToTokens(String content, int tokenBudget) {
        StringBuilder result = new StringBuilder();
        int asciiCharacters = 0;
        int nonAsciiTokens = 0;
        for (int offset = 0; offset < content.length();) {
            int codePoint = content.codePointAt(offset);
            int nextAscii = asciiCharacters + (codePoint <= 0x7F ? 1 : 0);
            int nextNonAscii = nonAsciiTokens + (codePoint <= 0x7F ? 0 : 1);
            if (nextNonAscii + (nextAscii + 3) / 4 > tokenBudget) {
                break;
            }
            result.appendCodePoint(codePoint);
            asciiCharacters = nextAscii;
            nonAsciiTokens = nextNonAscii;
            offset += Character.charCount(codePoint);
        }
        return result.toString().trim();
    }

    /**
     * 估算单条历史消息 Token，包含角色和消息结构开销。
     */
    private int estimateMessageTokens(AiChatHistoryMessage message) {
        return MESSAGE_OVERHEAD_TOKENS + estimateTokens(message.content());
    }

    /**
     * 以中文单字约一 Token、ASCII 四字符约一 Token进行保守估算。
     */
    int estimateTokens(String content) {
        if (!StringUtils.hasText(content)) {
            return 0;
        }
        int asciiCharacters = 0;
        int nonAsciiTokens = 0;
        for (int offset = 0; offset < content.length();) {
            int codePoint = content.codePointAt(offset);
            if (codePoint <= 0x7F) {
                asciiCharacters++;
            } else {
                nonAsciiTokens++;
            }
            offset += Character.charCount(codePoint);
        }
        return nonAsciiTokens + (asciiCharacters + 3) / 4;
    }

    /** Working Memory 候选消息、摘要及来源。by AI.Coding */
    private record CandidateHistory(
            List<AiChatHistoryMessage> messages,
            String source,
            SummaryContext summary) {

        /** 固化候选消息。 */
        private CandidateHistory {
            messages = List.copyOf(messages);
            summary = summary == null ? SummaryContext.empty() : summary;
        }
    }

    /** 摘要预算选择结果。by AI.Coding */
    private record SummarySelection(String content, int estimatedTokens, boolean truncated) {
    }

    /** RAG 预算选择结果。by AI.Coding */
    private record RagSelection(
            String content,
            List<String> citations,
            int estimatedTokens,
            boolean truncated) {

        /** 固化引用。 */
        private RagSelection {
            content = content == null ? "" : content;
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        /** 创建空结果。 */
        private static RagSelection empty() {
            return new RagSelection("", List.of(), 0, false);
        }
    }

    /** Working Memory 补充上下文。by AI.Coding */
    public record ContextSupplement(SummaryContext summary, RagContext rag) {

        /** 兼容空补充上下文。 */
        public ContextSupplement {
            summary = summary == null ? SummaryContext.empty() : summary;
            rag = rag == null ? RagContext.empty() : rag;
        }

        /** 创建空补充上下文。 */
        public static ContextSupplement empty() {
            return new ContextSupplement(SummaryContext.empty(), RagContext.empty());
        }
    }

    /**
     * 单次模型调用的有界 Working Memory。
     *
     * <p>by AI.Coding</p>
     */
    public record WorkingContext(
            List<AiChatHistoryMessage> history,
            int contextWindowTokens,
            int historyTokenBudget,
            int estimatedHistoryTokens,
            boolean truncated,
            String source,
            ContextSupplement supplement,
            RequestBudget requestBudget) {

        /** 固化已选择历史并兼容空摘要。 */
        public WorkingContext {
            history = List.copyOf(history);
            supplement = supplement == null ? ContextSupplement.empty() : supplement;
            requestBudget = requestBudget == null
                    ? new RequestBudget(
                            contextWindowTokens, 1, 1, 1, 0, 1,
                            new RequestExecutionBudget(1, 1024, 1, 1))
                    : requestBudget;
        }

        /** 返回已纳入模型上下文的摘要正文。 */
        public String conversationSummary() {
            return supplement.summary().content();
        }

        /** 返回持久摘要版本。 */
        public long summaryVersion() {
            return supplement.summary().version();
        }

        /** 返回摘要来源消息数量。 */
        public int summarySourceMessageCount() {
            return supplement.summary().sourceMessageCount();
        }

        /** 判断本次请求是否实际纳入摘要。 */
        public boolean summaryIncluded() {
            return StringUtils.hasText(supplement.summary().content());
        }

        /** 返回已纳入模型上下文的 RAG 证据。 */
        public String ragContent() {
            return supplement.rag().content();
        }

        /** 返回 RAG 引用标识。 */
        public List<String> ragCitations() {
            return supplement.rag().citations();
        }
    }

    /**
     * 单次模型请求的上下文、输出、工具、调用、重试和时间预算。
     *
     * <p>by AI.Coding</p>
     */
    public record RequestBudget(
            int contextWindowTokens,
            int maxOutputTokens,
            int maxToolCalls,
            int maxModelCalls,
            int maxRetries,
            int maxDurationSeconds,
            RequestExecutionBudget execution) {

        /** 空扩展预算使用保守默认值。 */
        public RequestBudget {
            execution = execution == null ? RequestExecutionBudget.defaults() : execution;
        }

        /** 返回最大计划步骤数。 */
        public int maxPlanSteps() { return execution.maxPlanSteps(); }

        /** 返回工具输出字节预算。 */
        public long maxToolOutputBytes() { return execution.maxToolOutputBytes(); }

        /** 返回最大并发工具数。 */
        public int maxConcurrentTools() { return execution.maxConcurrentTools(); }

        /** 返回模型调用成本预算。 */
        public long maxCostMicros() { return execution.maxCostMicros(); }
    }

    /** 单次任务扩展执行预算。by AI.Coding */
    public record RequestExecutionBudget(
            int maxPlanSteps,
            long maxToolOutputBytes,
            int maxConcurrentTools,
            long maxCostMicros) {

        /** 创建默认扩展预算。 */
        public static RequestExecutionBudget defaults() {
            return new RequestExecutionBudget(32, 8L * 1024L * 1024L, 1, 5_000_000L);
        }
    }
}
