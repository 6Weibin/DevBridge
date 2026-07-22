package com.devbridge.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.command.CommandResult;
import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.Platform;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * iOS 设备服务测试，覆盖 libimobiledevice 输出解析的关键格式。
 *
 * <p>by AI.Coding</p>
 */
class IosDeviceServiceTest {

    private static final String UDID = "00008120-00182C181EB8C01E";

    /**
     * 验证 ideviceinfo 的 key-value 输出能稳定解析，并忽略无效行。
     */
    @Test
    void parseKeyValueLinesShouldKeepKnownFields() {
        var values = IosDeviceService.parseKeyValueLines(List.of(
                "ProductType: iPhone15,3",
                "ProductVersion: 26.5",
                "BuildVersion: 23F77",
                "CPUArchitecture: arm64e",
                "invalid line"));

        assertThat(values)
                .containsEntry("ProductType", "iPhone15,3")
                .containsEntry("ProductVersion", "26.5")
                .containsEntry("BuildVersion", "23F77")
                .containsEntry("CPUArchitecture", "arm64e")
                .doesNotContainKey("invalid line");
    }

    /**
     * 验证 iOS 详情使用白名单 key 读取，磁盘可选字段失败时不影响设备名称和硬件信息。
     */
    @Test
    void getDetailShouldKeepBaseFieldsWhenOptionalDiskKeyFails() {
        FakeCommandRunner runner = new FakeCommandRunner();
        IosDeviceService service = new IosDeviceService(new FakeExecutableLocator(), runner);

        var detail = service.getDetail(UDID);

        assertThat(detail.platform()).isEqualTo(Platform.IOS);
        assertThat(detail.deviceName()).isEqualTo("God Father");
        assertThat(detail.model()).isEqualTo("iPhone15,3");
        assertThat(detail.osVersion()).isEqualTo("iOS 26.5");
        assertThat(detail.battery()).isEqualTo(65);
        assertThat(detail.hardwareModel()).isEqualTo("D74AP");
        assertThat(detail.hardwarePlatform()).isEqualTo("t8120");
        assertThat(detail.cpu()).isEqualTo("t8120 / D74AP");
        assertThat(detail.storage()).contains("可用").contains("总计");
        assertThat(runner.commands)
                .filteredOn(command -> command.contains("com.apple.disk_usage"))
                .allMatch(command -> command.contains("-k"));
    }

    /**
     * 测试用工具定位器，避免单元测试依赖本机是否安装 libimobiledevice。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeExecutableLocator extends ExecutableLocator {

        /**
         * 创建测试定位器。
         */
        FakeExecutableLocator() {
            super(new DevBridgeProperties());
        }

        /**
         * 返回工具名作为命令首段，便于 FakeCommandRunner 判定调用意图。
         *
         * @param definition 工具定义
         * @return 工具名
         */
        @Override
        public String locate(ToolDefinition definition) {
            return definition.name();
        }
    }

    /**
     * 测试用命令执行器，模拟真机 ideviceinfo 单 key 输出。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeCommandRunner extends CommandRunner {

        private final List<List<String>> commands = new ArrayList<>();

        /**
         * 创建测试命令执行器。
         */
        FakeCommandRunner() {
            super(new DevBridgeProperties(), Runnable::run);
        }

        /**
         * 根据命令参数返回稳定样本；AmountDataAvailable 故意失败以验证可选字段兜底。
         *
         * @param command 命令及参数
         * @return 模拟命令结果
         */
        @Override
        public CommandResult run(List<String> command) {
            commands.add(command);
            if ("idevice_id".equals(command.get(0))) {
                return new CommandResult(0, List.of(UDID), List.of(), false);
            }
            if (command.contains("com.apple.disk_usage") && !command.contains("-k")) {
                return new CommandResult(133, List.of("NANDInfo: huge"), List.of(), false);
            }
            return keyResult(command);
        }

        /**
         * 返回指定 key 的模拟输出。
         *
         * @param command 命令及参数
         * @return 模拟 key 输出
         */
        private CommandResult keyResult(List<String> command) {
            String key = command.get(command.indexOf("-k") + 1);
            return switch (key) {
                case "ProductType" -> success("iPhone15,3");
                case "HardwareModel" -> success("D74AP");
                case "HardwarePlatform" -> success("t8120");
                case "ProductVersion" -> success("26.5");
                case "TotalDataAvailable" -> success("3949871104");
                case "TotalDiskCapacity" -> success("256000000000");
                case "BatteryCurrentCapacity" -> success("65");
                case "DeviceName" -> success("God Father");
                case "BuildVersion" -> success("23F77");
                case "UniqueChipID" -> success("6803881547317278");
                case "ActivationState" -> success("Activated");
                case "CPUArchitecture" -> success("arm64e");
                case "DeviceClass" -> success("iPhone");
                case "ModelNumber" -> success("MQ8A3");
                case "AmountDataAvailable" -> new CommandResult(133, List.of(), List.of("disk key failed"), false);
                default -> success("");
            };
        }

        /**
         * 构造成功命令结果。
         *
         * @param value 标准输出第一行
         * @return 成功结果
         */
        private CommandResult success(String value) {
            return new CommandResult(0, List.of(value), List.of(), false);
        }
    }
}
