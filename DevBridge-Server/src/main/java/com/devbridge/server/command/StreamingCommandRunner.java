package com.devbridge.server.command;

import static com.devbridge.server.config.ToolExecutorConfiguration.COMMAND_IO_EXECUTOR;
import static com.devbridge.server.config.ToolExecutorConfiguration.COMMAND_TIMEOUT_EXECUTOR;

import com.devbridge.server.model.BusinessException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 长进程命令执行器，支持逐行读取输出并显式停止进程。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class StreamingCommandRunner {

    private final Executor readerExecutor;
    private final ScheduledExecutorService timeoutExecutor;
    private final ProcessTreeTerminator processTreeTerminator = new ProcessTreeTerminator();

    /**
     * 注入受管命令 IO 和超时调度器。
     *
     * @param readerExecutor 命令 IO 执行器
     * @param timeoutExecutor 超时调度器
     */
    public StreamingCommandRunner(
            @Qualifier(COMMAND_IO_EXECUTOR) Executor readerExecutor,
            @Qualifier(COMMAND_TIMEOUT_EXECUTOR) ScheduledExecutorService timeoutExecutor) {
        this.readerExecutor = readerExecutor;
        this.timeoutExecutor = timeoutExecutor;
    }

    /**
     * 启动长进程并异步读取 stdout/stderr。
     *
     * @param command 命令参数数组
     * @param timeout 超时时间
     * @param stdout 标准输出回调
     * @param stderr 错误输出回调
     * @return 长进程句柄
     */
    public StreamingProcess start(
            List<String> command,
            Duration timeout,
            Consumer<String> stdout,
            Consumer<String> stderr) {
        if (command == null || command.isEmpty()) {
            throw new BusinessException("COMMAND_EMPTY", "命令不能为空", HttpStatus.BAD_REQUEST, "");
        }
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            String id = UUID.randomUUID().toString();
            DefaultStreamingProcess streamingProcess = new DefaultStreamingProcess(id, process, processTreeTerminator);
            Process startedProcess = process;
            readerExecutor.execute(() -> readLines(startedProcess.getInputStream(), stdout));
            readerExecutor.execute(() -> readLines(startedProcess.getErrorStream(), stderr));
            timeoutExecutor.schedule(streamingProcess::stop, timeout.toMillis(), TimeUnit.MILLISECONDS);
            return streamingProcess;
        } catch (IOException ex) {
            throw new BusinessException("COMMAND_START_FAILED", "启动命令失败", HttpStatus.CONFLICT, ex.getMessage());
        } catch (RejectedExecutionException ex) {
            destroy(process);
            throw new BusinessException(
                    "COMMAND_EXECUTOR_SATURATED",
                    "命令执行队列已满",
                    HttpStatus.TOO_MANY_REQUESTS,
                    "command executor saturated");
        }
    }

    /**
     * 提交读取或超时任务失败时终止已经启动的进程。
     *
     * @param process 已启动进程
     */
    private void destroy(Process process) {
        processTreeTerminator.terminate(process);
    }

    /**
     * 逐行读取进程输出并发送给调用方。
     *
     * @param inputStream 输出流
     * @param consumer 行回调
     */
    private void readLines(InputStream inputStream, Consumer<String> consumer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                consumer.accept(line);
            }
        } catch (IOException ignored) {
            // 进程被主动停止时流会关闭，此处不覆盖上层会话状态。
        }
    }

    /**
     * 默认长进程句柄实现。
     *
     * <p>by AI.Coding</p>
     */
    private static class DefaultStreamingProcess implements StreamingProcess {

        private final String id;
        private final Process process;
        private final ProcessTreeTerminator processTreeTerminator;

        /**
         * 创建长进程句柄。
         *
         * @param id 会话 ID
         * @param process 进程
         * @param processTreeTerminator 进程树终止器
         */
        DefaultStreamingProcess(
                String id,
                Process process,
                ProcessTreeTerminator processTreeTerminator) {
            this.id = id;
            this.process = process;
            this.processTreeTerminator = processTreeTerminator;
        }

        /**
         * 获取进程会话 ID。
         *
         * @return 会话 ID
         */
        @Override
        public String id() {
            return id;
        }

        /**
         * 判断进程是否仍在运行。
         *
         * @return 运行中返回 true
         */
        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        /**
         * 停止进程；先尝试普通销毁，再强制终止。
         */
        @Override
        public void stop() {
            processTreeTerminator.terminate(process);
        }
    }
}
