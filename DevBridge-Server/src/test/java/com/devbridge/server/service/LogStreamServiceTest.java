package com.devbridge.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.command.StreamingCommandRunner;
import com.devbridge.server.command.StreamingProcess;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.DeviceDetail;
import com.devbridge.server.model.DeviceStatus;
import com.devbridge.server.model.LogStreamQuery;
import com.devbridge.server.model.Platform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 实时日志服务测试，覆盖不同平台日志格式解析。
 *
 * <p>by AI.Coding</p>
 */
class LogStreamServiceTest {

    /**
     * 验证工具错误事件不占用 SSE 原生 error 语义，避免前端把业务提示误判为连接断开。
     */
    @Test
    void toolErrorEventNameShouldAvoidNativeSseError() {
        assertThat(LogStreamService.LOG_EVENT_NAME).isEqualTo("log");
        assertThat(LogStreamService.TOOL_ERROR_EVENT_NAME).isEqualTo("tool-error");
    }

    /**
     * 验证 Android 实时日志只回放最近 1000 行历史日志，避免启动瞬间把大量旧日志灌入 SSE。
     */
    @Test
    void streamAndroidLogsShouldLimitInitialReplayToOneThousandLines(@TempDir Path tempDir) {
        CapturingStreamingCommandRunner runner = new CapturingStreamingCommandRunner();
        DevBridgeProperties properties = testProperties(tempDir);
        LogStreamService service = new LogStreamService(
                runner,
                new FakeAndroidDeviceService(),
                new FakeIosDeviceService(),
                new LogCaptureService(properties),
                properties);

        service.streamAndroidLogs("SERIAL-1", new LogStreamQuery("ALL", ""));

        assertThat(runner.command)
                .containsSubsequence("logcat", "-v", "threadtime", "-T", LogStreamService.ANDROID_INITIAL_REPLAY_LINES);
    }

    /**
     * 验证导出接口会停止当前采集会话，并把已写入的本次采集日志打成 zip。
     */
    @Test
    void exportCapturedLogsShouldStopActiveSessionAndZipCapture(@TempDir Path tempDir) throws IOException {
        CapturingStreamingCommandRunner runner = new CapturingStreamingCommandRunner();
        DevBridgeProperties properties = testProperties(tempDir);
        LogStreamService service = new LogStreamService(
                runner,
                new FakeAndroidDeviceService(),
                new FakeIosDeviceService(),
                new LogCaptureService(properties),
                properties);

        service.streamAndroidLogs("SERIAL-1", new LogStreamQuery("ALL", ""));
        runner.stdout.accept("07-01 10:00:00.000  100  200 I TestTag: first line");
        Path zip = service.exportCapturedLogs("android", "SERIAL-1");

        assertThat(runner.process.stopped).isTrue();
        assertThat(zipContent(zip)).contains("first line");
    }

    /**
     * 验证 Agent 采集入口复用同一会话生命周期，停止后底层进程确实终止。
     */
    @Test
    void agentCaptureShouldStopUnderlyingProcess(@TempDir Path tempDir) {
        CapturingStreamingCommandRunner runner = new CapturingStreamingCommandRunner();
        DevBridgeProperties properties = testProperties(tempDir);
        LogStreamService service = new LogStreamService(
                runner,
                new FakeAndroidDeviceService(),
                new FakeIosDeviceService(),
                new LogCaptureService(properties),
                properties);

        var started = service.startCapture("android", "SERIAL-1");
        runner.stdout.accept("07-01 10:00:00.000  100  200 I TestTag: agent line");
        var stopped = service.stop(started.sessionId());

        assertThat(started.status()).isEqualTo("running");
        assertThat(stopped.status()).isEqualTo("stopped");
        assertThat(runner.process.stopped).isTrue();
    }

    /**
     * 验证 idevicesyslog 的进程、子系统、PID、级别和消息能映射到统一日志模型。
     */
    @Test
    void parseIosLogLineShouldMapSyslogFields() {
        var event = LogStreamService.parseIosLogLine(
                1L,
                "Jun 30 12:25:24.754168 backboardd(BackBoardHIDEventProcessors)[64321] <Debug>: Motion event usagePage:0xFF0C usage:5");

        assertThat(event.id()).isEqualTo(1L);
        assertThat(event.timestamp()).isEqualTo("Jun 30 12:25:24.754168");
        assertThat(event.level()).isEqualTo("D");
        assertThat(event.pid()).isEqualTo("64321");
        assertThat(event.tag()).isEqualTo("BackBoardHIDEventProcessors");
        assertThat(event.message()).isEqualTo("Motion event usagePage:0xFF0C usage:5");
    }

    /**
     * 验证无子系统的 iOS 日志使用进程名作为 tag，并将 Error 映射为前端错误级别。
     */
    @Test
    void parseIosLogLineShouldFallbackToProcessName() {
        var event = LogStreamService.parseIosLogLine(
                2L,
                "Jun 30 12:25:24.788233 kernel[0] <Error>: buffer not found");

        assertThat(event.level()).isEqualTo("E");
        assertThat(event.pid()).isEqualTo("0");
        assertThat(event.tag()).isEqualTo("kernel");
        assertThat(event.message()).isEqualTo("buffer not found");
    }

    /**
     * 验证真实 idevicesyslog 常见的主机名和无小数秒时间格式不会降级为 INFO 空字段。
     */
    @Test
    void parseIosLogLineShouldSupportHostAndPlainSecondTimestamp() {
        var event = LogStreamService.parseIosLogLine(
                3L,
                "Jul  1 15:58:06 iPhone SpringBoard[456] <Warning>: application state changed");

        assertThat(event.timestamp()).isEqualTo("Jul  1 15:58:06");
        assertThat(event.level()).isEqualTo("W");
        assertThat(event.pid()).isEqualTo("456");
        assertThat(event.tag()).isEqualTo("SpringBoard");
        assertThat(event.message()).isEqualTo("application state changed");
    }

    /**
     * 验证 iOS 多行堆栈续行继承上一条日志上下文，避免前端显示空时间和空 PID。
     */
    @Test
    void parseIosLogLineShouldInheritContextForContinuationLine() {
        var previous = LogStreamService.parseIosLogLine(
                4L,
                "Jul  1 16:10:59.903265 PersistentConnection[69] <Debug>: timer callback");
        var event = LogStreamService.parseIosLogLine(5L, "\t0   PersistentConnection 0x00000001ab15a008 + 8200", previous);

        assertThat(event.timestamp()).isEqualTo(previous.timestamp());
        assertThat(event.level()).isEqualTo("D");
        assertThat(event.pid()).isEqualTo("69");
        assertThat(event.tag()).isEqualTo("PersistentConnection");
        assertThat(event.message()).contains("PersistentConnection");
    }

    /**
     * 测试用流式命令执行器，捕获启动参数但不真正启动外部进程。
     *
     * <p>by AI.Coding</p>
     */
    private static class CapturingStreamingCommandRunner extends StreamingCommandRunner {

        private List<String> command = List.of();
        private Consumer<String> stdout = line -> { };
        private FakeStreamingProcess process = new FakeStreamingProcess();

        /**
         * 创建不启动真实后台线程的流式命令测试替身。
         */
        private CapturingStreamingCommandRunner() {
            super(Runnable::run, new ScheduledThreadPoolExecutor(1));
        }

        /**
         * 捕获实时日志命令参数，返回可停止的空进程。
         *
         * @param command 命令参数
         * @param timeout 超时时间
         * @param stdout stdout 回调
         * @param stderr stderr 回调
         * @return 测试进程
         */
        @Override
        public StreamingProcess start(
                List<String> command,
                Duration timeout,
                Consumer<String> stdout,
                Consumer<String> stderr) {
            this.command = command;
            this.stdout = stdout;
            this.process = new FakeStreamingProcess();
            return process;
        }
    }

    /**
     * 测试用 Android 服务，避免实时日志命令测试依赖本机 adb 和真机。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeAndroidDeviceService extends AndroidDeviceService {

        /**
         * 创建测试 Android 服务。
         */
        FakeAndroidDeviceService() {
            super(
                    new ExecutableLocator(new DevBridgeProperties()),
                    new CommandRunner(new DevBridgeProperties(), Runnable::run),
                    new DevBridgeProperties(),
                    new AndroidPathGuard(),
                    new RemoteFileParser());
        }

        /**
         * 跳过真实设备详情读取，实时日志命令测试只关注启动参数。
         *
         * @param serial 设备序列号
         * @return 空详情
         */
        @Override
        public DeviceDetail getDetail(String serial) {
            return deviceDetail(serial, Platform.ANDROID, "Pixel 7 Pro");
        }

        /**
         * 返回固定 adb 命令名，便于断言参数序列。
         *
         * @return adb 命令
         */
        @Override
        public String adbExecutable() {
            return "adb";
        }
    }

    /**
     * 测试用 iOS 服务，Android 用例不会调用其真实能力。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeIosDeviceService extends IosDeviceService {

        /**
         * 创建测试 iOS 服务。
         */
        FakeIosDeviceService() {
            super(
                    new ExecutableLocator(new DevBridgeProperties()),
                    new CommandRunner(new DevBridgeProperties(), Runnable::run));
        }
    }

    /**
     * 测试用空长进程句柄。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeStreamingProcess implements StreamingProcess {

        private boolean stopped;

        /**
         * 返回固定会话 ID。
         *
         * @return 会话 ID
         */
        @Override
        public String id() {
            return "test-session";
        }

        /**
         * 停止前视为运行中，便于验证 Agent 状态查询。
         *
         * @return false
         */
        @Override
        public boolean isAlive() {
            return !stopped;
        }

        /**
         * 空停止实现。
         */
        @Override
        public void stop() {
            stopped = true;
        }
    }

    /**
     * 创建测试用配置，隔离日志采集和 zip 临时目录。
     *
     * @param tempDir 临时目录
     * @return DevBridge 配置
     */
    private static DevBridgeProperties testProperties(Path tempDir) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setLogCaptureRoot(tempDir.resolve("logs").toString());
        properties.setDownloadTempRoot(tempDir.resolve("downloads").toString());
        return properties;
    }

    /**
     * 创建测试设备详情，只填充日志采集需要的平台、序列号和型号。
     *
     * @param serial 序列号
     * @param platform 平台
     * @param model 型号
     * @return 设备详情
     */
    private static DeviceDetail deviceDetail(String serial, Platform platform, String model) {
        return new DeviceDetail(
                platform.getValue() + ":" + serial,
                serial,
                platform,
                DeviceStatus.CONNECTED,
                platform == Platform.IOS ? "Apple" : "Android",
                model,
                "",
                "",
                null,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                null);
    }

    /**
     * 读取 zip 中的文本内容，便于断言导出包包含采集日志。
     *
     * @param zip zip 路径
     * @return zip 文本内容
     * @throws IOException 读取失败时抛出
     */
    private static String zipContent(Path zip) throws IOException {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            input.getNextEntry();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
