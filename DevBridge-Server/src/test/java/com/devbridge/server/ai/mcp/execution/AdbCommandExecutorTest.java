package com.devbridge.server.ai.mcp.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.mcp.model.AdbCommandPlan;
import com.devbridge.server.ai.mcp.model.AdbOutputLimit;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import com.devbridge.server.ai.mcp.security.AdbOutputSanitizer;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.command.CommandOutputLimits;
import com.devbridge.server.command.CommandResult;
import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.service.ExecutableLocator;
import com.devbridge.server.service.ToolDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * ADB 命令执行器测试，验证工具输出策略会下推到进程读取层。
 *
 * <p>by AI.Coding</p>
 */
class AdbCommandExecutorTest {

    /**
     * 验证 ADB 计划中的行数和字节数上限原样传递给 CommandRunner。
     */
    @Test
    void executeShouldPassPlanOutputLimitsToCommandRunner() {
        CapturingCommandRunner commandRunner = new CapturingCommandRunner();
        AdbCommandExecutor executor = new AdbCommandExecutor(
                new FixedExecutableLocator(),
                commandRunner,
                new AdbOutputSanitizer(new SensitiveDataMasker()),
                new AdbRunningToolRegistry(),
                Runnable::run,
                Runnable::run);
        AdbOutputLimit expected = new AdbOutputLimit(12, 7, 4_096, 2_048);
        AdbCommandPlan plan = new AdbCommandPlan(
                "request-1", "conversation-1", "adb_shell", List.of("devices"), "", false,
                Map.of(), Duration.ofSeconds(5), expected, false);

        executor.execute(plan, AdbRiskLevel.LOW);

        assertThat(commandRunner.outputLimits.stdout().maxLines()).isEqualTo(expected.maxStdoutLines());
        assertThat(commandRunner.outputLimits.stderr().maxLines()).isEqualTo(expected.maxStderrLines());
        assertThat(commandRunner.outputLimits.stdout().maxBytes()).isEqualTo(expected.maxStdoutCharacters());
        assertThat(commandRunner.outputLimits.stderr().maxBytes()).isEqualTo(expected.maxStderrCharacters());
    }

    /**
     * 固定返回 adb 路径，隔离单元测试对本机工具安装状态的依赖。
     *
     * <p>by AI.Coding</p>
     */
    private static final class FixedExecutableLocator extends ExecutableLocator {

        /**
         * 创建固定工具定位器。
         */
        private FixedExecutableLocator() {
            super(new DevBridgeProperties());
        }

        /**
         * 返回测试专用可执行路径。
         *
         * @param definition 工具定义
         * @return 固定路径
         */
        @Override
        public String locate(ToolDefinition definition) {
            return "/test/adb";
        }
    }

    /**
     * 捕获输出限制的命令执行器测试替身，不启动真实 ADB 进程。
     *
     * <p>by AI.Coding</p>
     */
    private static final class CapturingCommandRunner extends CommandRunner {

        private CommandOutputLimits outputLimits;

        /**
         * 创建命令执行器测试替身。
         */
        private CapturingCommandRunner() {
            super(new DevBridgeProperties(), Runnable::run);
        }

        /**
         * 捕获调用方下推的输出限制并返回成功结果。
         *
         * @param command 命令及参数
         * @param timeout 超时时间
         * @param environment 环境变量
         * @param outputLimits 输出限制
         * @param processStarted 进程启动回调
         * @param processFinished 进程结束回调
         * @return 固定成功结果
         */
        @Override
        public CommandResult run(
                List<String> command,
                Duration timeout,
                Map<String, String> environment,
                CommandOutputLimits outputLimits,
                Consumer<Process> processStarted,
                Runnable processFinished) {
            this.outputLimits = outputLimits;
            return new CommandResult(0, List.of("ok"), List.of(), false);
        }
    }
}
