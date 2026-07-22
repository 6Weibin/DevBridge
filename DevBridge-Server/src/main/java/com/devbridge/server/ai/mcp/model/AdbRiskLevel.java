package com.devbridge.server.ai.mcp.model;

/**
 * ADB MCP 工具风险级别，供确认、审计和前端展示共同使用。
 *
 * <p>by AI.Coding</p>
 */
public enum AdbRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /**
     * 判断当前风险是否需要用户确认。
     *
     * @return 高风险及以上返回 true
     */
    public boolean requiresConfirmation() {
        return this == HIGH || this == CRITICAL;
    }
}
