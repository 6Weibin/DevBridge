package com.devbridge.server.ai.mcp.model;

/**
 * ADB MCP 工具统一结果，所有字段先脱敏再返回给模型和前端。
 *
 * <p>by AI.Coding</p>
 *
 * @param status 工具状态
 * @param stdout 标准输出
 * @param stderr 错误输出
 * @param exitCode 退出码
 * @param timedOut 是否超时
 * @param durationMillis 耗时毫秒
 * @param truncated 输出是否截断
 * @param riskLevel 实际风险
 * @param confirmationRequired 是否需要确认
 * @param confirmationToken 确认令牌
 * @param message 用户可读摘要
 * @param errorCode 稳定错误码
 * @param toolTitle 工具标题，用于前端区分 ADB MCP、Local Shell MCP 等工具族
 * @param commandSummary 最终执行命令摘要，已由调用方脱敏或控制长度
 */
public record AdbMcpToolResult(
        AdbToolStatus status,
        String stdout,
        String stderr,
        Integer exitCode,
        boolean timedOut,
        long durationMillis,
        boolean truncated,
        AdbRiskLevel riskLevel,
        boolean confirmationRequired,
        String confirmationToken,
        String message,
        String errorCode,
        String toolTitle,
        String commandSummary) {

    /**
     * 兼容旧调用点的构造器；未显式设置元数据时由前端按兜底规则展示。
     */
    public AdbMcpToolResult(
            AdbToolStatus status,
            String stdout,
            String stderr,
            Integer exitCode,
            boolean timedOut,
            long durationMillis,
            boolean truncated,
            AdbRiskLevel riskLevel,
            boolean confirmationRequired,
            String confirmationToken,
            String message,
            String errorCode) {
        this(status, stdout, stderr, exitCode, timedOut, durationMillis, truncated, riskLevel, confirmationRequired, confirmationToken, message, errorCode, "", "");
    }

    /**
     * 返回带工具元数据的新结果；用不可变 record 保持工具结果在线程间传递安全。
     *
     * @param toolTitle 工具标题
     * @param commandSummary 最终执行命令摘要
     * @return 带元数据的工具结果
     */
    public AdbMcpToolResult withToolMetadata(String toolTitle, String commandSummary) {
        return new AdbMcpToolResult(
                status,
                stdout,
                stderr,
                exitCode,
                timedOut,
                durationMillis,
                truncated,
                riskLevel,
                confirmationRequired,
                confirmationToken,
                message,
                errorCode,
                safe(toolTitle),
                safe(commandSummary));
    }

    /**
     * 创建成功结果，集中补齐默认字段。
     *
     * @param stdout 标准输出
     * @param stderr 错误输出
     * @param exitCode 退出码
     * @param durationMillis 耗时
     * @param truncated 是否截断
     * @param riskLevel 风险级别
     * @return 成功结果
     */
    public static AdbMcpToolResult success(
            String stdout,
            String stderr,
            int exitCode,
            long durationMillis,
            boolean truncated,
            AdbRiskLevel riskLevel) {
        return new AdbMcpToolResult(
                AdbToolStatus.SUCCESS,
                stdout,
                stderr,
                exitCode,
                false,
                durationMillis,
                truncated,
                riskLevel,
                false,
                "",
                "MCP 工具执行完成",
                "",
                "",
                "");
    }

    /**
     * 创建失败结果，失败信息必须已经脱敏。
     *
     * @param errorCode 稳定错误码
     * @param message 用户可读摘要
     * @param stderr 错误输出摘要
     * @param exitCode 退出码
     * @param riskLevel 风险级别
     * @return 失败结果
     */
    public static AdbMcpToolResult failed(
            String errorCode,
            String message,
            String stderr,
            Integer exitCode,
            AdbRiskLevel riskLevel) {
        return new AdbMcpToolResult(
                AdbToolStatus.FAILED,
                "",
                stderr,
                exitCode,
                false,
                0,
                false,
                riskLevel,
                false,
                "",
                message,
                errorCode,
                "",
                "");
    }

    /**
     * 创建需要确认的结果，确保真实 ADB 命令不会在确认前执行。
     *
     * @param token 确认令牌
     * @param message 风险说明
     * @param riskLevel 风险级别
     * @return 待确认结果
     */
    public static AdbMcpToolResult confirmationRequired(String token, String message, AdbRiskLevel riskLevel) {
        return new AdbMcpToolResult(
                AdbToolStatus.CONFIRMATION_REQUIRED,
                "",
                "",
                null,
                false,
                0,
                false,
                riskLevel,
                true,
                token,
                message,
                "",
                "",
                "");
    }

    /**
     * 创建取消结果，用于用户取消确认或终止运行中工具。
     *
     * @param message 用户可读摘要
     * @return 取消结果
     */
    public static AdbMcpToolResult canceled(String message) {
        return new AdbMcpToolResult(
                AdbToolStatus.CANCELED,
                "",
                "",
                null,
                false,
                0,
                false,
                AdbRiskLevel.LOW,
                false,
                "",
                message,
                "",
                "",
                "");
    }

    /**
     * 元数据字段统一兜底为空字符串，避免前端出现 null 文本。
     *
     * @param value 原始值
     * @return 安全文本
     */
    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
