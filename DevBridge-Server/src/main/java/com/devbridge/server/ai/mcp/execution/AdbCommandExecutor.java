package com.devbridge.server.ai.mcp.execution;

import static com.devbridge.server.config.ToolExecutorConfiguration.COMMAND_IO_EXECUTOR;
import static com.devbridge.server.config.ToolExecutorConfiguration.TOOL_EXECUTION_EXECUTOR;

import com.devbridge.server.ai.mcp.model.AdbCommandPlan;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import com.devbridge.server.ai.mcp.model.AdbOutputLimit;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import com.devbridge.server.ai.mcp.model.AdbRunningToolCall;
import com.devbridge.server.ai.mcp.model.AdbSanitizedOutput;
import com.devbridge.server.ai.mcp.security.AdbOutputSanitizer;
import com.devbridge.server.command.CommandOutputLimits;
import com.devbridge.server.command.CommandResult;
import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.command.ProcessTreeTerminator;
import com.devbridge.server.model.BusinessException;
import com.devbridge.server.service.ExecutableLocator;
import com.devbridge.server.service.ToolCatalog;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ADB MCP 命令执行器，负责定位 adb、执行参数数组并转换统一工具结果。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AdbCommandExecutor {

    private final ExecutableLocator executableLocator;
    private final CommandRunner commandRunner;
    private final AdbOutputSanitizer outputSanitizer;
    private final AdbRunningToolRegistry runningToolRegistry;
    private final ProcessTreeTerminator processTreeTerminator = new ProcessTreeTerminator();
    private final Executor toolExecutionExecutor;
    private final Executor commandIoExecutor;

    /**
     * 注入命令执行依赖。
     *
     * @param executableLocator 工具定位器
     * @param commandRunner 短命令执行器
     * @param outputSanitizer 输出安全处理器
     * @param runningToolRegistry 运行中工具注册表
     * @param toolExecutionExecutor 工具调度执行器
     * @param commandIoExecutor 命令 IO 执行器
     */
    public AdbCommandExecutor(
            ExecutableLocator executableLocator,
            CommandRunner commandRunner,
            AdbOutputSanitizer outputSanitizer,
            AdbRunningToolRegistry runningToolRegistry,
            @Qualifier(TOOL_EXECUTION_EXECUTOR) Executor toolExecutionExecutor,
            @Qualifier(COMMAND_IO_EXECUTOR) Executor commandIoExecutor) {
        this.executableLocator = executableLocator;
        this.commandRunner = commandRunner;
        this.outputSanitizer = outputSanitizer;
        this.runningToolRegistry = runningToolRegistry;
        this.toolExecutionExecutor = toolExecutionExecutor;
        this.commandIoExecutor = commandIoExecutor;
    }

    /**
     * 执行 ADB 命令计划并返回统一结果。
     *
     * @param plan 命令计划
     * @param riskLevel 风险级别
     * @return 工具结果
     */
    public AdbMcpToolResult execute(AdbCommandPlan plan, AdbRiskLevel riskLevel) {
        long started = System.currentTimeMillis();
        List<String> command = command(plan);
        String commandSummary = commandSummary(command);
        CommandResult result = commandRunner.run(
                command,
                plan.timeout(),
                plan.environment(),
                outputLimits(plan.outputLimit()),
                process -> runningToolRegistry.register(new AdbRunningToolCall(
                        plan.requestId(), String.valueOf(process.pid()),
                        () -> processTreeTerminator.terminate(process))),
                () -> runningToolRegistry.remove(plan.requestId()));
        AdbSanitizedOutput output = outputSanitizer.sanitize(result, plan.outputLimit());
        long elapsed = System.currentTimeMillis() - started;
        if (result.timedOut()) {
            return new AdbMcpToolResult(
                    com.devbridge.server.ai.mcp.model.AdbToolStatus.FAILED,
                    output.stdout(),
                    output.stderr(),
                    124,
                    true,
                    elapsed,
                    output.truncated(),
                    riskLevel,
                    false,
                    "",
                    "ADB 命令执行超时",
                    "ADB_COMMAND_TIMEOUT")
                    .withToolMetadata("ADB MCP", commandSummary);
        }
        if (!result.successful()) {
            if (result.exitCode() == CommandRunner.EXECUTOR_REJECTED_EXIT_CODE) {
                return new AdbMcpToolResult(
                        com.devbridge.server.ai.mcp.model.AdbToolStatus.FAILED,
                        output.stdout(), output.stderr(), result.exitCode(), false, elapsed,
                        output.truncated(), riskLevel, false, "", "ADB 执行队列已满", "TOOL_EXECUTOR_SATURATED")
                        .withToolMetadata("ADB MCP", commandSummary);
            }
            return new AdbMcpToolResult(
                    com.devbridge.server.ai.mcp.model.AdbToolStatus.FAILED,
                    output.stdout(),
                    output.stderr(),
                    result.exitCode(),
                    false,
                    elapsed,
                    output.truncated(),
                    riskLevel,
                    false,
                    "",
                    "ADB 命令执行失败",
                    "ADB_COMMAND_FAILED")
                    .withToolMetadata("ADB MCP", commandSummary);
        }
        return AdbMcpToolResult.success(output.stdout(), output.stderr(), result.exitCode(), elapsed, output.truncated(), riskLevel)
                .withToolMetadata("ADB MCP", commandSummary);
    }

    /**
     * 将 ADB 工具输出策略转换为通用进程读取上限。
     *
     * @param limit ADB 输出限制
     * @return 通用命令输出上限
     */
    private CommandOutputLimits outputLimits(AdbOutputLimit limit) {
        return CommandOutputLimits.of(
                limit.maxStdoutLines(),
                limit.maxStderrLines(),
                limit.maxStdoutCharacters(),
                limit.maxStderrCharacters());
    }

    /**
     * 流式执行 ADB 命令计划，并登记取消句柄。
     *
     * @param plan 命令计划
     * @param riskLevel 风险级别
     * @return SSE Emitter
     */
    public SseEmitter executeStreaming(AdbCommandPlan plan, AdbRiskLevel riskLevel) {
        SseEmitter emitter = new SseEmitter(plan.timeout().toMillis() + 5_000L);
        try {
            toolExecutionExecutor.execute(() -> runStreaming(plan, riskLevel, emitter));
        } catch (RejectedExecutionException ex) {
            send(emitter, "tool-error", AdbMcpToolResult.failed(
                    "TOOL_EXECUTOR_SATURATED", "ADB 执行队列已满", "请稍后重试", null, riskLevel)
                    .withToolMetadata("ADB MCP", plan.argumentSummary()));
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 拼接 adb 可执行文件和参数数组。
     *
     * @param plan 命令计划
     * @return 完整命令
     */
    public List<String> command(AdbCommandPlan plan) {
        String executable = executableLocator.locate(ToolCatalog.ADB);
        if (executable.isBlank()) {
            throw new BusinessException("ADB_TOOL_NOT_FOUND", "ADB 工具不存在", HttpStatus.CONFLICT, "adb not found");
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(plan.adbArguments());
        return command;
    }

    /**
     * 取消运行中的 ADB MCP 工具调用。
     *
     * @param requestId 请求 ID
     * @return 取消结果
     */
    public AdbMcpToolResult cancel(String requestId) {
        return runningToolRegistry.cancel(requestId);
    }

    /**
     * 在后台线程中启动并等待 ADB 流式命令。
     *
     * @param plan 命令计划
     * @param riskLevel 风险级别
     * @param emitter SSE Emitter
     */
    private void runStreaming(AdbCommandPlan plan, AdbRiskLevel riskLevel, SseEmitter emitter) {
        long started = System.currentTimeMillis();
        try {
            List<String> command = command(plan);
            Process process = process(plan, command);
            runningToolRegistry.register(new AdbRunningToolCall(
                    plan.requestId(),
                    plan.requestId(),
                    () -> processTreeTerminator.terminate(process)));
            CompletableFuture<Void> stdout = readStream(process.getInputStream(), emitter, "tool-output");
            CompletableFuture<Void> stderr = readStream(process.getErrorStream(), emitter, "tool-output");
            boolean finished = process.waitFor(plan.timeout().toMillis(), TimeUnit.MILLISECONDS);
            completeStreaming(plan, riskLevel, emitter, process, finished, started, commandSummary(command));
            CompletableFuture.allOf(stdout, stderr).get(500, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            send(emitter, "tool-error", AdbMcpToolResult.failed("ADB_COMMAND_FAILED", "ADB 流式命令执行失败", ex.getMessage(), null, riskLevel)
                    .withToolMetadata("ADB MCP", plan.argumentSummary()));
        } finally {
            runningToolRegistry.remove(plan.requestId());
            emitter.complete();
        }
    }

    /**
     * 启动进程并注入受控环境变量。
     *
     * @param plan 命令计划
     * @return 进程
     * @throws IOException 启动失败
     */
    private Process process(AdbCommandPlan plan, List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (!plan.environment().isEmpty()) {
            builder.environment().putAll(plan.environment());
        }
        return builder.start();
    }

    /**
     * 读取进程输出并发送 SSE 事件。
     *
     * @param inputStream 输出流
     * @param emitter SSE Emitter
     * @param eventName 事件名
     * @return 读取任务
     */
    private CompletableFuture<Void> readStream(InputStream inputStream, SseEmitter emitter, String eventName) {
        return CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    send(emitter, eventName, outputSanitizer.sanitizeLine(line));
                }
            } catch (IOException ignored) {
                // 取消进程时流会被关闭，这里不覆盖最终工具状态。
            }
        }, commandIoExecutor);
    }

    /**
     * 完成流式命令并发送最终结果。
     *
     * @param plan 命令计划
     * @param riskLevel 风险级别
     * @param emitter SSE Emitter
     * @param process 进程
     * @param finished 是否正常结束
     * @param started 开始时间
     */
    private void completeStreaming(
            AdbCommandPlan plan,
            AdbRiskLevel riskLevel,
            SseEmitter emitter,
            Process process,
            boolean finished,
            long started,
            String commandSummary) {
        if (!finished) {
            processTreeTerminator.terminate(process);
            send(emitter, "tool-error", new AdbMcpToolResult(
                    com.devbridge.server.ai.mcp.model.AdbToolStatus.FAILED,
                    "",
                    "",
                    124,
                    true,
                    System.currentTimeMillis() - started,
                    true,
                    riskLevel,
                    false,
                    "",
                    "ADB 流式命令执行超时",
                    "ADB_COMMAND_TIMEOUT")
                    .withToolMetadata("ADB MCP", commandSummary));
            return;
        }
        send(emitter, "tool-result", AdbMcpToolResult.success("", "", process.exitValue(), System.currentTimeMillis() - started, false, riskLevel)
                .withToolMetadata("ADB MCP", commandSummary));
    }

    /**
     * 拼接最终执行命令；工具卡片展示的必须是实际进程参数，而不是抽象工具名。
     *
     * @param command 最终命令数组
     * @return 命令摘要
     */
    private String commandSummary(List<String> command) {
        return String.join(" ", command == null ? List.of() : command);
    }

    /**
     * 发送 SSE 事件；客户端断开时完成 emitter。
     *
     * @param emitter SSE Emitter
     * @param eventName 事件名
     * @param data 事件数据
     */
    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException ex) {
            emitter.complete();
        }
    }
}
