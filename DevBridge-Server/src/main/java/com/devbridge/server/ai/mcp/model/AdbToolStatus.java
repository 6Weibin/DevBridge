package com.devbridge.server.ai.mcp.model;

/**
 * ADB MCP 工具执行状态，保持前后端和模型返回结构一致。
 *
 * <p>by AI.Coding</p>
 */
public enum AdbToolStatus {
    SUCCESS,
    FAILED,
    CONFIRMATION_REQUIRED,
    CANCELED
}
