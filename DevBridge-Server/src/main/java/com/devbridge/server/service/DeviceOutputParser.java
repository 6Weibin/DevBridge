package com.devbridge.server.service;

import com.devbridge.server.model.AppDetail;
import com.devbridge.server.model.DeviceInfo;
import com.devbridge.server.model.DeviceStatus;
import com.devbridge.server.model.InstalledApp;
import com.devbridge.server.model.Platform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 设备命令输出解析器，隔离不同平台 CLI 的文本格式差异。
 *
 * <p>by AI.Coding</p>
 */
public class DeviceOutputParser {

    /**
     * 解析 `adb devices` 输出。
     *
     * @param lines 命令输出行
     * @return Android 设备列表
     */
    public List<DeviceInfo> parseAdbDevices(List<String> lines) {
        List<DeviceInfo> devices = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("List of devices")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                devices.add(device(Platform.ANDROID, parts[0], "Android Device", status(parts[1])));
            }
        }
        return devices;
    }

    /**
     * 解析 `hdc list targets` 输出。
     *
     * @param lines 命令输出行
     * @return HarmonyOS 设备列表
     */
    public List<DeviceInfo> parseHdcTargets(List<String> lines) {
        List<DeviceInfo> devices = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("[Empty]")) {
                continue;
            }
            DeviceStatus status = trimmed.toLowerCase(Locale.ROOT).contains("unauthorized")
                    ? DeviceStatus.UNAUTHORIZED
                    : DeviceStatus.CONNECTED;
            String serial = trimmed.split("\\s+")[0];
            devices.add(device(Platform.HARMONY, serial, "HarmonyOS Device", status));
        }
        return devices;
    }

    /**
     * 解析 `idevice_id -l` 输出。
     *
     * @param lines 命令输出行
     * @return iOS 设备列表
     */
    public List<DeviceInfo> parseIosDevices(List<String> lines) {
        List<DeviceInfo> devices = new ArrayList<>();
        for (String line : lines) {
            String udid = line.trim();
            if (!udid.isEmpty()) {
                devices.add(device(Platform.IOS, udid, "iOS Device", DeviceStatus.CONNECTED));
            }
        }
        return devices;
    }

    /**
     * 解析 Android 包列表输出。
     *
     * @param lines `cmd package list packages -f -U --show-versioncode` 输出行
     * @return 已安装应用列表
     */
    public List<InstalledApp> parseAndroidPackages(List<String> lines) {
        List<InstalledApp> apps = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("package:")) {
                continue;
            }
            String payload = trimmed.substring("package:".length()).trim();
            int packageSplitIndex = payload.lastIndexOf("=");
            if (packageSplitIndex < 0) {
                continue;
            }
            String apkPath = payload.substring(0, packageSplitIndex).trim();
            String metadata = payload.substring(packageSplitIndex + 1).trim();
            String packageName = firstToken(metadata);
            if (!StringUtils.hasText(packageName)) {
                continue;
            }
            apps.add(new InstalledApp(
                    packageName,
                    packageName,
                    "",
                    metadataValue(metadata, "versionCode"),
                    isSystemApk(apkPath)));
        }
        return apps;
    }

    /**
     * 解析 dumpsys package 中每个包的版本名称。
     *
     * @param lines dumpsys package 输出行
     * @return 包名到版本名称的映射
     */
    public Map<String, String> parseAndroidPackageVersionNames(List<String> lines) {
        Map<String, String> versionNames = new HashMap<>();
        String currentPackage = "";
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Package [") && trimmed.contains("]")) {
                currentPackage = trimmed.substring("Package [".length(), trimmed.indexOf(']')).trim();
                continue;
            }
            if (StringUtils.hasText(currentPackage) && trimmed.startsWith("versionName=")) {
                versionNames.put(currentPackage, trimmed.substring("versionName=".length()).trim());
            }
        }
        return versionNames;
    }

    /**
     * 解析 dumpsys/cmd package 输出中的应用显示名称。
     *
     * @param lines dumpsys package 输出行
     * @return 包名到应用显示名称的映射
     */
    public Map<String, String> parseAndroidPackageLabels(List<String> lines) {
        Map<String, String> labels = new HashMap<>();
        String currentPackage = "";
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Package [") && trimmed.contains("]")) {
                currentPackage = trimmed.substring("Package [".length(), trimmed.indexOf(']')).trim();
                continue;
            }
            String label = androidLabel(trimmed);
            // dumpsys 在不同系统上是否输出 label 不稳定；只有解析到可展示文本时才覆盖包名回退值。
            if (StringUtils.hasText(currentPackage) && StringUtils.hasText(label)) {
                labels.putIfAbsent(currentPackage, label);
            }
        }
        return labels;
    }

    /**
     * 解析单个 Android 应用详情。
     *
     * @param lines dumpsys package 单包输出
     * @param fallbackPackageName 请求包名，输出缺失包名时作为回退
     * @return 应用详情
     */
    public AppDetail parseAndroidAppDetail(List<String> lines, String fallbackPackageName) {
        AndroidAppDetailBuilder detail = new AndroidAppDetailBuilder(fallbackPackageName);
        PermissionSection permissionSection = PermissionSection.NONE;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Package [") && trimmed.contains("]")) {
                detail.packageName = trimmed.substring("Package [".length(), trimmed.indexOf(']')).trim();
            }
            applyAndroidAppScalar(detail, trimmed);
            applyAndroidAppState(detail, trimmed);
            String label = androidLabel(trimmed);
            if (StringUtils.hasText(label)) {
                detail.name = label;
            }
            permissionSection = nextPermissionSection(trimmed, permissionSection);
            collectPermission(detail, trimmed, permissionSection);
        }
        return detail.toDetail();
    }

    /**
     * 解析应用详情中的标量字段。
     *
     * @param detail 应用详情构建器
     * @param line 单行输出
     */
    private void applyAndroidAppScalar(AndroidAppDetailBuilder detail, String line) {
        if (line.startsWith("versionCode=")) {
            detail.versionCode = assignmentValue(line, "versionCode");
            detail.minSdk = assignmentValue(line, "minSdk");
            detail.targetSdk = assignmentValue(line, "targetSdk");
        } else if (line.startsWith("versionName=")) {
            detail.versionName = line.substring("versionName=".length()).trim();
        } else if (line.startsWith("userId=") || line.startsWith("appId=")) {
            detail.uid = line.substring(line.indexOf('=') + 1).trim();
        } else if (line.startsWith("firstInstallTime=")) {
            detail.firstInstallTime = line.substring("firstInstallTime=".length()).trim();
        } else if (line.startsWith("lastUpdateTime=")) {
            detail.lastUpdateTime = line.substring("lastUpdateTime=".length()).trim();
        } else if (line.startsWith("installerPackageName=")) {
            detail.installerPackageName = line.substring("installerPackageName=".length()).trim();
        } else {
            applyAndroidAppPath(detail, line);
        }
    }

    /**
     * 解析应用路径字段，并根据路径补充系统应用判断。
     *
     * @param detail 应用详情构建器
     * @param line 单行输出
     */
    private void applyAndroidAppPath(AndroidAppDetailBuilder detail, String line) {
        if (line.startsWith("codePath=")) {
            detail.codePath = line.substring("codePath=".length()).trim();
            detail.systemApp = isSystemApk(detail.codePath);
        } else if (line.startsWith("resourcePath=")) {
            detail.resourcePath = line.substring("resourcePath=".length()).trim();
        } else if (line.startsWith("dataDir=")) {
            detail.dataDir = line.substring("dataDir=".length()).trim();
        } else if (line.startsWith("pkgFlags=") || line.startsWith("privateFlags=")) {
            // 部分系统应用 codePath 不在标准目录，flags 中的 SYSTEM/PRIVILEGED 可作为补充判断。
            detail.systemApp = detail.systemApp || line.contains(" SYSTEM") || line.contains(" PRIVILEGED");
        }
    }

    /**
     * 解析 User 状态行中的安装、隐藏、停止和启用状态。
     *
     * @param detail 应用详情构建器
     * @param line 单行输出
     */
    private void applyAndroidAppState(AndroidAppDetailBuilder detail, String line) {
        if (!line.startsWith("User ") || !line.contains(":")) {
            return;
        }
        detail.installed = booleanAssignment(line, "installed", detail.installed);
        detail.hidden = booleanAssignment(line, "hidden", detail.hidden);
        detail.stopped = booleanAssignment(line, "stopped", detail.stopped);
        detail.suspended = booleanAssignment(line, "suspended", detail.suspended);
        String enabled = assignmentValue(line, "enabled");
        if (StringUtils.hasText(enabled)) {
            detail.enabledState = enabled;
        }
    }

    /**
     * 根据当前行判断权限解析区段。
     *
     * @param line 单行输出
     * @param current 当前区段
     * @return 下一行应使用的权限区段
     */
    private PermissionSection nextPermissionSection(String line, PermissionSection current) {
        if ("requested permissions:".equals(line)) {
            return PermissionSection.REQUESTED;
        }
        if ("install permissions:".equals(line) || "runtime permissions:".equals(line)) {
            return PermissionSection.GRANTED;
        }
        if (line.endsWith(":") && !line.contains("permission")) {
            return PermissionSection.NONE;
        }
        return current;
    }

    /**
     * 收集权限名称；只保留权限标识，不返回 granted=false 的权限。
     *
     * @param detail 应用详情构建器
     * @param line 单行输出
     * @param section 当前权限区段
     */
    private void collectPermission(AndroidAppDetailBuilder detail, String line, PermissionSection section) {
        String permission = permissionName(line);
        if (!StringUtils.hasText(permission)) {
            return;
        }
        if (section == PermissionSection.REQUESTED) {
            detail.requestedPermissions.add(permission);
        } else if (section == PermissionSection.GRANTED && line.contains("granted=true")) {
            detail.grantedPermissions.add(permission);
        }
    }

    /**
     * 从权限行中提取权限名称。
     *
     * @param line 单行输出
     * @return 权限名称
     */
    private String permissionName(String line) {
        String value = line.contains(":") ? line.substring(0, line.indexOf(':')).trim() : line.trim();
        int spaceIndex = value.indexOf(' ');
        if (spaceIndex > 0) {
            value = value.substring(0, spaceIndex).trim();
        }
        return value.contains(".permission.") ? value : "";
    }

    /**
     * 读取布尔 key=value 字段，缺失时保留默认值。
     *
     * @param line 输出行
     * @param key 字段名
     * @param defaultValue 默认值
     * @return 解析后的布尔值
     */
    private boolean booleanAssignment(String line, String key, boolean defaultValue) {
        String value = assignmentValue(line, key);
        return StringUtils.hasText(value) ? Boolean.parseBoolean(value) : defaultValue;
    }

    /**
     * 解析 dumpsys package 中的版本名称。
     *
     * @param lines dumpsys package 输出行
     * @return 版本名称，缺失时为空字符串
     */
    public String parseAndroidPackageVersionName(List<String> lines) {
        return lines.stream()
                .map(String::trim)
                .filter(line -> line.startsWith("versionName="))
                .findFirst()
                .map(line -> line.substring("versionName=".length()).trim())
                .orElse("");
    }

    /**
     * 读取第一个空白分隔 token。
     *
     * @param value 原始文本
     * @return 第一个 token
     */
    private String firstToken(String value) {
        String[] parts = value.trim().split("\\s+");
        return parts.length == 0 ? "" : parts[0];
    }

    /**
     * 从 adb 输出的 metadata 中提取 key 后面的值。
     *
     * @param metadata metadata 文本
     * @param key 字段名
     * @return 字段值
     */
    private String metadataValue(String metadata, String key) {
        String prefix = key + ":";
        for (String part : metadata.split("\\s+")) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return "";
    }

    /**
     * 从单行 Android 包详情中提取可展示 label。
     *
     * @param line 包详情输出行
     * @return 应用名称，缺失时为空字符串
     */
    private String androidLabel(String line) {
        String label = applicationLabelValue(line);
        if (!StringUtils.hasText(label)) {
            label = assignmentValue(line, "nonLocalizedLabel");
        }
        if (!StringUtils.hasText(label)) {
            label = assignmentValue(line, "label");
        }
        return validLabel(label) ? label.trim() : "";
    }

    /**
     * 读取 aapt 风格的应用名称，兼容 application-label 和 application-label-xx 输出。
     *
     * @param line 输出行
     * @return 引号内文本
     */
    private String applicationLabelValue(String line) {
        int index = line.indexOf("application-label");
        if (index < 0) {
            return "";
        }
        int split = line.indexOf(':', index);
        int start = split >= 0 ? line.indexOf('\'', split + 1) : -1;
        int end = start >= 0 ? line.indexOf('\'', start + 1) : -1;
        return start >= 0 && end > start ? line.substring(start + 1, end).trim() : "";
    }

    /**
     * 读取 key=value 风格字段；遇到下一个字段时截断，保留带空格的名称。
     *
     * @param line 输出行
     * @param key 字段名
     * @return 字段值
     */
    private String assignmentValue(String line, String key) {
        String prefix = key + "=";
        int index = line.indexOf(prefix);
        if (index < 0) {
            return "";
        }
        int start = index + prefix.length();
        int end = nextAssignmentIndex(line, start);
        return trimDecorators(line.substring(start, end < 0 ? line.length() : end));
    }

    /**
     * 定位当前字段后的下一个 key=value 起点。
     *
     * @param line 输出行
     * @param start 搜索起点
     * @return 下一个字段起点，缺失时返回 -1
     */
    private int nextAssignmentIndex(String line, int start) {
        for (int i = start; i < line.length() - 1; i++) {
            if (line.charAt(i) == ' ' && assignmentStartsAt(line, i + 1)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 判断指定位置是否为 key=value 结构，避免误截断包含空格的 label。
     *
     * @param line 输出行
     * @param index 起始位置
     * @return 是字段起点时返回 true
     */
    private boolean assignmentStartsAt(String line, int index) {
        int equalsIndex = line.indexOf('=', index);
        int spaceIndex = line.indexOf(' ', index);
        return equalsIndex > index && (spaceIndex < 0 || equalsIndex < spaceIndex);
    }

    /**
     * 去除 dumpsys 字段常见包裹字符。
     *
     * @param value 原始字段值
     * @return 清理后的文本
     */
    private String trimDecorators(String value) {
        String cleaned = value.trim();
        if ((cleaned.startsWith("'") && cleaned.endsWith("'"))
                || (cleaned.startsWith("\"") && cleaned.endsWith("\""))) {
            return cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    /**
     * 判断 label 是否适合展示；过滤资源 ID 和空占位。
     *
     * @param label 候选名称
     * @return 可展示时返回 true
     */
    private boolean validLabel(String label) {
        if (!StringUtils.hasText(label)) {
            return false;
        }
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        return !"null".equals(normalized)
                && !"0".equals(normalized)
                && !normalized.startsWith("0x");
    }

    /**
     * 应用权限解析区段。
     */
    private enum PermissionSection {
        NONE,
        REQUESTED,
        GRANTED
    }

    /**
     * Android 应用详情构建器，集中承载 dumpsys 多行解析的临时状态。
     */
    private static class AndroidAppDetailBuilder {

        private String name;
        private String packageName;
        private String versionName = "";
        private String versionCode = "";
        private String uid = "";
        private String minSdk = "";
        private String targetSdk = "";
        private String firstInstallTime = "";
        private String lastUpdateTime = "";
        private String installerPackageName = "";
        private String codePath = "";
        private String resourcePath = "";
        private String dataDir = "";
        private boolean systemApp;
        private String enabledState = "";
        private boolean installed;
        private boolean hidden;
        private boolean stopped;
        private boolean suspended;
        private final Set<String> requestedPermissions = new LinkedHashSet<>();
        private final Set<String> grantedPermissions = new LinkedHashSet<>();

        /**
         * 创建详情构建器；包名同时作为应用名称的最终回退。
         *
         * @param packageName 请求包名
         */
        AndroidAppDetailBuilder(String packageName) {
            this.packageName = packageName;
            this.name = packageName;
        }

        /**
         * 转换为接口响应模型。
         *
         * @return 应用详情
         */
        AppDetail toDetail() {
            return new AppDetail(
                    name,
                    packageName,
                    versionName,
                    versionCode,
                    uid,
                    minSdk,
                    targetSdk,
                    firstInstallTime,
                    lastUpdateTime,
                    installerPackageName,
                    codePath,
                    resourcePath,
                    dataDir,
                    systemApp,
                    enabledState,
                    installed,
                    hidden,
                    stopped,
                    suspended,
                    List.copyOf(requestedPermissions),
                    List.copyOf(grantedPermissions));
        }
    }

    /**
     * 根据 APK 路径粗略识别是否为系统应用。
     *
     * @param apkPath APK 路径
     * @return 系统应用返回 true
     */
    private boolean isSystemApk(String apkPath) {
        String normalized = apkPath.toLowerCase(Locale.ROOT);
        return normalized.startsWith("/system/")
                || normalized.startsWith("/product/")
                || normalized.startsWith("/vendor/")
                || normalized.startsWith("/apex/")
                || normalized.startsWith("/odm/")
                || normalized.startsWith("/oem/");
    }

    /**
     * 把 CLI 状态转换成前端统一状态；未知状态保守视为离线。
     *
     * @param rawStatus CLI 原始状态
     * @return 统一设备状态
     */
    private DeviceStatus status(String rawStatus) {
        return switch (rawStatus.toLowerCase(Locale.ROOT)) {
            case "device" -> DeviceStatus.CONNECTED;
            case "unauthorized" -> DeviceStatus.UNAUTHORIZED;
            default -> DeviceStatus.OFFLINE;
        };
    }

    /**
     * 创建统一设备对象，ID 使用平台和序列号组合避免多平台串号冲突。
     *
     * @param platform 平台
     * @param serial 序列号
     * @param model 默认型号
     * @param status 设备状态
     * @return 设备对象
     */
    private DeviceInfo device(Platform platform, String serial, String model, DeviceStatus status) {
        return new DeviceInfo(platform.getValue() + ":" + serial, serial, model, platform, "", status);
    }
}
