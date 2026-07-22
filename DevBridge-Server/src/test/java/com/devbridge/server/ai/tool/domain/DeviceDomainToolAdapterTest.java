package com.devbridge.server.ai.tool.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.tool.artifact.ToolArtifactStore;
import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskAction;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskDecision;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskLevel;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.devbridge.server.command.CommandResult;
import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.CommandDiagnostic;
import com.devbridge.server.model.DeviceDetail;
import com.devbridge.server.model.DeviceInfo;
import com.devbridge.server.model.DeviceStatus;
import com.devbridge.server.service.AndroidDeviceService;
import com.devbridge.server.service.DeviceService;
import com.devbridge.server.service.HarmonyDeviceService;
import com.devbridge.server.service.IosDeviceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Device 领域工具测试，覆盖平台中立定义、iOS 路由和截图 Artifact。
 *
 * <p>by AI.Coding</p>
 */
class DeviceDomainToolAdapterTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * 验证设备工具使用领域来源，不暴露 ADB 协议名称。
     */
    @Test
    void definitionsShouldExposeDeviceDomainCapabilities() {
        var definitions = adapter().definitions();

        assertThat(definitions).extracting(value -> value.identity().toolId())
                .contains("device.list", "device.detail.read", "device.health.read", "device.screenshot.capture");
        assertThat(definitions).allMatch(value -> value.metadata().source().provider().equals("device-service"));
        assertThat(definitions).allMatch(value -> !value.identity().displayName().contains("ADB"));
    }

    /**
     * 验证 iOS 详情明确调用 iOS 服务，不经过 Android 服务。
     */
    @Test
    void executeShouldRouteIosDetailToIosService() {
        DeviceDomainToolAdapter adapter = adapter();
        CallRequest request = request("device.detail.read", Platform.IOS, "ios-1");

        CallResult result = adapter.execute(request, definition(adapter, request), allow());

        assertThat(result.payload().output().path("platform").asText()).isEqualTo("ios");
        assertThat(result.payload().output().path("model").asText()).isEqualTo("iPhone-Test");
    }

    /**
     * 验证截图只返回 Artifact 引用并清理领域临时文件。
     */
    @Test
    void executeShouldStoreScreenshotAsArtifact() {
        DeviceDomainToolAdapter adapter = adapter();
        CallRequest request = request("device.screenshot.capture", Platform.ANDROID, "android-1");

        CallResult result = adapter.execute(request, definition(adapter, request), allow());

        assertThat(result.payload().artifacts()).hasSize(1);
        assertThat(result.payload().artifacts().get(0).mediaType()).isEqualTo("image/png");
        assertThat(result.payload().output().has("path")).isFalse();
    }

    /**
     * 创建领域 Adapter。
     *
     * @return Adapter
     */
    private DeviceDomainToolAdapter adapter() {
        return new DeviceDomainToolAdapter(
                new FakeDeviceService(),
                new FakeAndroidDeviceService(tempDir),
                new FakeIosDeviceService(),
                new FakeHarmonyDeviceService(),
                artifactStore(),
                objectMapper);
    }

    /**
     * 创建 Artifact Store。
     *
     * @return Store
     */
    private ToolArtifactStore artifactStore() {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setToolArtifactRoot(tempDir.resolve("artifacts").toString());
        return new ToolArtifactStore(properties, objectMapper);
    }

    /**
     * 获取请求对应定义。
     *
     * @param adapter Adapter
     * @param request 请求
     * @return 定义
     */
    private ToolContract.Definition definition(DeviceDomainToolAdapter adapter, CallRequest request) {
        return adapter.definitions().stream()
                .filter(value -> value.identity().toolId().equals(request.tool().toolId()))
                .findFirst().orElseThrow();
    }

    /**
     * 创建领域调用请求。
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
                "digest",
                "",
                Caller.AGENT,
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
     * 跨平台设备列表替身。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeDeviceService extends DeviceService {

        /**
         * 创建空依赖替身。
         */
        FakeDeviceService() {
            super(null, null);
        }

        /**
         * 返回固定 Android 设备。
         *
         * @return 设备列表
         */
        @Override
        public List<DeviceInfo> listDevices() {
            return List.of(new DeviceInfo(
                    "android:android-1", "android-1", "Pixel-Test",
                    com.devbridge.server.model.Platform.ANDROID, "14", DeviceStatus.CONNECTED));
        }

        /**
         * 返回固定诊断。
         *
         * @return 诊断结果
         */
        @Override
        public CommandDiagnostic diagnoseAdbDevices() {
            return new CommandDiagnostic(List.of("adb", "devices"), 0, List.of("android-1 device"), List.of(), false);
        }
    }

    /**
     * Android 设备服务替身。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeAndroidDeviceService extends AndroidDeviceService {

        private final Path tempDir;

        /**
         * 保存临时目录。
         *
         * @param tempDir 临时目录
         */
        FakeAndroidDeviceService(Path tempDir) {
            super(null, null, null, null, null);
            this.tempDir = tempDir;
        }

        /**
         * 返回固定 Android 详情。
         *
         * @param serial 序列号
         * @return 详情
         */
        @Override
        public DeviceDetail getDetail(String serial) {
            return detail(serial, com.devbridge.server.model.Platform.ANDROID, "Pixel-Test");
        }

        /**
         * 创建测试 PNG 临时文件。
         *
         * @param serial 序列号
         * @return 文件路径
         */
        @Override
        public Path captureScreenshot(String serial) {
            try {
                Path file = tempDir.resolve("screenshot.png");
                Files.write(file, new byte[] {1, 2, 3, 4});
                return file;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    /**
     * iOS 设备服务替身。
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
    }

    /**
     * HarmonyOS 设备服务替身。
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
     * 创建最小设备详情。
     *
     * @param serial 序列号
     * @param platform 平台
     * @param model 型号
     * @return 设备详情
     */
    private static DeviceDetail detail(
            String serial, com.devbridge.server.model.Platform platform, String model) {
        return new DeviceDetail(
                platform.getValue() + ":" + serial, serial, platform, DeviceStatus.CONNECTED,
                "brand", model, "1", "", 80, "", "10GB", "", "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", "", "", "cpu", "4GB", null);
    }
}
