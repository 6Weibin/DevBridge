package com.devbridge.server.ai.provider;

import com.devbridge.server.ai.config.AiRuntimeConfig;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Context;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;

/**
 * Provider 对话请求，封装模型配置和提示词。
 *
 * <p>by AI.Coding</p>
 *
 * @param config 运行时配置
 * @param productPrompt 后端维护的产品角色提示词
 * @param userPrompt 用户提示词
 * @param maxTokens 最大输出 token
 * @param temperature 温度参数
 * @param toolCallbacks 工具回调
 * @param toolContext 工具上下文
 * @param egressContext 模型数据外发上下文
 */
public record AiProviderRequest(
        AiRuntimeConfig config,
        String productPrompt,
        String userPrompt,
        int maxTokens,
        double temperature,
        List<ToolCallback> toolCallbacks,
        Map<String, Object> toolContext,
        Context egressContext) {

    /**
     * 固化集合并为旧调用方补充仅含普通用户文本的兼容外发上下文。
     */
    public AiProviderRequest {
        toolCallbacks = toolCallbacks == null ? List.of() : List.copyOf(toolCallbacks);
        toolContext = toolContext == null ? Map.of() : Map.copyOf(toolContext);
        egressContext = egressContext == null ? Context.publicUserText(userPrompt) : egressContext;
    }

    /**
     * 兼容带工具但尚未迁移到 Agent Task 的旧对话请求。
     *
     * @param config 运行时配置
     * @param productPrompt 产品提示词
     * @param userPrompt 用户提示词
     * @param maxTokens 最大输出 token
     * @param temperature 温度参数
     * @param toolCallbacks 工具回调
     * @param toolContext 工具上下文
     */
    public AiProviderRequest(
            AiRuntimeConfig config,
            String productPrompt,
            String userPrompt,
            int maxTokens,
            double temperature,
            List<ToolCallback> toolCallbacks,
            Map<String, Object> toolContext) {
        this(config, productPrompt, userPrompt, maxTokens, temperature, toolCallbacks, toolContext, null);
    }

    /**
     * 兼容无工具调用的 Provider 请求。
     *
     * @param config 运行时配置
     * @param productPrompt 后端维护的产品角色提示词
     * @param userPrompt 用户提示词
     * @param maxTokens 最大输出 token
     * @param temperature 温度参数
     */
    public AiProviderRequest(
            AiRuntimeConfig config,
            String productPrompt,
            String userPrompt,
            int maxTokens,
            double temperature) {
        this(config, productPrompt, userPrompt, maxTokens, temperature, List.of(), Map.of(), null);
    }

    /**
     * 复制请求并切换到能力兼容的备用 Provider，Prompt、工具和安全上下文保持不变。
     *
     * @param target 目标运行时配置
     * @return 备用 Provider 请求
     */
    public AiProviderRequest withConfig(AiRuntimeConfig target) {
        return new AiProviderRequest(
                target, productPrompt, userPrompt,
                Math.min(maxTokens, target.capability().limits().maxOutputTokens()), temperature,
                toolCallbacks, toolContext, egressContext);
    }

    /** 复制请求并替换本次真实外发数据摘要。 */
    public AiProviderRequest withEgressContext(Context context) {
        return new AiProviderRequest(
                config, productPrompt, userPrompt, maxTokens, temperature,
                toolCallbacks, toolContext, context);
    }
}
