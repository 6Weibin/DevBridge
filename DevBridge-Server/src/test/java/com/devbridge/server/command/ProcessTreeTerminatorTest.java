package com.devbridge.server.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 进程树终止器测试，使用 Java 父子进程保证 Windows、macOS 和 Linux 行为一致。
 *
 * <p>by AI.Coding</p>
 */
class ProcessTreeTerminatorTest {

    /**
     * 验证终止父进程时同步清理其后代进程，避免 Shell 或构建任务残留。
     */
    @Test
    void terminateShouldStopParentAndDescendantProcesses() throws Exception {
        Process parent = startJava(ParentProcess.class.getName());
        long childPid;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(parent.getInputStream(), StandardCharsets.UTF_8))) {
            childPid = Long.parseLong(reader.readLine());
        }
        ProcessHandle child = ProcessHandle.of(childPid).orElseThrow();

        ProcessTerminationResult result = new ProcessTreeTerminator(Duration.ofMillis(300)).terminate(parent);

        assertThat(parent.waitFor(2, TimeUnit.SECONDS)).isTrue();
        awaitStopped(child);
        assertThat(parent.isAlive()).isFalse();
        assertThat(child.isAlive()).isFalse();
        assertThat(result.rootPid()).isEqualTo(parent.pid());
        assertThat(result.terminated()).isTrue();
    }

    /**
     * 启动当前 JDK 中的测试进程，避免依赖系统 Shell 命令。
     *
     * @param mainClass 入口类名
     * @param args 入口参数
     * @return Java 进程
     */
    private static Process startJava(String mainClass, String... args) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                mainClass));
        command.addAll(List.of(args));
        return new ProcessBuilder(command).start();
    }

    /**
     * 获取当前 JDK 的 Java 可执行文件路径。
     *
     * @return Java 可执行文件
     */
    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    /**
     * 等待进程句柄进入终止状态。
     *
     * @param processHandle 进程句柄
     */
    private void awaitStopped(ProcessHandle processHandle) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (processHandle.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * 测试父进程，启动一个子 Java 进程并等待其结束。
     *
     * <p>by AI.Coding</p>
     */
    public static final class ParentProcess {

        /**
         * 启动子进程并输出 PID，供测试进程获取真实父子关系。
         *
         * @param args 未使用参数
         */
        public static void main(String[] args) throws Exception {
            Process child = startJava(ChildProcess.class.getName(), String.valueOf(ProcessHandle.current().pid()));
            System.out.println(child.pid());
            System.out.flush();
            child.waitFor();
        }
    }

    /**
     * 测试子进程，保持运行直到被进程树终止器清理。
     *
     * <p>by AI.Coding</p>
     */
    public static final class ChildProcess {

        /**
         * 保持子进程存活。
         *
         * @param args 未使用参数
         */
        public static void main(String[] args) throws Exception {
            long parentPid = Long.parseLong(args[0]);
            while (ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(20);
            }
        }
    }
}
