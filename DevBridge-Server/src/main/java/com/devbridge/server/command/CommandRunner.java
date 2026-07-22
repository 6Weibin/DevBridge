package com.devbridge.server.command;

import static com.devbridge.server.config.ToolExecutorConfiguration.COMMAND_IO_EXECUTOR;

import com.devbridge.server.config.DevBridgeProperties;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 受控外部命令执行器，统一处理超时和输出读取。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class CommandRunner {

    public static final int EXECUTOR_REJECTED_EXIT_CODE = 125;

    private final DevBridgeProperties properties;
    private final BoundedProcessOutputReader outputReader = new BoundedProcessOutputReader();
    private final ProcessTreeTerminator processTreeTerminator = new ProcessTreeTerminator();
    private final Executor commandIoExecutor;

    /**
     * 注入命令执行配置。
     *
     * @param properties DevBridge 配置
     * @param commandIoExecutor 命令 IO 执行器
     */
    public CommandRunner(
            DevBridgeProperties properties,
            @Qualifier(COMMAND_IO_EXECUTOR) Executor commandIoExecutor) {
        this.properties = properties;
        this.commandIoExecutor = commandIoExecutor;
    }

    /**
     * 执行命令参数数组；这里刻意不接受 shell 字符串，避免用户输入触发命令注入。
     *
     * @param command 命令及参数
     * @return 命令结果
     */
    public CommandResult run(List<String> command) {
        return run(command, properties.getCommandTimeout());
    }

    /**
     * 按指定超时执行命令。
     *
     * @param command 命令及参数
     * @param timeout 超时时间
     * @return 命令结果
     */
    public CommandResult run(List<String> command, Duration timeout) {
        return run(command, timeout, Map.of());
    }

    /**
     * 按指定超时和受控环境变量执行命令。
     *
     * @param command 命令及参数
     * @param timeout 超时时间
     * @param environment 受控环境变量
     * @return 命令结果
     */
    public CommandResult run(List<String> command, Duration timeout, Map<String, String> environment) {
        return run(command, timeout, environment, CommandOutputLimits.defaults());
    }

    /**
     * 按指定超时、环境变量和输出上限执行命令。
     *
     * @param command 命令及参数
     * @param timeout 超时时间
     * @param environment 受控环境变量
     * @param outputLimits stdout/stderr 读取上限
     * @return 命令结果
     */
    public CommandResult run(
            List<String> command,
            Duration timeout,
            Map<String, String> environment,
            CommandOutputLimits outputLimits) {
        return run(command, timeout, environment, outputLimits, process -> { }, () -> { });
    }

    /**
     * 执行命令并在进程生命周期边界通知调用方，用于把真实子进程绑定到 Agent 取消作用域。
     *
     * @param command 命令及参数
     * @param timeout 超时时间
     * @param environment 受控环境变量
     * @param outputLimits stdout/stderr 读取上限
     * @param processStarted 进程启动后的登记回调
     * @param processFinished 进程结束后的清理回调
     * @return 命令结果
     */
    public CommandResult run(
            List<String> command,
            Duration timeout,
            Map<String, String> environment,
            CommandOutputLimits outputLimits,
            Consumer<Process> processStarted,
            Runnable processFinished) {
        if (command == null || command.isEmpty()) {
            return new CommandResult(127, List.of(), List.of("command is empty"), false);
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (environment != null && !environment.isEmpty()) {
                builder.environment().putAll(environment);
            }
            process = builder.start();
            // 进程一旦启动就先登记取消句柄，避免任务取消与进程注册之间出现逃逸窗口。
            processStarted.accept(process);
            CompletableFuture<BoundedProcessOutput> stdout = readOutputAsync(
                    process.getInputStream(), outputLimits.stdout());
            CompletableFuture<BoundedProcessOutput> stderr = readOutputAsync(
                    process.getErrorStream(), outputLimits.stderr());
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                processTreeTerminator.terminate(process);
                // 超时后不能同步阻塞读取进程流；adb 子进程退出慢时会导致 HTTP 请求一直挂起。
                process.waitFor(500, TimeUnit.MILLISECONDS);
                return CommandResult.fromBoundedOutputs(124, futureOutput(stdout), futureOutput(stderr), true);
            }
            return CommandResult.fromBoundedOutputs(
                    process.exitValue(),
                    futureOutput(stdout),
                    futureOutput(stderr),
                    false);
        } catch (IOException ex) {
            return new CommandResult(127, List.of(), List.of(ex.getMessage()), false);
        } catch (RejectedExecutionException ex) {
            destroy(process);
            return new CommandResult(
                    EXECUTOR_REJECTED_EXIT_CODE,
                    List.of(),
                    List.of("command executor saturated"),
                    false);
        } catch (InterruptedException ex) {
            destroy(process);
            Thread.currentThread().interrupt();
            return new CommandResult(130, List.of(), List.of("command interrupted"), false);
        } finally {
            processFinished.run();
        }
    }

    /**
     * 异步有界读取进程输出，避免管道阻塞和输出在堆内无界增长。
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
     * 线程池拒绝或当前线程中断时终止已经启动的进程，避免孤儿任务继续运行。
     *
     * @param process 已启动进程
     */
    private void destroy(Process process) {
        processTreeTerminator.terminate(process);
    }

    /**
     * 获取已读取的进程输出；超时命令的流可能仍未关闭，必须快速返回。
     *
     * @param future 输出读取任务
     * @return 已读取的有界输出，读取未完成时返回空输出
     */
    private BoundedProcessOutput futureOutput(CompletableFuture<BoundedProcessOutput> future) {
        try {
            return future.get(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return BoundedProcessOutput.error("command output interrupted");
        } catch (TimeoutException ex) {
            return BoundedProcessOutput.empty();
        } catch (Exception ex) {
            return BoundedProcessOutput.error(ex.getMessage());
        }
    }
}
