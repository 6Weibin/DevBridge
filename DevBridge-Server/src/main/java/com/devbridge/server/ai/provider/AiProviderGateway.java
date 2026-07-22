package com.devbridge.server.ai.provider;

import com.devbridge.server.ai.config.AiRuntimeConfig;

/**
 * AI Provider 统一调用边界，业务层不直接依赖 Spring AI 具体实现。
 *
 * <p>by AI.Coding</p>
 */
public interface AiProviderGateway {

    /**
     * 拉取 Provider 当前可用模型列表。
     *
     * @param config 临时运行时配置
     * @return 模型列表响应
     */
    AiModelListResponse listModels(AiRuntimeConfig config);

    /**
     * 发起文本对话请求。
     *
     * @param request Provider 请求
     * @return Provider 响应
     */
    AiProviderResponse chat(AiProviderRequest request);

    /**
     * 发起流式文本对话请求；Provider 实现负责把框架流转换为通用回调。
     *
     * @param request Provider 请求
     * @param listener 流式事件监听器
     * @return 流式请求句柄
     */
    AiProviderStreamHandle stream(AiProviderRequest request, AiProviderStreamListener listener);
}
