package com.devbridge.server.api;

import com.devbridge.server.ai.analysis.AiLogAnalysisRequest;
import com.devbridge.server.ai.analysis.AiLogAnalysisResponse;
import com.devbridge.server.ai.analysis.AiLogAnalysisService;
import com.devbridge.server.ai.config.AiConfigDetail;
import com.devbridge.server.ai.config.AiModelListRequest;
import com.devbridge.server.ai.config.AiConfigRequest;
import com.devbridge.server.ai.config.AiConfigService;
import com.devbridge.server.ai.config.AiConfigStatus;
import com.devbridge.server.ai.config.AiConnectionTestResult;
import com.devbridge.server.ai.conversation.AiChatRequest;
import com.devbridge.server.ai.conversation.AiChatResponse;
import com.devbridge.server.ai.conversation.AiConversationStoreService;
import com.devbridge.server.ai.conversation.AiConversationStoreService.ConversationDetail;
import com.devbridge.server.ai.conversation.AiConversationStoreService.ConversationMigrationRequest;
import com.devbridge.server.ai.conversation.AiConversationStoreService.ConversationMigrationResult;
import com.devbridge.server.ai.conversation.AiConversationStoreService.ConversationPage;
import com.devbridge.server.ai.conversation.AiConversationStoreService.ConversationWriteRequest;
import com.devbridge.server.ai.conversation.AiConversationService;
import com.devbridge.server.ai.provider.AiProviderGateway;
import com.devbridge.server.ai.provider.AiModelListResponse;
import com.devbridge.server.ai.provider.AiProviderRequest;
import com.devbridge.server.ai.provider.AiProviderResponse;
import com.devbridge.server.ai.web.WebSearchClient;
import com.devbridge.server.ai.web.WebSearchClient.SearchRequest;
import com.devbridge.server.ai.web.WebSearchConfigService;
import com.devbridge.server.ai.web.WebSearchConfigService.WebSearchConfigDetail;
import com.devbridge.server.ai.web.WebSearchConfigService.WebSearchConfigRequest;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletResponse;

/**
 * AI 助手接口，提供配置、连接测试、普通对话和日志分析能力。
 *
 * <p>by AI.Coding</p>
 */
@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class AiController {

    private static final int TEST_MAX_TOKENS = 80;
    private static final double TEST_TEMPERATURE = 0d;

    private final AiConfigService configService;
    private final AiProviderGateway providerGateway;
    private final AiConversationService conversationService;
    private final AiConversationStoreService conversationStoreService;
    private final AiLogAnalysisService logAnalysisService;
    private final WebSearchConfigService webSearchConfigService;
    private final WebSearchClient webSearchClient;

    /**
     * 注入 AI 接口依赖。
     *
     * @param configService AI 配置服务
     * @param providerGateway Provider 调用边界
     * @param conversationService 普通对话服务
     * @param conversationStoreService 历史聊天本地文件服务
     * @param logAnalysisService 日志分析服务
     * @param webSearchConfigService 网络检索配置服务
     * @param webSearchClient 网络检索客户端
     */
    public AiController(
            AiConfigService configService,
            AiProviderGateway providerGateway,
            AiConversationService conversationService,
            AiConversationStoreService conversationStoreService,
            AiLogAnalysisService logAnalysisService,
            WebSearchConfigService webSearchConfigService,
            WebSearchClient webSearchClient) {
        this.configService = configService;
        this.providerGateway = providerGateway;
        this.conversationService = conversationService;
        this.conversationStoreService = conversationStoreService;
        this.logAnalysisService = logAnalysisService;
        this.webSearchConfigService = webSearchConfigService;
        this.webSearchClient = webSearchClient;
    }

    /**
     * 获取 AI 配置状态；该接口不返回 API Key 明文。
     *
     * @return 配置状态
     */
    @GetMapping("/config/status")
    public AiConfigStatus configStatus() {
        return configService.status();
    }

    /**
     * 获取 AI 配置详情；指定 Provider 时回填该 Provider 的 API URL 和 API Key。
     *
     * @param provider Provider 配置值
     * @return 配置详情
     */
    @GetMapping("/config")
    public AiConfigDetail configDetail(
            @RequestParam(required = false) String provider,
            @RequestParam(defaultValue = "false") boolean revealApiKey,
            HttpServletResponse response) {
        // 明文只用于当前设置表单，禁止浏览器、代理或 Electron 缓存响应。
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        return configService.detail(provider, revealApiKey);
    }

    /**
     * 保存 AI 配置，成功后返回脱敏状态。
     *
     * @param request 配置请求
     * @return 配置状态
     */
    @PutMapping("/config")
    public AiConfigStatus saveConfig(@RequestBody AiConfigRequest request) {
        return configService.save(request);
    }

    /**
     * 使用临时配置测试 Provider 连接，不覆盖已保存配置。
     *
     * @param request 配置请求
     * @return 连接测试结果
     */
    @PostMapping("/config/test")
    public AiConnectionTestResult testConfig(@RequestBody AiConfigRequest request) {
        AiProviderResponse response = providerGateway.chat(new AiProviderRequest(
                configService.runtimeFrom(request),
                "你是连接测试助手，只返回 OK。",
                "请返回 OK。",
                TEST_MAX_TOKENS,
                TEST_TEMPERATURE));
        return new AiConnectionTestResult(true, response.answer(), request.provider(), request.model());
    }

    /**
     * 使用临时 Provider 配置拉取模型列表，不覆盖已保存配置。
     *
     * @param request 模型列表请求
     * @return 模型列表
     */
    @PostMapping("/config/models")
    public AiModelListResponse listModels(@RequestBody AiModelListRequest request) {
        return providerGateway.listModels(configService.runtimeForModelList(request));
    }

    /** 获取网络检索配置，明文密钥响应禁止缓存。 */
    @GetMapping("/config/web-search")
    public WebSearchConfigDetail webSearchConfig(
            @RequestParam(defaultValue = "false") boolean revealApiKey,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        return webSearchConfigService.detail(revealApiKey);
    }

    /** 保存独立的网络检索配置。 */
    @PutMapping("/config/web-search")
    public WebSearchConfigDetail saveWebSearchConfig(@RequestBody WebSearchConfigRequest request) {
        return webSearchConfigService.save(request);
    }

    /** 使用临时配置执行最小 Tavily 搜索测试，不覆盖已保存配置。 */
    @PostMapping("/config/web-search/test")
    public WebSearchConnectionTestResult testWebSearchConfig(@RequestBody WebSearchConfigRequest request) {
        var result = webSearchClient.search(
                webSearchConfigService.runtimeFrom(request), new SearchRequest("Android developer documentation", 1));
        return new WebSearchConnectionTestResult(true, "网络检索连接测试通过", result.results().size());
    }

    /**
     * 发起普通 AI 对话。
     *
     * @param request 对话请求
     * @return 对话响应
     */
    @PostMapping("/chat")
    public AiChatResponse chat(
            @RequestBody AiChatRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return conversationService.chat(request, idempotencyKey);
    }

    /** 网络检索连接测试响应。by AI.Coding */
    public record WebSearchConnectionTestResult(boolean available, String message, int resultCount) {
    }

    /**
     * 发起普通 AI 流式对话。
     *
     * @param request 对话请求
     * @return SSE 流
     */
    @PostMapping("/chat/stream")
    public SseEmitter streamChat(
            @RequestBody AiChatRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return conversationService.streamChat(request, idempotencyKey);
    }

    /**
     * 分页读取历史聊天摘要，不加载全部消息正文。
     *
     * @param page 从零开始的页码
     * @param size 每页数量
     * @return 历史聊天分页
     */
    @GetMapping("/conversations")
    public ConversationPage listConversations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return conversationStoreService.list(page, size);
    }

    /**
     * 读取指定历史聊天的最近消息。
     *
     * @param conversationId 会话标识
     * @param messageLimit 最近消息数量上限
     * @return 会话详情
     */
    @GetMapping("/conversations/{conversationId}")
    public ConversationDetail getConversation(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "100") int messageLimit) {
        return conversationStoreService.get(conversationId, messageLimit);
    }

    /**
     * 创建、更新、重命名或激活一个历史聊天。
     *
     * @param conversationId 会话标识
     * @param request 会话写入内容
     * @return 保存后的会话详情
     */
    @PutMapping("/conversations/{conversationId}")
    public ConversationDetail saveConversation(
            @PathVariable String conversationId,
            @RequestBody ConversationWriteRequest request) {
        return conversationStoreService.upsert(conversationId, request);
    }

    /**
     * 删除指定历史聊天。
     *
     * @param conversationId 会话标识
     */
    @DeleteMapping("/conversations/{conversationId}")
    public void deleteConversation(@PathVariable String conversationId) {
        conversationStoreService.delete(conversationId);
    }

    /**
     * 幂等迁移旧浏览器 localStorage 历史数据。
     *
     * @param request 旧会话数据
     * @return 迁移结果
     */
    @PostMapping("/conversations/migrate")
    public ConversationMigrationResult migrateConversations(
            @RequestBody ConversationMigrationRequest request) {
        return conversationStoreService.migrate(request);
    }

    /**
     * 分析当前设备日志。
     *
     * @param request 日志分析请求
     * @return 日志分析响应
     */
    @PostMapping("/analyze/logs")
    public AiLogAnalysisResponse analyzeLogs(@RequestBody AiLogAnalysisRequest request) {
        return logAnalysisService.analyze(request);
    }
}
