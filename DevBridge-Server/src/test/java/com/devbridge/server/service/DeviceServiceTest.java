package com.devbridge.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.command.CommandResult;
import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.CommandDiagnostic;
import com.devbridge.server.model.DeviceInfo;
import com.devbridge.server.model.DeviceStatus;
import com.devbridge.server.model.Platform;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 设备枚举服务测试，覆盖 ADB daemon 异常恢复路径。
 *
 * <p>by AI.Coding</p>
 */
class DeviceServiceTest {

    /**
     * 验证 ADB server 协议错误时会先重置 daemon，再重新枚举设备。
     */
    @Test
    void listDevicesShouldRecoverAdbDaemonWhenProtocolFaultHappens() {
        FakeCommandRunner runner = new FakeCommandRunner(
                failed("adb: failed to check server version: protocol fault (couldn't read status): Connection reset by peer"),
                success(),
                success(),
                success("List of devices attached", "ABC123\tdevice product:test model:Pixel_8 device:shiba"));
        DeviceService service = new DeviceService(new FakeExecutableLocator(), runner);

        List<DeviceInfo> devices = service.listDevices();

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).serial()).isEqualTo("ABC123");
        assertThat(devices.get(0).platform()).isEqualTo(Platform.ANDROID);
        assertThat(devices.get(0).status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(runner.calls()).containsExactly(
                List.of("/fake/adb", "devices"),
                List.of("/fake/adb", "kill-server"),
                List.of("/fake/adb", "start-server"),
                List.of("/fake/adb", "devices"));
    }

    /**
     * 验证启动页依赖的 ADB 诊断接口在协议错误时也会恢复 daemon。
     */
    @Test
    void diagnoseAdbDevicesShouldRecoverAdbDaemonWhenProtocolFaultHappens() {
        FakeCommandRunner runner = new FakeCommandRunner(
                failed("adb: failed to check server version: protocol fault (couldn't read status): Connection reset by peer"),
                success(),
                success(),
                success("List of devices attached"));
        DeviceService service = new DeviceService(new FakeExecutableLocator(), runner);

        CommandDiagnostic diagnostic = service.diagnoseAdbDevices();

        assertThat(diagnostic.exitCode()).isZero();
        assertThat(diagnostic.timedOut()).isFalse();
        assertThat(runner.calls()).containsExactly(
                List.of("/fake/adb", "devices", "-l"),
                List.of("/fake/adb", "kill-server"),
                List.of("/fake/adb", "start-server"),
                List.of("/fake/adb", "devices", "-l"));
    }

    /**
     * 构造成功命令结果。
     *
     * @param stdout 标准输出行
     * @return 成功结果
     */
    private static CommandResult success(String... stdout) {
        return new CommandResult(0, List.of(stdout), List.of(), false);
    }

    /**
     * 构造失败命令结果。
     *
     * @param stderr 标准错误内容
     * @return 失败结果
     */
    private static CommandResult failed(String stderr) {
        return new CommandResult(1, List.of(), List.of(stderr), false);
    }

    /**
     * 固定返回 ADB 路径的工具定位器，避免测试依赖本机文件系统。
     */
    private static class FakeExecutableLocator extends ExecutableLocator {

        FakeExecutableLocator() {
            super(new DevBridgeProperties());
        }

        /**
         * 只让 ADB 存在，其它平台工具返回缺失，保证测试聚焦 Android 枚举路径。
         *
         * @param definition 工具定义
         * @return adb 路径或空字符串
         */
        @Override
        public String locate(ToolDefinition definition) {
            return ToolCatalog.ADB.equals(definition) ? "/fake/adb" : "";
        }
    }

    /**
     * 可预设返回值的命令执行器，用于验证命令顺序和超时分支。
     */
    private static class FakeCommandRunner extends CommandRunner {

        private final List<CommandResult> results;
        private final List<List<String>> calls = new ArrayList<>();

        FakeCommandRunner(CommandResult... results) {
            super(new DevBridgeProperties(), Runnable::run);
            this.results = new ArrayList<>(List.of(results));
        }

        /**
         * 记录命令并按顺序返回预设结果。
         *
         * @param command 命令及参数
         * @param timeout 超时时间
         * @return 预设命令结果
         */
        @Override
        public CommandResult run(List<String> command, Duration timeout) {
            calls.add(List.copyOf(command));
            return results.isEmpty() ? success() : results.remove(0);
        }

        /**
         * 获取执行过的命令列表。
         *
         * @return 命令列表
         */
        List<List<String>> calls() {
            return calls;
        }
    }
}
