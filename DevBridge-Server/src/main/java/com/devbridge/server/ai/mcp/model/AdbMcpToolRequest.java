package com.devbridge.server.ai.mcp.model;

import java.util.Map;

/**
 * ADB MCP 工具调用请求，统一承载模型工具调用、REST 调用和确认后二次执行。
 *
 * <p>by AI.Coding</p>
 *
 * @param toolName 工具名称
 * @param conversationId 对话 ID
 * @param deviceSerial 目标设备序列号
 * @param arguments 工具参数
 * @param confirmationToken 确认令牌
 * @param requestId 请求 ID
 */
public record AdbMcpToolRequest(
        String toolName,
        String conversationId,
        String deviceSerial,
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
