package com.devbridge.server.ai.localshell.execution;

import static com.devbridge.server.config.ToolExecutorConfiguration.COMMAND_IO_EXECUTOR;
import static com.devbridge.server.config.ToolExecutorConfiguration.TOOL_EXECUTION_EXECUTOR;

import com.devbridge.server.ai.localshell.model.LocalShellCommandMode;
import com.devbridge.server.ai.localshell.model.LocalShellCommandPlan;
import com.devbridge.server.ai.localshell.model.LocalShellRunningToolCall;
import com.devbridge.server.ai.localshell.security.LocalShellOutputSanitizer;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import com.devbridge.server.ai.mcp.model.AdbSanitizedOutput;
import com.devbridge.server.ai.mcp.model.AdbToolStatus;
import com.devbridge.server.command.BoundedProcessOutput;
import com.devbridge.server.command.BoundedProcessOutputReader;
import com.devbridge.server.command.CommandOutputLimits;
import com.devbridge.server.command.CommandResult;
import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.command.ProcessOutputLimit;
import com.devbridge.server.command.ProcessTreeTerminator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Local Shell 命令执行器，负责启动本机进程并转换统一工具结果。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class LocalShellCommandExecutor {

    private final LocalShellOutputSanitizer outputSanitizer;
    private final LocalShellRunningToolRegistry runningToolRegistry;
    private final BoundedProcessOutputReader outputReader = new BoundedProcessOutputReader();
    private final ProcessTreeTerminator processTreeTerminator = new ProcessTreeTerminator();
    private final Executor toolExecutionExecutor;
    private final Executor commandIoExecutor;

    /**
     * 注入执行依赖。
     *
     * @param outputSanitizer 输出安全处理器
     * @param runningToolRegistry 运行中工具注册表
     * @param toolExecutionExecutor 工具调度执行器
     * @param commandIoExecutor 命令 IO 执行器
     */
    public LocalShellCommandExecutor(
            LocalShellOutputSanitizer outputSanitizer,
            LocalShellRunningToolRegistry runningToolRegistry,
            @Qualifier(TOOL_EXECUTION_EXECUTOR) Executor toolExecutionExecutor,
            @Qualifier(COMMAND_IO_EXECUTOR) Executor commandIoExecutor) {
        this.outputSanitizer = outputSanitizer;
        this.runningToolRegistry = runningToolRegistry;
        this.toolExecutionExecutor = toolExecutionExecutor;
        this.commandIoExecutor = commandIoExecutor;
    }

    /**
     * 执行本机命令并返回统一工具结果。
     *
     * @param plan 命令计划
     * @param riskLevel 风险级别
     * @return 工具结果
     */
    public AdbMcpToolResult execute(LocalShellCommandPlan plan, AdbRiskLevel riskLevel) {
        long started = System.currentTimeMillis();
        List<String> command = command(plan);
        String commandSummary = commandSummary(command);
        CommandResult result = run(plan, command);
        AdbSanitizedOutput output = outputSanitizer.sanitize(result, plan.outputLimit());
        long elapsed = System.currentTimeMillis() - started;
        if (result.timedOut()) {
            return new AdbMcpToolResult(
                    AdbToolStatus.FAILED, output.stdout(), output.stderr(), 124, true, elapsed,
                    output.truncated(), riskLevel, false, "", "本机命令执行超时", "LOCAL_SHELL_TIMEOUT")
                    .withToolMetadata("Local Shell MCP", commandSummary);
        }
        if (!result.successful()) {
            if (result.exitCode() == CommandRunner.EXECUTOR_REJECTED_EXIT_CODE) {
                return new AdbMcpToolResult(
                        AdbToolStatus.FAILED, output.stdout(), output.stderr(), result.exitCode(), false, elapsed,
                        output.truncated(), riskLevel, false, "", "本机命令执行队列已满", "TOOL_EXECUTOR_SATURATED")
                        .withToolMetadata("Local Shell MCP", commandSummary);
            }
            return new AdbMcpToolResult(
                    AdbToolStatus.FAILED, output.stdout(), output.stderr(), result.exitCode(), false, elapsed,
                    output.truncated(), riskLevel, false, "", "本机命令执行失败", "LOCAL_SHELL_EXEC_FAILED")
                    .withToolMetadata("Local Shell MCP", commandSummary);
        }
        return AdbMcpToolResult.success(output.stdout(), output.stderr(), result.exitCode(), elapsed, output.truncated(), riskLevel)
                .withToolMetadata("Local Shell MCP", commandSummary);
    }

    /**
     * 流式执行本机命令，并登记取消句柄。
     *
     * @param plan 命令计划
     * @param riskLevel 风险级别
     * @return SSE Emitter
     */
    public SseEmitter executeStreaming(LocalShellCommandPlan plan, AdbRiskLevel riskLevel) {
        SseEmitter emitter = new SseEmitter(plan.timeout().toMillis() + 5_000L);
        try {
            toolExecutionExecutor.execute(() -> runStreaming(plan, riskLevel, emitter));
        } catch (RejectedExecutionException ex) {
            send(emitter, "tool-error", AdbMcpToolResult.failed(
                    "TOOL_EXECUTOR_SATURATED", "本机命令执行队列已满", "请稍后重试", null, riskLevel)
                    .withToolMetadata("Local Shell MCP", plan.commandSummary()));
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 取消运行中的本机命令工具调用。
     *
     * @param requestId 请求 ID
     * @return 取消结果
     */
    public AdbMcpToolResult cancel(String requestId) {
        return runningToolRegistry.cancel(requestId);
    }

    /**
     * 查询 Local Shell MCP 管理的运行中命令状态。
     *
     * @return 工具结果
     */
    public AdbMcpToolResult processStatus() {
        List<String> lines = runningToolRegistry.snapshot().stream()
                .map(call -> "requestId=" + call.requestId() + ", processId=" + call.processId())
                .toList();
        String stdout = lines.isEmpty() ? "当前没有运行中的本机命令。" : String.join(System.lineSeparator(), lines);
        return AdbMcpToolResult.success(stdout, "", 0, 0, false, AdbRiskLevel.LOW)
                .withToolMetadata("Local Shell MCP", "local_shell_process_status");
    }

    /**
     * 执行短命令并读取完整输出。
     *
     * @param plan 命令计划
     * @return 命令结果
     */
    private CommandResult run(LocalShellCommandPlan plan, List<String> command) {
        Process process = null;
        try {
            process = process(plan, command);
            Process runningProcess = process;
            runningToolRegistry.register(new LocalShellRunningToolCall(
                    plan.requestId(), String.valueOf(process.pid()),
                    () -> processTreeTerminator.terminate(runningProcess)));
            CommandOutputLimits limits = outputLimits(plan);
            CompletableFuture<BoundedProcessOutput> stdout = readOutputAsync(
                    process.getInputStream(), limits.stdout());
            CompletableFuture<BoundedProcessOutput> stderr = readOutputAsync(
                    process.getErrorStream(), limits.stderr());
            boolean finished = process.waitFor(plan.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                processTreeTerminator.terminate(process);
                process.waitFor(500, TimeUnit.MILLISECONDS);
                return CommandResult.fromBoundedOutputs(124, futureOutput(stdout), futureOutput(stderr), true);
            }
            return CommandResult.fromBoundedOutputs(
                    process.exitValue(), futureOutput(stdout), futureOutput(stderr), false);
        } catch (IOException ex) {
            return new CommandResult(127, List.of(), List.of(ex.getMessage()), false);
        } catch (RejectedExecutionException ex) {
            destroy(process);
            return new CommandResult(
                    CommandRunner.EXECUTOR_REJECTED_EXIT_CODE,
                    List.of(),
                    List.of("command executor saturated"),
                    false);
        } catch (InterruptedException ex) {
            destroy(process);
            Thread.currentThread().interrupt();
            return new CommandResult(130, List.of(), List.of("command interrupted"), false);
        } finally {
            runningToolRegistry.remove(plan.requestId());
        }
    }

    /**
     * 在后台线程中启动并等待流式命令。
     *
     * @param plan 命令计划
     * @param riskLevel 风险级别
     * @param emitter SSE Emitter
     */
    private void runStreaming(LocalShellCommandPlan plan, AdbRiskLevel riskLevel, SseEmitter emitter) {
        long started = System.currentTimeMillis();
        try {
            List<String> command = command(plan);
            String commandSummary = commandSummary(command);
            Process process = process(plan, command);
            runningToolRegistry.register(new LocalShellRunningToolCall(
                    plan.requestId(),
                    String.valueOf(process.pid()),
                    () -> processTreeTerminator.terminate(process)));
            CompletableFuture<Void> stdout = readStream(process.getInputStream(), emitter);
            CompletableFuture<Void> stderr = readStream(process.getErrorStream(), emitter);
            boolean finished = process.waitFor(plan.timeout().toMillis(), TimeUnit.MILLISECONDS);
            completeStreaming(plan, riskLevel, emitter, process, finished, started, commandSummary);
            CompletableFuture.allOf(stdout, stderr).get(500, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            send(emitter, "tool-error", AdbMcpToolResult.failed("LOCAL_SHELL_EXEC_FAILED", "本机命令流式执行失败", ex.getMessage(), null, riskLevel)
                    .withToolMetadata("Local Shell MCP", plan.commandSummary()));
        } finally {
            runningToolRegistry.remove(plan.requestId());
            emitter.complete();
        }
    }

    /**
     * 启动进程并注入受控环境变量和工作目录。
     *
     * @param plan 命令计划
     * @return 进程
     * @throws IOException 启动失败
     */
    private Process process(LocalShellCommandPlan plan, List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(plan.workingDirectory().toFile());
        if (!plan.environment().isEmpty()) {
            builder.environment().putAll(plan.environment());
        }
        return builder.start();
    }

    /**
     * 将 Local Shell 工具输出策略转换为通用进程读取上限。
     *
     * @param plan 命令计划
     * @return 通用命令输出上限
     */
    private CommandOutputLimits outputLimits(LocalShellCommandPlan plan) {
        return CommandOutputLimits.of(
                plan.outputLimit().maxStdoutLines(),
                plan.outputLimit().maxStderrLines(),
                plan.outputLimit().maxStdoutCharacters(),
                plan.outputLimit().maxStderrCharacters());
    }

    /**
     * 组装最终本机命令。
     *
     * @param plan 命令计划
     * @return 命令参数数组
     */
    private List<String> command(LocalShellCommandPlan plan) {
        if (plan.mode() == LocalShellCommandMode.ARGV) {
            return plan.argv();
        }
        if (isWindows()) {
            return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", plan.commandLine());
        }
        return List.of(shellExecutable(), "-lc", plan.commandLine());
    }

    /**
     * 读取进程输出并发送 SSE 事件。
     *
     * @param inputStream 输出流
     * @param emitter SSE Emitter
     * @return 读取任务
     */
    private CompletableFuture<Void> readStream(InputStream inputStream, SseEmitter emitter) {
        return CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    send(emitter, "tool-output", outputSanitizer.sanitizeLine(line));
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
            LocalShellCommandPlan plan,
            AdbRiskLevel riskLevel,
            SseEmitter emitter,
            Process process,
            boolean finished,
            long started,
            String commandSummary) {
        if (!finished) {
            processTreeTerminator.terminate(process);
            send(emitter, "tool-error", new AdbMcpToolResult(
                    AdbToolStatus.FAILED, "", "", 124, true, System.currentTimeMillis() - started,
                    true, riskLevel, false, "", "本机命令流式执行超时", "LOCAL_SHELL_TIMEOUT")
                    .withToolMetadata("Local Shell MCP", commandSummary));
            return;
        }
        send(emitter, "tool-result", AdbMcpToolResult.success("", "", process.exitValue(), System.currentTimeMillis() - started, false, riskLevel)
                .withToolMetadata("Local Shell MCP", commandSummary));
    }

    /**
     * 拼接最终执行命令；展示真实 ProcessBuilder 参数，便于用户核对本机操作。
     *
     * @param command 最终命令数组
     * @return 命令摘要
     */
    private String commandSummary(List<String> command) {
        return String.join(" ", command == null ? List.of() : command);
    }

    /**
     * 异步有界读取进程输出。
     *
     * @param inputStream 输出流
     * @param limit 输出上限
     * @return 输出读取任务
     */
    private CompletableFuture<BoundedProcessOutput> readOutputAsync(
            InputStream inputStream,
            ProcessOutputLimit limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return outputReader.read(inputStream, limit);
            } catch (IOException ex) {
                return BoundedProcessOutput.error(ex.getMessage());
            }
        }, commandIoExecutor);
    }

    /**
     * 执行队列拒绝或等待线程中断时终止已启动进程。
     *
     * @param process 已启动进程
     */
    private void destroy(Process process) {
        processTreeTerminator.terminate(process);
    }

    /**
     * 获取已读取的有界进程输出。
     *
     * @param future 输出读取任务
     * @return 已读取输出
     */
    private BoundedProcessOutput futureOutput(CompletableFuture<BoundedProcessOutput> future) {
        try {
            return future.get(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return BoundedProcessOutput.error("command output interrupted");
        } catch (Exception ex) {
            return BoundedProcessOutput.empty();
        }
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

    /**
     * 获取 Unix shell 可执行文件。
     *
     * @return shell 路径
     */
    private String shellExecutable() {
        if (java.nio.file.Files.isExecutable(java.nio.file.Path.of("/bin/zsh"))) {
            return "/bin/zsh";
        }
        if (java.nio.file.Files.isExecutable(java.nio.file.Path.of("/bin/bash"))) {
            return "/bin/bash";
        }
        return "/bin/sh";
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
