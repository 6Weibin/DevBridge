package com.devbridge.server.ai.config;

import java.time.Instant;

/**
 * AI 配置状态响应；不包含 API Key 明文。
 *
 * <p>by AI.Coding</p>
 *
 * @param configured 是否已配置
 * @param provider Provider 类型
 * @param model 模型名称
 * @param apiUrlHost API 主机摘要
 * @param updatedAt 更新时间
 */
public record AiConfigStatus(
        boolean configured,
        String provider,
        String model,
        String apiUrlHost,
        Instant updatedAt) {

    /**
     * 返回未配置状态，前端据此打开配置弹窗。
     *
     * @return 未配置状态
     */
    public static AiConfigStatus empty() {
        return new AiConfigStatus(false, "", "", "", null);
    }
}
