package com.devbridge.server.ai.provider;

import java.util.List;

/**
 * AI Provider 模型列表响应，供前端配置页直接展示候选模型。
 *
 * <p>by AI.Coding</p>
 *
 * @param provider Provider 类型
 * @param models 模型 ID 列表
 */
public record AiModelListResponse(String provider, List<String> models) {
}
