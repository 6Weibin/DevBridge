package com.devbridge.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.model.AppDetail;
import com.devbridge.server.model.DeviceStatus;
import com.devbridge.server.model.InstalledApp;
import com.devbridge.server.model.Platform;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 设备输出解析测试，确保常见 CLI 输出能稳定转换成统一模型。
 *
 * <p>by AI.Coding</p>
 */
class DeviceOutputParserTest {

    private final DeviceOutputParser parser = new DeviceOutputParser();

    /**
     * 验证 adb 输出中的 connected、unauthorized、offline 状态都能被识别。
     */
    @Test
    void parseAdbDevicesShouldMapStatuses() {
        var devices = parser.parseAdbDevices(List.of(
                "List of devices attached",
                "ZY22FLBP9C\tdevice",
                "R58M34ZWKRB unauthorized",
                "emulator-5554 offline"));

        assertThat(devices).hasSize(3);
        assertThat(devices.get(0).platform()).isEqualTo(Platform.ANDROID);
        assertThat(devices.get(0).status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(devices.get(1).status()).isEqualTo(DeviceStatus.UNAUTHORIZED);
        assertThat(devices.get(2).status()).isEqualTo(DeviceStatus.OFFLINE);
    }

    /**
     * 验证 hdc 空输出不会生成虚假设备。
     */
    @Test
    void parseHdcTargetsShouldIgnoreEmptyMarker() {
        var devices = parser.parseHdcTargets(List.of("[Empty]", ""));

        assertThat(devices).isEmpty();
    }

    /**
     * 验证 hdc 输出中的 unauthorized 状态会保留给前端做授权提示。
     */
    @Test
    void parseHdcTargetsShouldMapUnauthorized() {
        var devices = parser.parseHdcTargets(List.of("HDC-ABC unauthorized"));

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).status()).isEqualTo(DeviceStatus.UNAUTHORIZED);
    }

    /**
     * 验证 Android 包列表输出能解析包名、版本号和系统应用标记。
     */
    @Test
    void parseAndroidPackagesShouldMapPackageMetadata() {
        var apps = parser.parseAndroidPackages(List.of(
                "package:/data/app/~~abc/base.apk=com.example.demo uid:10234 versionCode:42",
                "package:/data/app/~~abc==/base.apk=com.example.equals uid:10235 versionCode:43",
                "package:/system/priv-app/Settings/Settings.apk=com.android.settings uid:1000 versionCode:35",
                "invalid line"));

        assertThat(apps).hasSize(3);
        assertThat(apps.get(0)).isEqualTo(new InstalledApp(
                "com.example.demo",
                "com.example.demo",
                "",
                "42",
                false));
        assertThat(apps.get(1).packageName()).isEqualTo("com.example.equals");
        assertThat(apps.get(2).packageName()).isEqualTo("com.android.settings");
        assertThat(apps.get(2).systemApp()).isTrue();
    }

    /**
     * 验证包版本名称能从 dumpsys package 输出中提取。
     */
    @Test
    void parseAndroidPackageVersionNameShouldReturnVersionName() {
        String versionName = parser.parseAndroidPackageVersionName(List.of(
                "Packages:",
                "  Package [com.example.demo] (abc):",
                "    versionCode=42 minSdk=23 targetSdk=35",
                "    versionName=1.2.3"));

        assertThat(versionName).isEqualTo("1.2.3");
    }

    /**
     * 验证 dumpsys package packages 能批量解析每个包的版本名称。
     */
    @Test
    void parseAndroidPackageVersionNamesShouldMapPackages() {
        var versionNames = parser.parseAndroidPackageVersionNames(List.of(
                "Package [com.example.demo] (abc):",
                "  versionCode=42 minSdk=23 targetSdk=35",
                "  versionName=1.2.3",
                "Package [com.android.settings] (def):",
                "  versionCode=35 minSdk=35 targetSdk=35",
                "  versionName=15"));

        assertThat(versionNames)
                .containsEntry("com.example.demo", "1.2.3")
                .containsEntry("com.android.settings", "15");
    }

    /**
     * 验证 dumpsys/cmd package 暴露应用 label 时会映射成展示名称。
     */
    @Test
    void parseAndroidPackageLabelsShouldMapReadableLabels() {
        var labels = parser.parseAndroidPackageLabels(List.of(
                "Package [com.example.demo] (abc):",
                "  nonLocalizedLabel=Demo App icon=0x7f010001",
                "Package [com.example.quoted] (def):",
                "  application-label-zh:'Quoted App'",
                "Package [com.example.empty] (ghi):",
                "  nonLocalizedLabel=null labelRes=0x7f010002"));

        assertThat(labels)
                .containsEntry("com.example.demo", "Demo App")
                .containsEntry("com.example.quoted", "Quoted App")
                .doesNotContainKey("com.example.empty");
    }

    /**
     * 验证单个 Android 应用详情能解析版本、状态和权限摘要。
     */
    @Test
    void parseAndroidAppDetailShouldMapPackageDetail() {
        AppDetail detail = parser.parseAndroidAppDetail(List.of(
                "Package [com.example.demo] (abc):",
                "  userId=10234",
                "  codePath=/data/app/com.example.demo",
                "  resourcePath=/data/app/com.example.demo/base.apk",
                "  dataDir=/data/user/0/com.example.demo",
                "  versionCode=42 minSdk=23 targetSdk=35",
                "  versionName=1.2.3",
                "  firstInstallTime=2026-07-01 10:00:00",
                "  lastUpdateTime=2026-07-02 10:00:00",
                "  installerPackageName=com.android.vending",
                "  nonLocalizedLabel=Demo App icon=0x7f010001",
                "  requested permissions:",
                "    android.permission.INTERNET",
                "    com.example.demo.permission.SYNC",
                "  install permissions:",
                "    android.permission.INTERNET: granted=true",
                "    android.permission.CAMERA: granted=false",
                "  User 0: ceDataInode=1 installed=true hidden=false suspended=false stopped=true enabled=0"),
                "com.example.demo");

        assertThat(detail.name()).isEqualTo("Demo App");
        assertThat(detail.packageName()).isEqualTo("com.example.demo");
        assertThat(detail.versionName()).isEqualTo("1.2.3");
        assertThat(detail.versionCode()).isEqualTo("42");
        assertThat(detail.uid()).isEqualTo("10234");
        assertThat(detail.minSdk()).isEqualTo("23");
        assertThat(detail.targetSdk()).isEqualTo("35");
        assertThat(detail.installed()).isTrue();
        assertThat(detail.stopped()).isTrue();
        assertThat(detail.requestedPermissions()).containsExactly(
                "android.permission.INTERNET",
                "com.example.demo.permission.SYNC");
        assertThat(detail.grantedPermissions()).containsExactly("android.permission.INTERNET");
    }

    /**
     * 验证 iOS UDID 列表能被转换为已连接设备。
     */
    @Test
    void parseIosDevicesShouldUseUdidAsSerial() {
        var devices = parser.parseIosDevices(List.of("00008130-001A7C3E3A82201E"));

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).platform()).isEqualTo(Platform.IOS);
        assertThat(devices.get(0).status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(devices.get(0).serial()).isEqualTo("00008130-001A7C3E3A82201E");
    }
}
