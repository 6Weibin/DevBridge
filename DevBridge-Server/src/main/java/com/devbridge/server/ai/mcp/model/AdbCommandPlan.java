package com.devbridge.server.ai.mcp.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * ADB 命令执行计划，保存已经规范化的参数数组和执行上下文。
 *
 * <p>by AI.Coding</p>
 *
 * @param requestId 请求 ID
 * @param conversationId 对话 ID
 * @param toolName 工具名称
 * @param adbArguments 不含 adb 可执行文件的参数数组
 * @param deviceSerial 目标设备序列号
 * @param requiresDevice 是否需要设备
 * @param environment 受控环境变量
 * @param timeout 超时时间
 * @param outputLimit 输出限制
 * @param streaming 是否建议流式执行
 */
public record AdbCommandPlan(
        String requestId,
        String conversationId,
        String toolName,
        List<String> adbArguments,
        String deviceSerial,
        boolean requiresDevice,
        Map<String, String> environment,
        Duration timeout,
        AdbOutputLimit outputLimit,
        boolean streaming) {

    /**
     * 返回完整 adb 参数摘要，用于确认令牌绑定和审计。
     *
     * @return 参数摘要
     */
    public String argumentSummary() {
        return String.join(" ", adbArguments == null ? List.of() : adbArguments);
    }
}
