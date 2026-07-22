package com.devbridge.server.ai.mcp.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

/**
 * ADB MCP 工具定义，字段与 MCP Tool schema 和前端工具目录展示保持一致。
 *
 * <p>by AI.Coding</p>
 *
 * @param name 工具名称
 * @param description 工具说明
 * @param inputSchema 输入 JSON Schema
 * @param outputSchema 输出 JSON Schema
 * @param defaultRiskLevel 默认风险级别
 * @param timeout 工具超时时间
 * @param outputLimit 输出限制
 * @param requiresDevice 是否需要目标设备
 */
public record AdbMcpToolDefinition(
        String name,
        String description,
        JsonNode inputSchema,
        JsonNode outputSchema,
        AdbRiskLevel defaultRiskLevel,
        Duration timeout,
        AdbOutputLimit outputLimit,
        boolean requiresDevice) {
}
