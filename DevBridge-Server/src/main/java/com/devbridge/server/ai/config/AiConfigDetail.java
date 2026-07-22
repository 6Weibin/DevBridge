package com.devbridge.server.ai.config;

import java.time.Instant;
import java.util.List;

/**
 * AI 配置详情响应；用于本机配置页回填用户已保存的配置。
 *
 * <p>by AI.Coding</p>
 *
 * @param configured 是否已配置
 * @param provider Provider 类型
 * @param apiUrl API 基础地址
 * @param apiKey API Key 明文
 * @param model 模型名称
 * @param systemPrompt 用户偏好提示词；字段名为兼容旧前端保留
 * @param localShellAuthorizations Local Shell MCP 命令授权规则
 * @param updatedAt 更新时间
 */
public record AiConfigDetail(
        boolean configured,
        String provider,
        String apiUrl,
        String apiKey,
        String model,
        String systemPrompt,
        List<AiCommandAuthorizationRule> localShellAuthorizations,
        Instant updatedAt) {

    /**
     * 返回未配置详情，避免前端处理 null。
     *
     * @return 未配置详情
     */
    public static AiConfigDetail empty() {
        return new AiConfigDetail(false, "", "", "", "", AiPromptDefaults.DEFAULT_USER_PREFERENCE_PROMPT, List.of(), null);
    }
}
