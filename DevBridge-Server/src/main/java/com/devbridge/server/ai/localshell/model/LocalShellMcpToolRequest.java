package com.devbridge.server.ai.localshell.model;

import java.util.Map;

/**
 * Local Shell MCP 工具调用请求，统一承载模型工具调用、REST 调用和确认后二次执行。
 *
 * <p>by AI.Coding</p>
 *
 * @param toolName 工具名称
 * @param conversationId 对话 ID
 * @param arguments 工具参数
 * @param confirmationToken 确认令牌
 * @param requestId 请求 ID
 */
public record LocalShellMcpToolRequest(
        String toolName,
        String conversationId,
        Map<String, Object> arguments,
        String confirmationToken,
        String requestId) {

    /**
     * 返回非空参数映射，避免调用方重复判空。
     *
     * @return 工具参数
     */
    public Map<String, Object> safeArguments() {
        return arguments == null ? Map.of() : arguments;
    }
}
