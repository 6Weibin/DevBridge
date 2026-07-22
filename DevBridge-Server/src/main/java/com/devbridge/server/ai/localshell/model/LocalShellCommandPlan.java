package com.devbridge.server.ai.localshell.model;

import com.devbridge.server.ai.mcp.model.AdbOutputLimit;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Local Shell 命令执行计划，保存已校验的命令、目录、环境和输出限制。
 *
 * <p>by AI.Coding</p>
 *
 * @param requestId 请求 ID
 * @param conversationId 对话 ID
 * @param toolName 工具名称
 * @param mode 执行模式
 * @param command 原始命令摘要
 * @param commandLine shell 模式命令
 * @param argv 参数数组模式命令
 * @param workingDirectory 工作目录
 * @param environment 受控环境变量
 * @param timeout 超时时间
 * @param outputLimit 输出限制
 * @param streaming 是否建议流式执行
 */
public record LocalShellCommandPlan(
        String requestId,
        String conversationId,
        String toolName,
        LocalShellCommandMode mode,
        String command,
        String commandLine,
        List<String> argv,
        Path workingDirectory,
        Map<String, String> environment,
        Duration timeout,
        AdbOutputLimit outputLimit,
        boolean streaming) {

    /**
     * 返回命令摘要，用于确认令牌绑定和审计。
     *
     * @return 命令摘要
     */
    public String commandSummary() {
        if (mode == LocalShellCommandMode.ARGV) {
            return String.join(" ", argv == null ? List.of() : argv);
        }
        return commandLine == null ? "" : commandLine;
    }
}
