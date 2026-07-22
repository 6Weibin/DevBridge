package com.devbridge.server.ai.tool.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.tool.artifact.ToolArtifactStore;
import com.devbridge.server.ai.tool.artifact.ToolArtifactStore.ArtifactWriteRequest;
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
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.AppDetail;
import com.devbridge.server.model.InstalledApp;
import com.devbridge.server.service.AndroidDeviceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * App 领域工具测试，覆盖风险、Artifact 安装和权限最小输出。
 *
 * <p>by AI.Coding</p>
 */
class AppDomainToolAdapterTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * 验证应用生命周期工具完整，卸载默认需要确认。
     */
    @Test
    void definitionsShouldExposeAppLifecycleWithConfirmation() {
        AppDomainToolAdapter adapter = adapter(new FakeAndroidDeviceService());
        var ids = adapter.definitions().stream().map(value -> value.identity().toolId()).toList();
        var uninstall = adapter.definitions().stream()
                .filter(value -> value.identity().toolId().equals("app.uninstall")).findFirst().orElseThrow();

        assertThat(ids).contains(
                "app.list", "app.detail.read", "app.install", "app.uninstall",
                "app.launch", "app.stop", "app.permissions.read");
        assertThat(adapter.assess(request("app.uninstall", packageArguments()), uninstall).action())
                .isEqualTo(RiskAction.CONFIRM);
    }

    /**
     * 验证安装只从 Artifact 物化 APK，调用完成后删除临时文件。
     */
    @Test
    void executeShouldInstallFromArtifactAndDeleteTemporaryApk() {
        FakeAndroidDeviceService service = new FakeAndroidDeviceService();
        ToolArtifactStore store = artifactStore();
        var metadata = store.write(new ArtifactWriteRequest(
                "apk", "application/vnd.android.package-archive", "INTERNAL",
                Instant.now().plusSeconds(3600), false, "gzip"),
                new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
        AppDomainToolAdapter adapter = adapter(service, store);
        ObjectNode arguments = packageArguments().put("artifactId", metadata.identity().artifactId());
        CallRequest request = request("app.install", arguments);

        CallResult result = adapter.execute(request, definition(adapter, request), allow());

        assertThat(result.payload().output().path("installed").asBoolean()).isTrue();
        assertThat(service.apkExistedDuringInstall).isTrue();
        assertThat(Files.exists(service.installedApk)).isFalse();
    }

    /**
     * 验证权限工具只输出权限相关字段，不暴露应用数据目录。
     */
    @Test
    void executeShouldReturnMinimalPermissionView() {
        AppDomainToolAdapter adapter = adapter(new FakeAndroidDeviceService());
        CallRequest request = request("app.permissions.read", packageArguments());

        CallResult result = adapter.execute(request, definition(adapter, request), allow());

        assertThat(result.payload().output().path("requestedPermissions")).hasSize(2);
        assertThat(result.payload().output().has("dataDir")).isFalse();
    }

    /**
     * 创建默认 Adapter。
     *
     * @param service Android 服务替身
     * @return Adapter
     */
    private AppDomainToolAdapter adapter(FakeAndroidDeviceService service) {
        return adapter(service, artifactStore());
    }

    /**
     * 创建指定 Artifact Store 的 Adapter。
     *
     * @param service Android 服务替身
     * @param store Artifact Store
     * @return Adapter
     */
    private AppDomainToolAdapter adapter(FakeAndroidDeviceService service, ToolArtifactStore store) {
        DevBridgeProperties properties = properties();
        return new AppDomainToolAdapter(service, store, properties, objectMapper);
    }

    /**
     * 创建 Artifact Store。
     *
     * @return Store
     */
    private ToolArtifactStore artifactStore() {
        return new ToolArtifactStore(properties(), objectMapper);
    }

    /**
     * 创建隔离目录配置。
     *
     * @return 配置
     */
    private DevBridgeProperties properties() {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setToolArtifactRoot(tempDir.resolve("artifacts").toString());
        properties.setDownloadTempRoot(tempDir.resolve("downloads").toString());
        return properties;
    }

    /**
     * 获取请求对应定义。
     *
     * @param adapter Adapter
     * @param request 请求
     * @return 定义
     */
    private ToolContract.Definition definition(AppDomainToolAdapter adapter, CallRequest request) {
        return adapter.definitions().stream()
                .filter(value -> value.identity().toolId().equals(request.tool().toolId()))
                .findFirst().orElseThrow();
    }

    /**
     * 创建包操作参数。
     *
     * @return 参数
     */
    private ObjectNode packageArguments() {
        return objectMapper.createObjectNode()
                .put("serial", "device-1")
                .put("packageName", "com.example.demo");
    }

    /**
     * 创建工具请求。
     *
     * @param toolId 工具 ID
     * @param arguments 参数
     * @return 请求
     */
    private CallRequest request(String toolId, ObjectNode arguments) {
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity("conversation", "", "turn", "step", "call", Instant.now()),
                new ToolReference(toolId, ToolContract.SCHEMA_VERSION),
                arguments,
                "digest",
                "key",
                Caller.AGENT,
                new ExecutionContext(Platform.ANDROID, "device-1", "", "", List.of()));
    }

    /**
     * 创建允许执行的风险决策。
     *
     * @return 风险决策
     */
    private RiskDecision allow() {
        return new RiskDecision(RiskLevel.MEDIUM, RiskAction.ALLOW, "test", "TEST", "测试", "", Instant.now());
    }

    /**
     * 不执行真实 ADB 命令的 Android 应用服务替身。
     *
     * <p>by AI.Coding</p>
     */
    private static class FakeAndroidDeviceService extends AndroidDeviceService {

        private Path installedApk;
        private boolean apkExistedDuringInstall;

        /**
         * 创建空依赖替身。
         */
        FakeAndroidDeviceService() {
            super(null, null, new DevBridgeProperties(), null, null);
        }

        /**
         * 返回固定应用列表。
         *
         * @param serial 序列号
         * @return 应用列表
         */
        @Override
        public List<InstalledApp> listInstalledApps(String serial) {
            return List.of(new InstalledApp("Demo", "com.example.demo", "1.0", "1", false));
        }

        /**
         * 返回固定应用详情。
         *
         * @param serial 序列号
         * @param packageName 包名
         * @return 应用详情
         */
        @Override
        public AppDetail getAppDetail(String serial, String packageName) {
            return detail(packageName);
        }

        /**
         * 记录安装时临时文件状态。
         *
         * @param serial 序列号
         * @param apkPath APK 路径
         * @param expectedPackageName 包名
         */
        @Override
        public void installApp(String serial, Path apkPath, String expectedPackageName) {
            installedApk = apkPath;
            apkExistedDuringInstall = Files.isRegularFile(apkPath);
        }

        /**
         * 测试卸载不执行命令。
         *
         * @param serial 序列号
         * @param packageName 包名
         */
        @Override
        public void uninstallApp(String serial, String packageName) {
        }

        /**
         * 返回启动后运行状态。
         *
         * @param serial 序列号
         * @param packageName 包名
         * @return true
         */
        @Override
        public boolean launchApp(String serial, String packageName) {
            return true;
        }

        /**
         * 返回停止后状态。
         *
         * @param serial 序列号
         * @param packageName 包名
         * @return true
         */
        @Override
        public boolean stopApp(String serial, String packageName) {
            return true;
        }

        /**
         * 创建权限详情。
         *
         * @param packageName 包名
         * @return 详情
         */
        private AppDetail detail(String packageName) {
            return new AppDetail(
                    "Demo", packageName, "1.0", "1", "10001", "23", "35", "", "", "",
                    "/data/app/base.apk", "/data/app/base.apk", "/data/user/0/" + packageName,
                    false, "enabled", true, false, false, false,
                    List.of("android.permission.INTERNET", "android.permission.CAMERA"),
                    List.of("android.permission.INTERNET"));
        }
    }
}
