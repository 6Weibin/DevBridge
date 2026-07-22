package com.devbridge.server.ai.provider;

import com.devbridge.server.ai.config.AiConfigService;
import com.devbridge.server.ai.config.AiRuntimeConfig;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.ai.observation.AiObservationEvent;
import com.devbridge.server.ai.observation.AiObservationRecorder;
import com.devbridge.server.ai.prompt.AiPromptComposer;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.ai.security.egress.AiDataEgressGuard;
import com.devbridge.server.ai.security.egress.AiDataEgressGuard.ConfirmationRequiredException;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Classification;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Context;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.DataType;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Item;
import com.devbridge.server.model.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import reactor.core.Disposable;

/**
 * 基于 Spring AI ChatClient 的 Provider Gateway 实现。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class SpringAiProviderGateway implements AiProviderGateway {

    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(60);

    private final SensitiveDataMasker masker;
    private final AiObservationRecorder observationRecorder;
    private final AiProviderEndpointResolver endpointResolver;
    private final AiPromptComposer promptComposer;
    private final AiDataEgressGuard dataEgressGuard;
    private final AiConfigService configService;
    private final AiModelRouter modelRouter;
    private final DevBridgeProperties properties;

    /**
     * 注入 Provider Gateway 依赖。
     *
     * @param masker 脱敏工具
     * @param observationRecorder AI 调用观测记录器
     * @param endpointResolver Provider 端点解析器
     * @param promptComposer Prompt 安全分层组装器
     * @param dataEgressGuard 模型数据外发守卫
     * @param configService AI 多 Provider 配置服务
     * @param modelRouter 已配置模型路由器
     * @param properties AI 产品化功能开关
     */
    @Autowired
    public SpringAiProviderGateway(
            SensitiveDataMasker masker,
            AiObservationRecorder observationRecorder,
            AiProviderEndpointResolver endpointResolver,
            AiPromptComposer promptComposer,
            AiDataEgressGuard dataEgressGuard,
            AiConfigService configService,
            AiModelRouter modelRouter,
            DevBridgeProperties properties) {
        this.masker = masker;
        this.observationRecorder = observationRecorder;
        this.endpointResolver = endpointResolver;
        this.promptComposer = promptComposer;
        this.dataEgressGuard = dataEgressGuard;
        this.configService = configService;
        this.modelRouter = modelRouter;
        this.properties = properties;
    }

    /**
     * 兼容仅验证纯解析方法的旧测试构造方式。
     *
     * @param masker 脱敏工具
     * @param observationRecorder AI 调用观测记录器
     * @param endpointResolver Provider 端点解析器
     */
    SpringAiProviderGateway(
            SensitiveDataMasker masker,
            AiObservationRecorder observationRecorder,
            AiProviderEndpointResolver endpointResolver) {
        this(masker, observationRecorder, endpointResolver, new AiPromptComposer(), null, null,
                new AiModelRouter(), new DevBridgeProperties());
    }

    /**
     * 兼容已注入安全组件但不启用 Provider 降级的测试。
     *
     * @param masker 脱敏工具
     * @param observationRecorder AI 调用观测记录器
     * @param endpointResolver Provider 端点解析器
     * @param promptComposer Prompt 组装器
     * @param dataEgressGuard 外发守卫
     */
    SpringAiProviderGateway(
            SensitiveDataMasker masker,
            AiObservationRecorder observationRecorder,
            AiProviderEndpointResolver endpointResolver,
            AiPromptComposer promptComposer,
            AiDataEgressGuard dataEgressGuard) {
        this(masker, observationRecorder, endpointResolver, promptComposer, dataEgressGuard, null,
                new AiModelRouter(), new DevBridgeProperties());
    }

    /**
     * 使用 OpenAI-compatible 模型列表接口拉取当前 Provider 可用模型。
     *
     * @param config 临时运行时配置
     * @return 模型列表响应
     */
    @Override
    public AiModelListResponse listModels(AiRuntimeConfig config) {
        long started = System.currentTimeMillis();
        AiProviderEndpoint endpoint = endpointResolver.resolve(config);
        try {
            List<String> models = fetchModelIds(config, endpoint, endpoint.modelsPath());
            record(config, true, "", System.currentTimeMillis() - started);
            return new AiModelListResponse(config.provider().getValue(), models);
        } catch (RestClientResponseException ex) {
            List<String> fallbackModels = tryFallbackModelPath(config, endpoint, ex);
            if (!fallbackModels.isEmpty()) {
                record(config, true, "", System.currentTimeMillis() - started);
                return new AiModelListResponse(config.provider().getValue(), fallbackModels);
            }
            throw providerModelListError(config, ex, started);
        } catch (RestClientException ex) {
            throw providerModelListError(config, ex, started);
        }
    }

    /**
     * 使用 Spring AI 构建一次性 ChatClient 并发起文本对话。
     *
     * @param request Provider 请求
     * @return Provider 响应
     */
    @Override
    public AiProviderResponse chat(AiProviderRequest request) {
        List<ProviderAttempt> attempts = attempts(request);
        BusinessException last = null;
        for (int index = 0; index < attempts.size(); index++) {
            ProviderAttempt candidate = attempts.get(index);
            AiProviderRequest attempt = candidate.request();
            markRoute(attempt, candidate.reason());
            AttemptState state = new AttemptState();
            long started = System.currentTimeMillis();
            try {
                AiProviderResponse response = chatOnce(attempt, state, started);
                modelRouter.recordSuccess(attempt.config());
                return response;
            } catch (BusinessException ex) {
                // Prompt 和数据外发安全决策不能通过重试或切换 Provider 绕过。
                if (!ex.getErrorCode().startsWith("AI_PROVIDER_")) {
                    throw ex;
                }
                last = ex;
            } catch (RuntimeException ex) {
                boolean retryable = retryable(ex);
                last = providerError(attempt, ex, started);
                if (!retryable || state.toolInvoked().get()) {
                    throw last;
                }
                modelRouter.recordRetryableFailure(attempt.config());
            }
            if (index + 1 >= attempts.size() || state.toolInvoked().get()) {
                throw last;
            }
            markRetry(request);
        }
        throw last == null ? new IllegalStateException("Provider 调用未执行") : last;
    }

    /**
     * 使用 Spring AI 流式接口发起对话，把 Flux 增量转换为模块内通用事件。
     *
     * @param request Provider 请求
     * @param listener 流式事件监听器
     * @return 流式请求句柄
     */
    @Override
    public AiProviderStreamHandle stream(AiProviderRequest request, AiProviderStreamListener listener) {
        RetryStreamHandle handle = new RetryStreamHandle();
        startStreamAttempt(attempts(request), 0, listener, handle);
        return handle;
    }

    /**
     * 执行一次同步 Provider 调用，工具回调使用调用标记包装。
     */
    private AiProviderResponse chatOnce(
            AiProviderRequest request,
            AttemptState state,
            long started) {
        Duration remaining = enforceRuntimeBudget(request);
        enforceEgress(request);
        ChatClient client = ChatClient.create(chatModel(
                request.config(), request.maxTokens(), request.temperature(), remaining));
        String systemPrompt = promptComposer.compose(
                request.productPrompt(), request.config().userPreferencePrompt());
        String answer = client.prompt()
                .system(systemPrompt)
                .user(request.userPrompt())
                .toolCallbacks(trackingTools(request, state.toolInvoked()))
                .toolContext(request.toolContext())
                .call()
                .content();
        long elapsed = System.currentTimeMillis() - started;
        record(request, true, "", elapsed);
        return new AiProviderResponse(
                answer == null ? "" : answer,
                request.config().provider().getValue(), request.config().model(), elapsed);
    }

    /**
     * 启动一次流式尝试；只有没有正文和工具副作用时才切换到下一候选。
     */
    private void startStreamAttempt(
            List<ProviderAttempt> attempts,
            int index,
            AiProviderStreamListener listener,
            RetryStreamHandle handle) {
        if (handle.canceled()) {
            return;
        }
        ProviderAttempt candidate = attempts.get(index);
        AiProviderRequest request = candidate.request();
        markRoute(request, candidate.reason());
        AttemptState state = new AttemptState();
        long started = System.currentTimeMillis();
        try {
            Duration remaining = enforceRuntimeBudget(request);
            enforceEgress(request);
            AtomicReference<String> finishReason = new AtomicReference<>("");
            ChatClient client = ChatClient.create(chatModel(
                    request.config(), request.maxTokens(), request.temperature(), remaining));
            String systemPrompt = promptComposer.compose(
                    request.productPrompt(), request.config().userPreferencePrompt());
            Disposable disposable = client.prompt()
                    .system(systemPrompt)
                    .user(request.userPrompt())
                    .toolCallbacks(trackingTools(request, state.toolInvoked()))
                    .toolContext(request.toolContext())
                    .stream().chatResponse()
                    .timeout(remaining)
                    .subscribe(
                            response -> streamResponse(response, listener, finishReason, state),
                            error -> streamError(attempts, index, listener, handle, state, request, started, error),
                            () -> completeStream(request, listener, started, finishReason.get()));
            handle.replace(index, disposable);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            BusinessException mapped = providerError(request, ex, started);
            if (retryable(ex) && index + 1 < attempts.size() && !state.toolInvoked().get()) {
                markRetry(request);
                startStreamAttempt(attempts, index + 1, listener, handle);
                return;
            }
            throw mapped;
        }
    }

    /**
     * 处理流式增量并标记已经向用户输出正文。
     */
    private void streamResponse(
            ChatResponse response,
            AiProviderStreamListener listener,
            AtomicReference<String> finishReason,
            AttemptState state) {
        String content = streamContent(response);
        if (content != null && !content.isEmpty()) {
            state.contentObserved().set(true);
        }
        handleStreamResponse(response, listener, finishReason);
    }

    /**
     * 处理异步流错误并决定是否安全重试。
     */
    private void streamError(
            List<ProviderAttempt> attempts,
            int index,
            AiProviderStreamListener listener,
            RetryStreamHandle handle,
            AttemptState state,
            AiProviderRequest request,
            long started,
            Throwable error) {
        RuntimeException runtime = toRuntimeException(error);
        BusinessException mapped = providerError(request, runtime, started);
        boolean safeRetry = retryable(runtime)
                && !state.contentObserved().get()
                && !state.toolInvoked().get()
                && index + 1 < attempts.size()
                && !handle.canceled();
        if (safeRetry) {
            modelRouter.recordRetryableFailure(request.config());
            markRetry(request);
            startStreamAttempt(attempts, index + 1, listener, handle);
            return;
        }
        listener.onError(mapped);
    }

    /**
     * 构造有界尝试顺序：当前模型一次重试，再选择一个能力兼容的已配置 Provider。
     */
    private List<ProviderAttempt> attempts(AiProviderRequest request) {
        List<ProviderAttempt> values = new ArrayList<>();
        int retries = Math.min(2, Math.max(0, request.config().capability().limits().maxRetries()));
        boolean fallbackEnabled = properties != null
                && properties.getAiFeatures().isModelFallbackEnabled();
        List<AiRuntimeConfig> fallbacks = fallbackEnabled && configService != null
                ? configService.compatibleFallbacks(request.config()) : List.of();
        List<AiModelRouter.RouteCandidate> route = modelRouter.route(request.config(), fallbacks);
        for (int index = 0; index < route.size(); index++) {
            AiModelRouter.RouteCandidate candidate = route.get(index);
            AiProviderRequest routed = index == 0 && candidate.config() == request.config()
                    ? request : request.withConfig(candidate.config());
            values.add(new ProviderAttempt(routed, candidate.reason()));
            if (index == 0 && retries > 0) {
                values.add(new ProviderAttempt(routed, "同模型安全阶段重试"));
            }
            if (values.size() >= retries + 1) {
                break;
            }
        }
        return List.copyOf(values);
    }

    /** 将本次实际模型和选型原因写入共享任务上下文。 */
    private void markRoute(AiProviderRequest request, String reason) {
        setContextReference(request, "modelRouteReason", reason == null ? "" : reason);
        setContextReference(request, "actualProvider", request.config().provider().getValue());
        setContextReference(request, "actualModel", request.config().model());
    }

    /** 更新共享任务上下文中的字符串引用。 */
    private void setContextReference(AiProviderRequest request, String key, String value) {
        Object contextValue = request.toolContext().get(key);
        if (!(contextValue instanceof AtomicReference<?> reference)) {
            return;
        }
        @SuppressWarnings("unchecked")
        AtomicReference<String> target = (AtomicReference<String>) reference;
        target.set(value);
    }

    /**
     * 增加共享 Provider 重试计数，供任务完成事件记录实际使用量。
     */
    private void markRetry(AiProviderRequest request) {
        Object value = request.toolContext().get("providerRetryCount");
        if (value instanceof java.util.concurrent.atomic.AtomicInteger counter) {
            counter.incrementAndGet();
        }
        Object recorder = request.toolContext().get("modelRetryRecorder");
        if (recorder instanceof Runnable callback) {
            callback.run();
        }
    }

    /**
     * 在每次真实 Provider 调用前强制执行模型调用次数和任务总时长预算。
     *
     * @param request Provider 请求
     * @return 当前调用剩余时长
     */
    private Duration enforceRuntimeBudget(AiProviderRequest request) {
        Object counterValue = request.toolContext().get("modelCallCount");
        Object maxValue = request.toolContext().get("maxModelCalls");
        if (counterValue instanceof AtomicInteger counter && maxValue instanceof Number max) {
            int used = counter.incrementAndGet();
            if (used > Math.max(1, max.intValue())) {
                throw budgetError("AI_MODEL_BUDGET_EXCEEDED", "模型调用预算已耗尽");
            }
        }
        enforceCostBudget(request);
        Object deadlineValue = request.toolContext().get("taskDeadlineMillis");
        if (!(deadlineValue instanceof Number deadline)) {
            return PROVIDER_TIMEOUT;
        }
        long remainingMillis = deadline.longValue() - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            throw budgetError("AI_TASK_TIMEOUT", "任务总执行时间预算已耗尽");
        }
        return Duration.ofMillis(Math.min(PROVIDER_TIMEOUT.toMillis(), remainingMillis));
    }

    /** 使用保守 Token 单价预占本次最大调用成本，避免重试绕过任务成本上限。 */
    private void enforceCostBudget(AiProviderRequest request) {
        Object usedValue = request.toolContext().get("estimatedCostMicros");
        Object maxValue = request.toolContext().get("maxCostMicros");
        if (!(usedValue instanceof AtomicLong used) || !(maxValue instanceof Number max)) return;
        long inputTokens = Math.max(1L, request.userPrompt().length() / 4L);
        long reservedMicros = Math.multiplyExact(inputTokens + request.maxTokens(), 10L);
        if (used.addAndGet(reservedMicros) > Math.max(1L, max.longValue())) {
            throw budgetError("AI_COST_BUDGET_EXCEEDED", "模型调用成本预算已耗尽");
        }
    }

    /** 构造不允许降级重试的任务预算错误。 */
    private BusinessException budgetError(String code, String message) {
        return new BusinessException(code, message, HttpStatus.REQUEST_TIMEOUT, "agent runtime budget exhausted");
    }

    /**
     * Provider 网络调用前执行统一数据外发策略；兼容纯解析测试实例。
     */
    private void enforceEgress(AiProviderRequest request) {
        if (dataEgressGuard != null) {
            dataEgressGuard.enforce(request);
        }
    }

    /**
     * 包装工具回调并记录本次 Provider 尝试是否已经触发工具。
     */
    private List<ToolCallback> trackingTools(
            AiProviderRequest request,
            AtomicBoolean toolInvoked) {
        return request.toolCallbacks().stream()
                .map(callback -> new TrackingToolCallback(callback, toolInvoked, request))
                .map(ToolCallback.class::cast)
                .toList();
    }

    /**
     * 仅网络超时、连接失败、限流和服务端错误允许安全阶段重试。
     */
    private boolean retryable(RuntimeException error) {
        if (error instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof ConnectException) {
                return true;
            }
            current = current.getCause();
        }
        String detail = String.valueOf(error.getMessage()).toLowerCase();
        if (List.of("400", "401", "403", "404", "409", "422", "invalid api key")
                .stream().anyMatch(detail::contains)) {
            return false;
        }
        return List.of("429", "500", "502", "503", "504", "timeout", "timed out",
                        "connection reset", "connection refused", "premature close")
                .stream().anyMatch(detail::contains);
    }

    /** 单次 Provider 尝试的副作用与输出状态。by AI.Coding */
    private static final class AttemptState {

        private final AtomicBoolean toolInvoked = new AtomicBoolean();
        private final AtomicBoolean contentObserved = new AtomicBoolean();

        /** 返回工具调用标记。 */
        private AtomicBoolean toolInvoked() {
            return toolInvoked;
        }

        /** 返回正文输出标记。 */
        private AtomicBoolean contentObserved() {
            return contentObserved;
        }
    }

    /** 可跨 Provider 尝试替换底层订阅的取消句柄。by AI.Coding */
    private static final class RetryStreamHandle implements AiProviderStreamHandle {

        private final AtomicReference<Disposable> current = new AtomicReference<>();
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicInteger currentAttempt =
                new java.util.concurrent.atomic.AtomicInteger(-1);

        /** 替换当前订阅；同步失败触发递归重试时，旧尝试不能覆盖新订阅。 */
        private void replace(int attempt, Disposable disposable) {
            int previous = currentAttempt.getAndAccumulate(attempt, Math::max);
            if (attempt < previous) {
                disposable.dispose();
                return;
            }
            current.set(disposable);
            if (canceled.get()) {
                disposable.dispose();
            }
        }

        /** 判断调用方是否已经取消。 */
        private boolean canceled() {
            return canceled.get();
        }

        /** 取消当前活动 Provider 订阅。 */
        @Override
        public void cancel() {
            canceled.set(true);
            Disposable disposable = current.get();
            if (disposable != null) {
                disposable.dispose();
            }
        }
    }

    /** 标记工具副作用发生点的回调包装。by AI.Coding */
    private final class TrackingToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final AtomicBoolean invoked;
        private final AiProviderRequest request;

        /** 保存原回调和共享调用标记。 */
        private TrackingToolCallback(
                ToolCallback delegate,
                AtomicBoolean invoked,
                AiProviderRequest request) {
            this.delegate = delegate;
            this.invoked = invoked;
            this.request = request;
        }

        /** 返回原工具定义。 */
        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        /** 标记后执行无上下文工具调用。 */
        @Override
        public String call(String toolInput) {
            invoked.set(true);
            return executeTool(() -> delegate.call(toolInput));
        }

        /** 标记后执行带上下文工具调用。 */
        @Override
        public String call(String toolInput, ToolContext toolContext) {
            invoked.set(true);
            return executeTool(() -> delegate.call(toolInput, toolContext));
        }

        /** 执行工具并强制并发、输出和数据外发预算。 */
        private String executeTool(Supplier<String> invocation) {
            AtomicInteger active = contextCounter("activeToolCalls");
            int max = contextNumber("maxConcurrentTools", 1).intValue();
            if (active != null && active.incrementAndGet() > Math.max(1, max)) {
                active.decrementAndGet();
                throw budgetError("AI_CONCURRENT_TOOL_BUDGET_EXCEEDED", "并发工具预算已耗尽");
            }
            try {
                return enforceToolOutput(invocation.get());
            } finally {
                if (active != null) active.decrementAndGet();
            }
        }

        /** 工具结果再次进入外部模型前追加真实数据分类并执行统一外发策略。 */
        private String enforceToolOutput(String output) {
            String toolName = delegate.getToolDefinition().name();
            if (toolName != null && toolName.contains("agent_input_request")) {
                // 等待输入是本地控制信号，当前 Provider 已被取消，不会继续接收该结果。
                return output;
            }
            long outputBytes = output == null ? 0L : output.getBytes(StandardCharsets.UTF_8).length;
            if (outputBytes > contextNumber("maxToolOutputBytes", Long.MAX_VALUE).longValue()) {
                throw budgetError("AI_TOOL_OUTPUT_BUDGET_EXCEEDED", "工具输出超过任务预算");
            }
            if (dataEgressGuard == null) {
                return output;
            }
            Context current = request.egressContext();
            List<Item> items = new ArrayList<>(current.items());
            DataType dataType = toolOutputType(toolName);
            items.add(Item.fromText(
                    dataType, Classification.CONFIRMATION_REQUIRED,
                    toolName, true, output));
            try {
                dataEgressGuard.enforce(request.withEgressContext(new Context(
                        current.taskId(), current.conversationId(), current.stepId(),
                        current.modelCallId(), "工具结果返回模型", items,
                        current.confirmationId())));
            } catch (ConfirmationRequiredException confirmation) {
                // Provider 可能延迟传播工具回调异常，确认卡片必须在守卫判定时立即发送。
                publishDataEgressConfirmation(confirmation);
                throw confirmation;
            }
            return output;
        }

        /** 把工具结果外发确认立即交给当前对话流发布。 */
        private void publishDataEgressConfirmation(ConfirmationRequiredException confirmation) {
            Object value = request.toolContext().get("dataEgressConfirmationPublisher");
            if (!(value instanceof Consumer<?> consumer)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Consumer<ConfirmationRequiredException> publisher =
                    (Consumer<ConfirmationRequiredException>) consumer;
            publisher.accept(confirmation);
        }

        /** 读取共享工具计数器。 */
        private AtomicInteger contextCounter(String key) {
            Object value = request.toolContext().get(key);
            return value instanceof AtomicInteger counter ? counter : null;
        }

        /** 读取共享数值预算。 */
        private Number contextNumber(String key, Number fallback) {
            Object value = request.toolContext().get(key);
            return value instanceof Number number ? number : fallback;
        }
    }

    /** 根据工具定义识别外发数据类型，避免把本机输出和设备日志统称为用户消息。 */
    private DataType toolOutputType(String toolName) {
        String value = toolName == null ? "" : toolName.toLowerCase();
        if (value.contains("local") || value.contains("shell") || value.contains("desktop")) {
            return DataType.LOCAL_COMMAND_OUTPUT;
        }
        if (value.contains("log")) {
            return DataType.DEVICE_LOG;
        }
        if (value.contains("app") || value.contains("package")) {
            return DataType.APPLICATION_LIST;
        }
        return DataType.TOOL_OUTPUT;
    }

    /**
     * 处理 Spring AI 流式响应；读取 ChatResponse 才能拿到 finishReason，用于识别模型输出被 max_tokens 截断。
     *
     * @param response Spring AI 响应片段
     * @param listener 业务流式监听器
     * @param finishReason 最后一次非空结束原因
     */
    private void handleStreamResponse(
            ChatResponse response,
            AiProviderStreamListener listener,
            AtomicReference<String> finishReason) {
        String content = streamContent(response);
        if (content != null) {
            listener.onContent(content);
        }
        String reason = streamFinishReason(response);
        if (reason != null && !reason.isBlank()) {
            // by AI.Coding: 多数 Provider 只在最后一个 chunk 放 finishReason，必须缓存到完成回调再统一处理。
            finishReason.set(reason);
        }
    }

    /**
     * 提取流式文本片段，空字符串会继续传递给上层，由业务层决定是否忽略。
     *
     * @param response Spring AI 响应片段
     * @return 文本片段，无法提取时返回 null
     */
    private String streamContent(ChatResponse response) {
        Generation generation = firstGeneration(response);
        return generation == null || generation.getOutput() == null ? null : generation.getOutput().getText();
    }

    /**
     * 提取模型完成原因。
     *
     * @param response Spring AI 响应片段
     * @return 完成原因，无法提取时返回空字符串
     */
    private String streamFinishReason(ChatResponse response) {
        Generation generation = firstGeneration(response);
        ChatGenerationMetadata metadata = generation == null ? null : generation.getMetadata();
        return metadata == null ? "" : metadata.getFinishReason();
    }

    /**
     * 安全读取第一个 Generation，兼容 Provider 发送空元数据 chunk 的情况。
     *
     * @param response Spring AI 响应片段
     * @return 第一个 Generation，缺失时返回 null
     */
    private Generation firstGeneration(ChatResponse response) {
        List<Generation> generations = response == null ? List.of() : response.getResults();
        return generations == null || generations.isEmpty() ? null : generations.get(0);
    }

    /**
     * 根据运行时配置创建 OpenAI-compatible ChatModel；国产模型通过 baseUrl 和 model 适配。
     *
     * @param config 运行时配置
     * @param maxTokens 最大输出 token
     * @param temperature 温度参数
     * @return Spring AI ChatModel
     */
    private OpenAiChatModel chatModel(
            AiRuntimeConfig config, int maxTokens, double temperature, Duration timeout) {
        AiProviderEndpoint endpoint = endpointResolver.resolve(config);
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(endpoint.baseUrl())
                // 不同厂商的 baseUrl 是否自带 /v1、/v4 不一致，路径必须由后端统一解析后显式传入。
                .completionsPath(endpoint.completionsPath())
                .embeddingsPath(endpoint.embeddingsPath())
                .apiKey(config.apiKey())
                .restClientBuilder(restClientBuilder(timeout))
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.model())
                .maxTokens(maxTokens)
                .temperature(temperature)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    /**
     * 请求模型列表接口并提取模型 ID。
     *
     * @param config 临时运行时配置
     * @param endpoint Provider 端点
     * @param modelsPath 模型列表路径
     * @return 模型 ID 列表
     */
    private List<String> fetchModelIds(AiRuntimeConfig config, AiProviderEndpoint endpoint, String modelsPath) {
        JsonNode payload = restClientBuilder().build()
                .get()
                .uri(endpoint.baseUrl() + modelsPath)
                .headers(headers -> bearerHeaders(headers, config.apiKey()))
                .retrieve()
                .body(JsonNode.class);
        List<String> models = parseModelIds(payload);
        if (models.isEmpty()) {
            throw new BusinessException("AI_MODEL_LIST_EMPTY", "Provider 未返回可用模型", HttpStatus.BAD_GATEWAY, endpoint.baseUrl() + modelsPath);
        }
        return models;
    }

    /**
     * 设置模型列表接口请求头；API Key 只放入请求头，不写入日志或响应。
     *
     * @param headers HTTP 请求头
     * @param apiKey API Key
     */
    private void bearerHeaders(HttpHeaders headers, String apiKey) {
        headers.setBearerAuth(apiKey);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    }

    /**
     * 解析 OpenAI-compatible 模型列表响应，兼容 data/models 数组和字符串数组形态。
     *
     * @param payload Provider 原始响应
     * @return 去重后的模型 ID 列表
     */
    List<String> parseModelIds(JsonNode payload) {
        Set<String> models = new LinkedHashSet<>();
        collectModelIds(payload == null ? null : payload.get("data"), models);
        collectModelIds(payload == null ? null : payload.get("models"), models);
        collectModelIds(payload, models);
        return new ArrayList<>(models);
    }

    /**
     * 从数组节点中收集模型 ID；对象优先取 id 字段，字符串节点直接作为模型名。
     *
     * @param node 候选数组节点
     * @param models 模型 ID 集合
     */
    private void collectModelIds(JsonNode node, Set<String> models) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            String modelId = item.isTextual() ? item.asText() : item.path("id").asText("");
            if (!modelId.isBlank()) {
                models.add(modelId);
            }
        }
    }

    /**
     * 对常见 OpenAI-compatible 差异做一次受控兜底：host-only 地址在 /v1/models 和 /models 间切换。
     *
     * @param config 临时运行时配置
     * @param endpoint Provider 端点
     * @param original 原始异常
     * @return 兜底成功时返回模型列表，失败返回空列表
     */
    private List<String> tryFallbackModelPath(AiRuntimeConfig config, AiProviderEndpoint endpoint, RestClientResponseException original) {
        if (original.getStatusCode().value() != 404) {
            return List.of();
        }
        String fallbackPath = fallbackModelsPath(endpoint);
        if (fallbackPath.isBlank()) {
            return List.of();
        }
        try {
            return fetchModelIds(config, endpoint, fallbackPath);
        } catch (RestClientException | BusinessException ignored) {
            // 兜底路径只用于兼容 Provider 差异，最终错误仍保留原始路径的诊断信息。
            return List.of();
        }
    }

    /**
     * 计算模型列表兜底路径，避免已经带 /v1、/v2、/v4 的 baseUrl 被重复拼接版本号。
     *
     * @param endpoint Provider 端点
     * @return 兜底路径，无需兜底时返回空字符串
     */
    private String fallbackModelsPath(AiProviderEndpoint endpoint) {
        if ("/v1/models".equals(endpoint.modelsPath())) {
            return "/models";
        }
        if ("/models".equals(endpoint.modelsPath()) && !endpoint.baseUrl().matches(".*/v\\d+$")) {
            return "/v1/models";
        }
        return "";
    }

    /**
     * 创建带主动超时的 RestClient，避免 Provider 长时间无响应拖住 AI 请求。
     *
     * @return RestClient Builder
     */
    private RestClient.Builder restClientBuilder() {
        return restClientBuilder(PROVIDER_TIMEOUT);
    }

    /**
     * 创建受任务剩余预算限制的 RestClient。
     *
     * @param timeout 当前调用最大时长
     * @return RestClient Builder
     */
    private RestClient.Builder restClientBuilder(Duration timeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration effective = timeout.compareTo(PROVIDER_TIMEOUT) < 0 ? timeout : PROVIDER_TIMEOUT;
        requestFactory.setConnectTimeout(effective);
        requestFactory.setReadTimeout(effective);
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * 将 Provider 异常映射为稳定业务错误，并记录脱敏观测事件。
     *
     * @param request Provider 请求
     * @param ex 原始异常
     * @param started 开始时间
     * @return 业务异常
     */
    private BusinessException providerError(AiProviderRequest request, RuntimeException ex, long started) {
        BusinessException businessError = findBusinessException(ex);
        if (businessError != null) {
            // 工具回调会被 Spring AI 包装，安全确认和预算异常必须保留原业务语义。
            return businessError;
        }
        long elapsed = System.currentTimeMillis() - started;
        String detail = masker.maskText(ex.getMessage());
        record(request, false, detail, elapsed);
        return providerError(detail);
    }

    /**
     * 从框架包装异常链中恢复原始业务异常。
     *
     * @param error Spring AI 或 Reactor 返回的异常
     * @return 原始业务异常，不存在时返回 null
     */
    BusinessException findBusinessException(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof BusinessException businessException) {
                return businessException;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 将模型列表异常映射为稳定业务错误，并记录脱敏观测事件。
     *
     * @param config 临时运行时配置
     * @param ex 原始异常
     * @param started 开始时间
     * @return 业务异常
     */
    private BusinessException providerModelListError(AiRuntimeConfig config, RuntimeException ex, long started) {
        long elapsed = System.currentTimeMillis() - started;
        String detail = masker.maskText(ex.getMessage());
        record(config, false, detail, elapsed);
        return providerError(detail);
    }

    /**
     * 按 Provider 错误摘要映射通用业务错误码。
     *
     * @param detail 脱敏错误摘要
     * @return 业务异常
     */
    private BusinessException providerError(String detail) {
        String lower = detail.toLowerCase();
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("invalid api key")) {
            return new BusinessException("AI_PROVIDER_AUTH_FAILED", "AI Provider 鉴权失败", HttpStatus.UNAUTHORIZED, detail);
        }
        if (lower.contains("429") || lower.contains("rate limit")) {
            return new BusinessException("AI_PROVIDER_RATE_LIMITED", "AI Provider 请求被限流", HttpStatus.TOO_MANY_REQUESTS, detail);
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return new BusinessException("AI_PROVIDER_TIMEOUT", "AI Provider 请求超时", HttpStatus.GATEWAY_TIMEOUT, detail);
        }
        return new BusinessException("AI_PROVIDER_RESPONSE_INVALID", "AI Provider 调用失败", HttpStatus.BAD_GATEWAY, detail);
    }

    /**
     * 完成流式调用并记录成功事件。
     *
     * @param request Provider 请求
     * @param listener 流式监听器
     * @param started 开始时间
     */
    private void completeStream(
            AiProviderRequest request,
            AiProviderStreamListener listener,
            long started,
            String finishReason) {
        long elapsed = System.currentTimeMillis() - started;
        modelRouter.recordSuccess(request.config());
        record(request, true, "", elapsed);
        listener.onComplete(finishReason);
    }

    /**
     * 将 Reactor 回调中的 Throwable 规范化为 RuntimeException，复用统一错误映射。
     *
     * @param error 原始错误
     * @return RuntimeException
     */
    private RuntimeException toRuntimeException(Throwable error) {
        if (error instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(error);
    }

    /** 单次 Provider 尝试及其可观测路由原因。by AI.Coding */
    private record ProviderAttempt(AiProviderRequest request, String reason) {
    }

    /**
     * 记录 Provider 调用摘要，避免业务日志里出现完整 Prompt 或日志正文。
     *
     * @param request Provider 请求
     * @param success 是否成功
     * @param error 错误摘要
     * @param elapsedMillis 耗时毫秒
     */
    private void record(AiProviderRequest request, boolean success, String error, long elapsedMillis) {
        record(request.config(), success, error, elapsedMillis);
    }

    /**
     * 记录 Provider 调用摘要，模型列表请求没有具体模型时使用固定占位符。
     *
     * @param config 运行时配置
     * @param success 是否成功
     * @param error 错误摘要
     * @param elapsedMillis 耗时毫秒
     */
    private void record(AiRuntimeConfig config, boolean success, String error, long elapsedMillis) {
        observationRecorder.record(new AiObservationEvent(
                config.provider().getValue(),
                config.model().isBlank() ? "<model-list>" : config.model(),
                success,
                elapsedMillis,
                error));
    }
}
