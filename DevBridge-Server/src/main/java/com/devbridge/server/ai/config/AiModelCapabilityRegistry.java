package com.devbridge.server.ai.config;

import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 模型能力注册表，根据 Provider 和模型名称提供保守的运行能力与预算上限。
 *
 * <p>注册表不访问网络，也不保存第二份模型配置；未知聊天模型使用兼容上限，明确的非聊天模型禁止进入对话和工具任务。</p>
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AiModelCapabilityRegistry {

    private static final int DEFAULT_CONTEXT_TOKENS = 32_768;
    private static final int DEFAULT_OUTPUT_TOKENS = 8_192;
    private static final int DEFAULT_PLAN_STEPS = 32;
    private static final long DEFAULT_TOOL_OUTPUT_BYTES = 8L * 1024L * 1024L;
    private static final int DEFAULT_CONCURRENT_TOOLS = 1;
    private static final long DEFAULT_COST_MICROS = 5_000_000L;

    /**
     * 解析当前模型能力。
     *
     * @param provider Provider 类型
     * @param model 模型名称
     * @return 模型能力快照
     */
    public ModelCapability resolve(AiProviderType provider, String model) {
        String modelId = model == null ? "" : model.trim();
        String lower = modelId.toLowerCase(Locale.ROOT);
        boolean chat = !nonChatModel(lower);
        boolean toolCalling = chat && !reasoningOnlyModel(lower);
        int contextTokens = contextTokens(provider, lower);
        int outputTokens = outputTokens(provider, lower);
        return new ModelCapability(
                modelId,
                chat,
                toolCalling,
                chat,
                chat && multimodalModel(lower),
                chat,
                new ModelLimits(
                        contextTokens, outputTokens, 24, 16, 2, 300,
                        ModelExecutionLimits.defaults()));
    }

    /**
     * 判断候选模型能否承接当前调用所需能力。
     *
     * @param required 原模型能力
     * @param candidate 候选模型能力
     * @return 能力满足返回 true
     */
    public boolean compatible(ModelCapability required, ModelCapability candidate) {
        if (required == null || candidate == null || !candidate.chat()) {
            return false;
        }
        return (!required.toolCalling() || candidate.toolCalling())
                && (!required.streaming() || candidate.streaming())
                && (!required.multimodal() || candidate.multimodal())
                && (!required.jsonOutput() || candidate.jsonOutput());
    }

    /**
     * 明确排除 embedding、重排、语音、图像和视频等非聊天模型。
     */
    private boolean nonChatModel(String model) {
        return containsAny(model,
                "embedding", "embed-", "rerank", "tts", "whisper", "asr",
                "image", "video", "moderation", "text-moderation");
    }

    /**
     * 推理专用模型默认不暴露工具，避免对不确定的 Tool Calling 支持做乐观假设。
     */
    private boolean reasoningOnlyModel(String model) {
        return containsAny(model, "reasoner", "deepseek-r1", "qwq", "o1-mini");
    }

    /**
     * 识别视觉或全模态模型。
     */
    private boolean multimodalModel(String model) {
        return containsAny(model, "vision", "-vl", "vl-", "4o", "omni", "gpt-5");
    }

    /**
     * 按常见模型族给出保守上下文窗口，未知模型使用部署可覆盖的默认窗口。
     */
    private int contextTokens(AiProviderType provider, String model) {
        if (model.contains("qwen-long")) {
            return 1_000_000;
        }
        if (containsAny(model, "gpt-5", "gpt-4.1", "gpt-4o", "qwen", "glm-4", "ernie")) {
            return 128_000;
        }
        if (provider == AiProviderType.DEEPSEEK || model.contains("deepseek")) {
            return 64_000;
        }
        return DEFAULT_CONTEXT_TOKENS;
    }

    /**
     * 按模型族给出单次回复上限，避免直接使用厂商可能过大的理论值。
     */
    private int outputTokens(AiProviderType provider, String model) {
        if (containsAny(model, "gpt-5", "gpt-4.1", "qwen", "glm-4")) {
            return 16_384;
        }
        if (provider == AiProviderType.DEEPSEEK || model.contains("deepseek")) {
            return 8_192;
        }
        return DEFAULT_OUTPUT_TOKENS;
    }

    /**
     * 判断文本是否包含任一模型特征。
     */
    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** 模型能力快照。by AI.Coding */
    public record ModelCapability(
            String modelId,
            boolean chat,
            boolean toolCalling,
            boolean streaming,
            boolean multimodal,
            boolean jsonOutput,
            ModelLimits limits) {

        /** 兼容空模型名和空限制。 */
        public ModelCapability {
            modelId = modelId == null ? "" : modelId;
            limits = limits == null ? ModelLimits.defaults() : limits;
        }
    }

    /** 单任务模型与工具预算上限。by AI.Coding */
    public record ModelLimits(
            int contextWindowTokens,
            int maxOutputTokens,
            int maxToolCalls,
            int maxModelCalls,
            int maxRetries,
            int maxDurationSeconds,
            ModelExecutionLimits execution) {

        /** 空扩展预算使用保守默认值。 */
        public ModelLimits {
            execution = execution == null ? ModelExecutionLimits.defaults() : execution;
        }

        /** 兼容旧模型注册项并补齐新增任务预算。 */
        public ModelLimits(
                int contextWindowTokens,
                int maxOutputTokens,
                int maxToolCalls,
                int maxModelCalls,
                int maxRetries,
                int maxDurationSeconds) {
            this(contextWindowTokens, maxOutputTokens, maxToolCalls, maxModelCalls,
                    maxRetries, maxDurationSeconds, ModelExecutionLimits.defaults());
        }

        /** 返回最大计划步骤数。 */
        public int maxPlanSteps() { return execution.maxPlanSteps(); }

        /** 返回单任务工具输出预算。 */
        public long maxToolOutputBytes() { return execution.maxToolOutputBytes(); }

        /** 返回最大并发工具数。 */
        public int maxConcurrentTools() { return execution.maxConcurrentTools(); }

        /** 返回任务成本预算，单位为微货币单位。 */
        public long maxCostMicros() { return execution.maxCostMicros(); }

        /** 创建兼容默认限制。 */
        public static ModelLimits defaults() {
            return new ModelLimits(DEFAULT_CONTEXT_TOKENS, DEFAULT_OUTPUT_TOKENS, 24, 16, 2, 300);
        }
    }

    /** 模型执行阶段扩展预算，避免主限制对象构造参数过多。by AI.Coding */
    public record ModelExecutionLimits(
            int maxPlanSteps,
            long maxToolOutputBytes,
            int maxConcurrentTools,
            long maxCostMicros) {

        /** 创建保守默认扩展预算。 */
        public static ModelExecutionLimits defaults() {
            return new ModelExecutionLimits(
                    DEFAULT_PLAN_STEPS, DEFAULT_TOOL_OUTPUT_BYTES,
                    DEFAULT_CONCURRENT_TOOLS, DEFAULT_COST_MICROS);
        }
    }
}
