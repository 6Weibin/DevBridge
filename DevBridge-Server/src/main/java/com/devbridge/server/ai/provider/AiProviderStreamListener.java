package com.devbridge.server.ai.provider;

import com.devbridge.server.model.BusinessException;

/**
 * AI Provider 流式响应监听器，隔离业务层与具体 Spring AI Flux 类型。
 *
 * <p>by AI.Coding</p>
 */
public interface AiProviderStreamListener {

    /**
     * 接收模型增量文本。
     *
     * @param content 增量文本
     */
    void onContent(String content);

    /**
     * Provider 正常结束时回调。
     */
    void onComplete();

    /**
     * Provider 正常结束时回调，并携带模型返回的结束原因。
     *
     * @param finishReason 模型结束原因，例如 STOP、LENGTH，可能为空
     */
    default void onComplete(String finishReason) {
        // by AI.Coding: 保留旧回调入口，避免现有测试和非 Spring Provider 实现被完成原因扩展侵入。
        onComplete();
    }

    /**
     * Provider 失败时回调，错误已映射为统一业务异常。
     *
     * @param error 业务异常
     */
    void onError(BusinessException error);
}
