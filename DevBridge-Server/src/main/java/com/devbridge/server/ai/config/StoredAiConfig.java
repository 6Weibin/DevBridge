package com.devbridge.server.ai.config;

import java.util.List;
import java.util.Map;

/**
 * 本地落盘 AI 配置集合，每个 Provider 独立保存模型配置，授权规则作为全局安全策略保存。
 *
 * <p>by AI.Coding</p>
 *
 * @param activeProvider 当前生效 Provider
 * @param providers Provider 配置表
 * @param systemPrompt 系统提示词
 * @param localShellAuthorizations Local Shell MCP 命令授权规则
 */
record StoredAiConfig(
        String activeProvider,
        Map<String, StoredProviderConfig> providers,
        String systemPrompt,
        List<AiCommandAuthorizationRule> localShellAuthorizations) {
}
