package com.devbridge.server.ai.tool.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityQuery;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskAction;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskDecision;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskLevel;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.devbridge.server.ai.tool.gateway.ToolRegistry;
import com.devbridge.server.command.CommandResult;
import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.DeviceDetail;
import com.devbridge.server.model.DeviceStatus;
import com.devbridge.server.service.ExecutableLocator;
import com.devbridge.server.service.HarmonyDeviceService;
import com.devbridge.server.service.IosDeviceService;
import com.devbridge.server.service.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * iOS/Harmony 平台 Adapter 测试，覆盖来源、平台隔离和 HDC 参数化调用。
 *
 * <p>by AI.Coding</p>
 */
class MobilePlatformToolAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * 验证 iOS 和 HarmonyOS 工具使用各自平台来源，不包含 Android/ADB 定义。
     */
    @Test
    void definitionsShouldUseNativePlatformProviders() {
        MobilePlatformToolAdapter adapter = adapter();

        assertThat(adapter.definitions()).filteredOn(value -> value.metadata().platforms().contains(Platform.IOS))
                .allMatch(value -> value.metadata().source().provider().equals("libimobiledevice"));
        assertThat(adapter.definitions()).filteredOn(value -> value.metadata().platforms().contains(Platform.HARMONY_OS))
                .allMatch(value -> value.metadata().source().provider().equals("hdc"));
        assertThat(adapter.definitions()).noneMatch(value -> value.identity().toolId().startsWith("android.adb."));
    }

    /**
     * 验证 HarmonyOS 详情通过 Harmony 服务执行并返回正确平台。
     */
    @Test
    void executeShouldReturnHarmonyDetail() {
        MobilePlatformToolAdapter adapter = adapter();
        CallRequest request = request("harmony.device.detail.read", Platform.HARMONY_OS, "HARMONY-1");
        var definition = adapter.definitions().stream()
                .filter(value -> value.identity().toolId().equals(request.tool().toolId())).findFirst().orElseThrow();

        CallResult result = adapter.execute(request, definition, allow());

        assertThat(result.payload().output().path("platform").asText()).isEqualTo("harmony");
        assertThat(result.payload().output().path("model").asText()).isEqualTo("Harmony-Test");
    }

    /**
     * 验证 HDC 服务使用目标序列号和白名单 param get 参数数组。
     */
    @Test
    void harmonyServiceShouldUseParameterizedHdcCommands() {
        HarmonyCommandRunner runner = new HarmonyCommandRunner();
        HarmonyDeviceService service = new HarmonyDeviceService(new FakeExecutableLocator(), runner);

        DeviceDetail detail = service.getDetail("HARMONY-1");

        assertThat(detail.model()).isEqualTo("Mate-Test");
        assertThat(runner.commands).anySatisfy(command -> assertThat(command)
                .containsExactly("hdc", "-t", "HARMONY-1", "shell", "param", "get", "const.product.model"));
    }

    /**
     * 验证按 iOS 平台查询能力时不会返回 Android ADB 工具。
     */
    @Test
    void registryShouldNotExposeAdbToolsForIosPlatform() {
        ToolRegistry registry = new ToolRegistry(List.of(adapter()));

        var tools = registry.capabilities(new CapabilityQuery(
                Platform.IOS, List.of("device.detail.read"), null, false));

        assertThat(tools).extracting(value -> value.toolId())
                .contains("ios.device.detail.read")
                .allMatch(value -> !value.startsWith("android.adb."));
    }

    /**
     * 创建平台 Adapter。
     *
     * @return Adapter
     */
    private MobilePlatformToolAdapter adapter() {
        return new MobilePlatformToolAdapter(
                new FakeIosDeviceService(), new FakeHarmonyDeviceService(), objectMapper);
    }

    /**
     * 创建平台调用请求。
     *
     * @param toolId 工具 ID
     * @param platform 平台
     * @param serial 序列号
     * @return 请求
     */
    private CallRequest request(String toolId, Platform platform, String serial) {
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity("conversation", "", "turn", "step", "call", Instant.now()),
                new ToolReference(toolId, ToolContract.SCHEMA_VERSION),
                objectMapper.createObjectNode().put("serial", serial),
                "digest", "", Caller.AGENT,
                new ExecutionContext(platform, serial, "", "", List.of()));
    }

    /**
     * 创建允许执行的风险决策。
     *
     * @return 风险决策
     */
    private RiskDecision allow() {
        return new RiskDecision(RiskLevel.LOW, RiskAction.ALLOW, "test", "TEST", "测试", "", Instant.now());
    }

    /**
     * iOS 服务替身。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeIosDeviceService extends IosDeviceService {

        /**
         * 创建空依赖替身。
         */
        FakeIosDeviceService() {
            super(null, null);
        }

        /**
         * 返回固定 iOS 详情。
         *
         * @param udid UDID
         * @return 详情
         */
        @Override
        public DeviceDetail getDetail(String udid) {
            return detail(udid, com.devbridge.server.model.Platform.IOS, "iPhone-Test");
        }

        /**
         * 日志能力测试不访问真机。
         *
         * @param udid UDID
         */
        @Override
        public void ensureLoggable(String udid) {
        }

        /**
         * 返回固定工具路径。
         *
         * @return 工具名
         */
        @Override
        public String idevicesyslogExecutable() {
            return "idevicesyslog";
        }
    }

    /**
     * HarmonyOS 服务替身。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeHarmonyDeviceService extends HarmonyDeviceService {

        /**
         * 创建空依赖替身。
         */
        FakeHarmonyDeviceService() {
            super(null, null);
        }

        /**
         * 返回固定 HarmonyOS 详情。
         *
         * @param serial 序列号
         * @return 详情
         */
        @Override
        public DeviceDetail getDetail(String serial) {
            return detail(serial, com.devbridge.server.model.Platform.HARMONY, "Harmony-Test");
        }
    }

    /**
     * 固定返回 hdc 的工具定位器。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeExecutableLocator extends ExecutableLocator {

        /**
         * 创建定位器。
         */
        FakeExecutableLocator() {
            super(new DevBridgeProperties());
        }

        /**
         * 返回 hdc。
         *
         * @param definition 工具定义
         * @return hdc
         */
        @Override
        public String locate(ToolDefinition definition) {
            return "hdc";
        }
    }

    /**
     * HDC 命令执行替身。
     *
     * <p>by AI.Coding</p>
     */
    private static class HarmonyCommandRunner extends CommandRunner {

        private final List<List<String>> commands = new ArrayList<>();

        /**
         * 创建执行器。
         */
        HarmonyCommandRunner() {
            super(new DevBridgeProperties(), Runnable::run);
        }

        /**
         * 返回连接列表和参数值。
         *
         * @param command 命令参数
         * @return 命令结果
         */
        @Override
        public CommandResult run(List<String> command) {
            commands.add(command);
            if (command.contains("targets")) {
                return new CommandResult(0, List.of("HARMONY-1"), List.of(), false);
            }
            String key = command.get(command.size() - 1);
            String value = switch (key) {
                case "const.product.brand" -> "Huawei";
                case "const.product.model" -> "Mate-Test";
                case "const.product.software.version" -> "HarmonyOS 5";
                case "const.ohos.apiversion" -> "20";
                default -> "arm64-v8a";
            };
            return new CommandResult(0, List.of(value), List.of(), false);
        }
    }

    /**
     * 创建最小设备详情。
     *
     * @param serial 序列号
     * @param platform 平台
     * @param model 型号
     * @return 详情
     */
    private static DeviceDetail detail(
            String serial, com.devbridge.server.model.Platform platform, String model) {
        return new DeviceDetail(
                platform.getValue() + ":" + serial, serial, platform, DeviceStatus.CONNECTED,
                "brand", model, "1", "", null, "", "", "", "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", "", "", "cpu", "", null);
    }
}
