package com.devbridge.server.ai.provider;

/**
 * AI Provider 流式请求句柄，用于前端取消或连接关闭时释放底层订阅。
 *
 * <p>by AI.Coding</p>
 */
public interface AiProviderStreamHandle {

    /**
     * 取消底层 Provider 流，避免客户端断开后继续消耗模型请求。
     */
    void cancel();
}
