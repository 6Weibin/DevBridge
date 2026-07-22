package com.devbridge.server.ai.localshell.policy;

import com.devbridge.server.ai.localshell.model.LocalShellCommandMode;
import com.devbridge.server.ai.localshell.model.LocalShellCommandPlan;
import com.devbridge.server.ai.localshell.model.LocalShellMcpToolDefinition;
import com.devbridge.server.ai.localshell.model.LocalShellMcpToolRequest;
import com.devbridge.server.model.BusinessException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Local Shell 命令规划器，把工具 JSON 参数转换为受控执行计划。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class LocalShellCommandPlanner {

    private final LocalShellPolicyService policyService;

    /**
     * 注入安全策略。
     *
     * @param policyService 安全策略
     */
    public LocalShellCommandPlanner(LocalShellPolicyService policyService) {
        this.policyService = policyService;
    }

    /**
     * 规划 Local Shell 工具调用。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @return 命令计划
     */
    public LocalShellCommandPlan plan(LocalShellMcpToolRequest request, LocalShellMcpToolDefinition definition) {
        Map<String, Object> args = request.safeArguments();
        Path workdir = policyService.workingDirectory(text(args.get("workingDirectory"), ""));
        return switch (request.toolName()) {
            case "local_shell_pwd" -> fixedPlan(request, definition, workdir, List.of("pwd"));
            case "local_shell_list_dir" -> listDirPlan(request, definition, workdir, args);
            case "local_shell_read_text" -> readTextPlan(request, definition, workdir, args);
            default -> execPlan(request, definition, workdir, args);
        };
    }

    /**
     * 规划通用命令执行工具。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @param workdir 工作目录
     * @param args 工具参数
     * @return 命令计划
     */
    private LocalShellCommandPlan execPlan(
            LocalShellMcpToolRequest request,
            LocalShellMcpToolDefinition definition,
            Path workdir,
            Map<String, Object> args) {
        LocalShellCommandMode mode = mode(args.get("mode"));
        if (mode == LocalShellCommandMode.SHELL) {
            String commandLine = text(args.get("commandLine"), "");
            if (!StringUtils.hasText(commandLine)) {
                throw new BusinessException("LOCAL_SHELL_COMMAND_EMPTY", "本机 Shell 命令不能为空", HttpStatus.BAD_REQUEST, "");
            }
            return plan(request, definition, mode, commandLine, commandLine, List.of(), workdir, args);
        }
        List<String> argv = strings(args.get("argv"));
        if (argv.isEmpty()) {
            throw new BusinessException("LOCAL_SHELL_COMMAND_EMPTY", "本机命令 argv 不能为空", HttpStatus.BAD_REQUEST, "");
        }
        return plan(request, definition, mode, String.join(" ", argv), "", argv, workdir, args);
    }

    /**
     * 创建固定 argv 命令计划。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @param workdir 工作目录
     * @param argv 参数数组
     * @return 命令计划
     */
    private LocalShellCommandPlan fixedPlan(
            LocalShellMcpToolRequest request,
            LocalShellMcpToolDefinition definition,
            Path workdir,
            List<String> argv) {
        return plan(request, definition, LocalShellCommandMode.ARGV, String.join(" ", argv), "", argv, workdir, request.safeArguments());
    }

    /**
     * 规划目录列表命令。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @param workdir 工作目录
     * @param args 工具参数
     * @return 命令计划
     */
    private LocalShellCommandPlan listDirPlan(
            LocalShellMcpToolRequest request,
            LocalShellMcpToolDefinition definition,
            Path workdir,
            Map<String, Object> args) {
        String directory = text(args.get("directory"), ".");
        List<String> argv = isWindows() ? List.of("cmd.exe", "/d", "/s", "/c", "dir", directory) : List.of("ls", "-la", directory);
        return plan(request, definition, LocalShellCommandMode.ARGV, String.join(" ", argv), "", argv, workdir, args);
    }

    /**
     * 规划文本文件读取命令。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @param workdir 工作目录
     * @param args 工具参数
     * @return 命令计划
     */
    private LocalShellCommandPlan readTextPlan(
            LocalShellMcpToolRequest request,
            LocalShellMcpToolDefinition definition,
            Path workdir,
            Map<String, Object> args) {
        String filePath = text(args.get("filePath"), "");
        if (!StringUtils.hasText(filePath)) {
            throw new BusinessException("LOCAL_SHELL_COMMAND_EMPTY", "读取文件路径不能为空", HttpStatus.BAD_REQUEST, "");
        }
        List<String> argv = isWindows() ? List.of("cmd.exe", "/d", "/s", "/c", "type", filePath) : List.of("cat", filePath);
        return plan(request, definition, LocalShellCommandMode.ARGV, String.join(" ", argv), "", argv, workdir, args);
    }

    /**
     * 创建命令计划。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @param mode 执行模式
     * @param command 命令摘要
     * @param commandLine shell 命令行
     * @param argv 参数数组
     * @param workdir 工作目录
     * @param args 工具参数
     * @return 命令计划
     */
    private LocalShellCommandPlan plan(
            LocalShellMcpToolRequest request,
            LocalShellMcpToolDefinition definition,
            LocalShellCommandMode mode,
            String command,
            String commandLine,
            List<String> argv,
            Path workdir,
            Map<String, Object> args) {
        return new LocalShellCommandPlan(
                request.requestId(),
                request.conversationId(),
                request.toolName(),
                mode,
                command,
                commandLine,
                argv,
                workdir,
                policyService.environment(environment(args.get("environment"))),
                policyService.timeout(args.get("timeoutMillis")),
                definition.outputLimit(),
                false);
    }

    /**
     * 解析命令模式。
     *
     * @param value 原始模式
     * @return 命令模式
     */
    private LocalShellCommandMode mode(Object value) {
        return "SHELL".equalsIgnoreCase(text(value, "")) ? LocalShellCommandMode.SHELL : LocalShellCommandMode.ARGV;
    }

    /**
     * 读取字符串值。
     *
     * @param value 原始值
     * @param fallback 默认值
     * @return 字符串
     */
    private String text(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    /**
     * 读取字符串数组。
     *
     * @param value 原始值
     * @return 字符串数组
     */
    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && StringUtils.hasText(String.valueOf(item))) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    /**
     * 安全转换环境变量对象。
     *
     * @param value 原始值
     * @return 环境变量映射
     */
    private Map<String, Object> environment(Object value) {
        return value instanceof Map<?, ?> map
                ? map.entrySet().stream().collect(java.util.stream.Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue))
                : Map.of();
    }

    /**
     * 判断当前是否 Windows。
     *
     * @return Windows 返回 true
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
