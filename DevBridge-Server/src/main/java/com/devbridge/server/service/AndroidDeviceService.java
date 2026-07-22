package com.devbridge.server.service;

import com.devbridge.server.command.CommandResult;
import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.command.ProcessTreeTerminator;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.AppDetail;
import com.devbridge.server.model.BusinessException;
import com.devbridge.server.model.DeviceDetail;
import com.devbridge.server.model.DeviceStatus;
import com.devbridge.server.model.InstalledApp;
import com.devbridge.server.model.Platform;
import com.devbridge.server.model.RemoteFileNode;
import com.devbridge.server.model.RemoteFileType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Android 设备能力服务，封装详情、文件和日志导出命令。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AndroidDeviceService {

    private final ExecutableLocator executableLocator;
    private final CommandRunner commandRunner;
    private final DevBridgeProperties properties;
    private final AndroidPathGuard pathGuard;
    private final RemoteFileParser remoteFileParser;
    private final DeviceOutputParser deviceOutputParser = new DeviceOutputParser();
    private final ProcessTreeTerminator processTreeTerminator = new ProcessTreeTerminator();
    private static final String PACKAGE_NAME_PATTERN = "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+";

    /**
     * 注入 Android 命令依赖。
     *
     * @param executableLocator 工具定位器
     * @param commandRunner 短命令执行器
     * @param properties DevBridge 配置
     * @param pathGuard 远端路径校验器
     * @param remoteFileParser 文件列表解析器
     */
    public AndroidDeviceService(
            ExecutableLocator executableLocator,
            CommandRunner commandRunner,
            DevBridgeProperties properties,
            AndroidPathGuard pathGuard,
            RemoteFileParser remoteFileParser) {
        this.executableLocator = executableLocator;
        this.commandRunner = commandRunner;
        this.properties = properties;
        this.pathGuard = pathGuard;
        this.remoteFileParser = remoteFileParser;
    }

    /**
     * 获取 Android 设备详情；单个字段读取失败时返回空值，避免整页不可用。
     *
     * @param serial 设备序列号
     * @return 设备详情
     */
    public DeviceDetail getDetail(String serial) {
        ensureConnected(serial);
        String brand = firstText(getProp(serial, "ro.product.brand"), getProp(serial, "ro.product.manufacturer"), "Android");
        String model = firstText(getProp(serial, "ro.product.marketname"), getProp(serial, "ro.product.model"), "Android Device");
        return new DeviceDetail(
                Platform.ANDROID.getValue() + ":" + serial,
                serial,
                Platform.ANDROID,
                DeviceStatus.CONNECTED,
                brand,
                model,
                getProp(serial, "ro.build.version.release"),
                getProp(serial, "ro.build.version.sdk"),
                batteryLevel(serial),
                wmSize(serial),
                storageSummary(serial),
                gpuSummary(serial),
                wmDensity(serial),
                getProp(serial, "ro.build.fingerprint"),
                getProp(serial, "ro.build.version.security_patch"),
                optionalUnknown(getProp(serial, "ro.bootloader")),
                kernelVersion(serial),
                getProp(serial, "gsm.version.baseband"),
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
                cpuSummary(serial),
                ramSummary(serial),
                null);
    }

    /**
     * 查询 Android 已安装应用列表。
     *
     * @param serial 设备序列号
     * @return 已安装应用列表
     */
    public List<InstalledApp> listInstalledApps(String serial) {
        ensureConnected(serial);
        CommandResult packageResult = adb(serial, List.of("shell", "cmd", "package", "list", "packages", "-f", "-U", "--show-versioncode"));
        if (!packageResult.successful()) {
            throw commandError("APP_LIST_FAILED", "应用列表读取失败", HttpStatus.BAD_GATEWAY, packageResult);
        }
        List<InstalledApp> apps = deviceOutputParser.parseAndroidPackages(packageResult.stdout());
        CommandResult packageDetailResult = packageDetails(serial);
        Map<String, String> versionNames = packageDetailResult.successful()
                ? deviceOutputParser.parseAndroidPackageVersionNames(packageDetailResult.stdout())
                : Map.of();
        Map<String, String> labels = packageDetailResult.successful()
                ? deviceOutputParser.parseAndroidPackageLabels(packageDetailResult.stdout())
                : Map.of();
        return apps.stream()
                .map(app -> new InstalledApp(
                        labels.getOrDefault(app.packageName(), app.name()),
                        app.packageName(),
                        versionNames.getOrDefault(app.packageName(), ""),
                        app.versionCode(),
                        app.systemApp()))
                .toList();
    }

    /**
     * 批量读取应用详情；失败时由调用方保留基础应用列表。
     *
     * @param serial 设备序列号
     * @return dumpsys package 命令结果
     */
    private CommandResult packageDetails(String serial) {
        return adb(serial, List.of("shell", "dumpsys", "package", "packages"));
    }

    /**
     * 查询单个 Android 应用详情。
     *
     * @param serial 设备序列号
     * @param packageName 应用包名
     * @return 应用详情
     */
    public AppDetail getAppDetail(String serial, String packageName) {
        ensureConnected(serial);
        validatePackageName(packageName);
        CommandResult result = adb(serial, List.of("shell", "dumpsys", "package", packageName));
        if (!result.successful()) {
            throw commandError("APP_DETAIL_FAILED", "应用详情读取失败", HttpStatus.BAD_GATEWAY, result);
        }
        if (!containsPackageDetail(result.stdout())) {
            throw new BusinessException("APP_NOT_FOUND", "未找到指定应用", HttpStatus.NOT_FOUND, packageName);
        }
        return deviceOutputParser.parseAndroidAppDetail(result.stdout(), packageName);
    }

    /**
     * 安装受控本机 APK，并按期望包名验证安装后状态。
     *
     * @param serial 设备序列号
     * @param apkPath APK 本机临时路径
     * @param expectedPackageName 期望包名
     */
    public void installApp(String serial, Path apkPath, String expectedPackageName) {
        ensureConnected(serial);
        validatePackageName(expectedPackageName);
        Path safeApk = validateApkPath(apkPath);
        CommandResult result = adb(serial, List.of("install", "-r", safeApk.toString()));
        if (!result.successful() || hasFailureOutput(result) || !packageExists(serial, expectedPackageName, false)) {
            throw commandError("APP_INSTALL_FAILED", "应用安装失败或安装后校验未通过", HttpStatus.BAD_GATEWAY, result);
        }
    }

    /**
     * 启动应用主入口，并返回启动后是否检测到进程。
     *
     * @param serial 设备序列号
     * @param packageName 应用包名
     * @return 进程运行返回 true
     */
    public boolean launchApp(String serial, String packageName) {
        ensureConnected(serial);
        validatePackageName(packageName);
        ensurePackageExists(serial, packageName);
        CommandResult result = adb(serial, List.of(
                "shell", "monkey", "-p", packageName,
                "-c", "android.intent.category.LAUNCHER", "1"));
        if (!result.successful() || hasFailureOutput(result)) {
            throw commandError("APP_LAUNCH_FAILED", "应用启动失败", HttpStatus.BAD_GATEWAY, result);
        }
        return isAppRunning(serial, packageName);
    }

    /**
     * 强制停止应用，并验证进程已经退出。
     *
     * @param serial 设备序列号
     * @param packageName 应用包名
     * @return 进程已停止返回 true
     */
    public boolean stopApp(String serial, String packageName) {
        ensureConnected(serial);
        validatePackageName(packageName);
        ensurePackageExists(serial, packageName);
        CommandResult result = adb(serial, List.of("shell", "am", "force-stop", packageName));
        if (!result.successful()) {
            throw commandError("APP_STOP_FAILED", "应用停止失败", HttpStatus.BAD_GATEWAY, result);
        }
        return !isAppRunning(serial, packageName);
    }

    /**
     * 卸载 Android 应用；调用方必须已完成用户二次确认。
     *
     * @param serial 设备序列号
     * @param packageName 应用包名
     */
    public void uninstallApp(String serial, String packageName) {
        ensureConnected(serial);
        validatePackageName(packageName);
        ensureAppCanBeUninstalled(serial, packageName);
        CommandResult result = adb(serial, List.of("shell", "pm", "uninstall", packageName));
        if (!uninstallSucceeded(serial, packageName, result)) {
            throw commandError("APP_UNINSTALL_FAILED", "应用卸载失败", HttpStatus.BAD_GATEWAY, result);
        }
    }

    /**
     * 卸载前确认包真实存在且不是系统应用，避免不可卸载包被误报成网络或内部错误。
     *
     * @param serial 设备序列号
     * @param packageName 应用包名
     */
    private void ensureAppCanBeUninstalled(String serial, String packageName) {
        if (!packageExists(serial, packageName, false)) {
            throw new BusinessException("APP_NOT_FOUND", "未找到指定应用", HttpStatus.NOT_FOUND, packageName);
        }
        if (packageExists(serial, packageName, true)) {
            throw new BusinessException("APP_UNINSTALL_FORBIDDEN", "系统应用不支持直接卸载", HttpStatus.BAD_REQUEST, packageName);
        }
    }

    /**
     * 按精确包名检查应用列表；设备会返回模糊匹配结果，因此必须逐行确认完整包名。
     *
     * @param serial 设备序列号
     * @param packageName 应用包名
     * @param systemOnly 是否只检查系统包
     * @return 包名存在返回 true
     */
    private boolean packageExists(String serial, String packageName, boolean systemOnly) {
        List<String> command = systemOnly
                ? List.of("shell", "cmd", "package", "list", "packages", "-s", packageName)
                : List.of("shell", "cmd", "package", "list", "packages", packageName);
        CommandResult result = adb(serial, command);
        return result.successful() && result.stdout().stream()
                .map(String::trim)
                .anyMatch(line -> line.equals("package:" + packageName) || line.endsWith("=" + packageName));
    }

    /**
     * 判断 pm uninstall 是否成功；adb 输出可能延迟或为空，退出码成功后再用包是否消失兜底确认。
     *
     * @param serial 设备序列号
     * @param packageName 应用包名
     * @param result 卸载命令结果
     * @return 卸载成功时返回 true
     */
    private boolean uninstallSucceeded(String serial, String packageName, CommandResult result) {
        if (!result.successful() || hasFailureOutput(result)) {
            return false;
        }
        if (hasSuccessOutput(result)) {
            return true;
        }
        // 有些设备卸载已生效但未稳定输出 Success；二次查询包不存在时按成功处理。
        return !packageExists(serial, packageName, false);
    }

    /**
     * 判断命令输出是否包含明确成功标记。
     *
     * @param result 命令结果
     * @return 包含成功标记返回 true
     */
    private boolean hasSuccessOutput(CommandResult result) {
        return commandOutput(result).stream().map(String::trim).anyMatch("Success"::equalsIgnoreCase);
    }

    /**
     * 判断命令输出是否包含明确失败标记；部分设备即使退出码为 0 也会输出 Failure。
     *
     * @param result 命令结果
     * @return 包含失败标记返回 true
     */
    private boolean hasFailureOutput(CommandResult result) {
        return commandOutput(result).stream()
                .map(line -> line.trim().toLowerCase(Locale.ROOT))
                .anyMatch(line -> line.startsWith("failure") || line.startsWith("error") || line.contains("failed"));
    }

    /**
     * 合并标准输出和错误输出，避免设备把卸载结果写入 stderr 时丢失判定依据。
     *
     * @param result 命令结果
     * @return 合并后的输出行
     */
    private List<String> commandOutput(CommandResult result) {
        List<String> lines = new ArrayList<>(result.stdout());
        lines.addAll(result.stderr());
        return lines;
    }

    /**
     * 校验应用包名，避免空值或明显非法参数触发设备命令。
     *
     * @param packageName 应用包名
     */
    private void validatePackageName(String packageName) {
        if (!StringUtils.hasText(packageName) || !packageName.matches(PACKAGE_NAME_PATTERN)) {
            throw new BusinessException("INVALID_PACKAGE_NAME", "应用包名不合法", HttpStatus.BAD_REQUEST, packageName);
        }
    }

    /**
     * 校验 APK 必须是普通文件、扩展名正确且不超过统一下载上限。
     *
     * @param apkPath APK 路径
     * @return 规范绝对路径
     */
    private Path validateApkPath(Path apkPath) {
        if (apkPath == null) {
            throw new BusinessException("APK_INVALID", "APK 文件不能为空", HttpStatus.BAD_REQUEST, "");
        }
        Path normalized = apkPath.toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(normalized)
                    || !normalized.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".apk")
                    || Files.size(normalized) > properties.getMaxDownloadBytes()) {
                throw new BusinessException("APK_INVALID", "APK 文件无效或超过大小上限", HttpStatus.BAD_REQUEST, "");
            }
            return normalized;
        } catch (IOException ex) {
            throw new BusinessException("APK_INVALID", "APK 文件无法读取", HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * 校验文件搜索片段，禁止路径分隔符、通配符和控制字符改变 find 语义。
     *
     * @param query 搜索片段
     * @return 安全搜索片段
     */
    private String validateSearchQuery(String query) {
        String value = query == null ? "" : query.trim();
        if (value.isEmpty() || value.length() > 128
                || value.contains("/") || value.contains("\\")
                || value.contains("*") || value.contains("?")
                || value.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw new BusinessException("FILE_SEARCH_INVALID", "文件搜索条件不合法", HttpStatus.BAD_REQUEST, value);
        }
        return value;
    }

    /**
     * 校验文件传输使用的本机临时文件。
     *
     * @param localPath 本机路径
     * @return 规范绝对路径
     */
    private Path validateLocalTransferFile(Path localPath) {
        if (localPath == null) {
            throw new BusinessException("FILE_SOURCE_INVALID", "上传文件不能为空", HttpStatus.BAD_REQUEST, "");
        }
        Path normalized = localPath.toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(normalized) || Files.size(normalized) > properties.getMaxDownloadBytes()) {
                throw new BusinessException("FILE_SOURCE_INVALID", "上传文件无效或超过大小上限", HttpStatus.BAD_REQUEST, "");
            }
            return normalized;
        } catch (IOException ex) {
            throw new BusinessException("FILE_SOURCE_INVALID", "上传文件无法读取", HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * 校验包名在设备上精确存在。
     *
     * @param serial 设备序列号
     * @param packageName 应用包名
     */
    private void ensurePackageExists(String serial, String packageName) {
        if (!packageExists(serial, packageName, false)) {
            throw new BusinessException("APP_NOT_FOUND", "未找到指定应用", HttpStatus.NOT_FOUND, packageName);
        }
    }

    /**
     * 使用 pidof 检查应用进程，查询失败按未运行处理。
     *
     * @param serial 设备序列号
     * @param packageName 应用包名
     * @return 运行中返回 true
     */
    private boolean isAppRunning(String serial, String packageName) {
        CommandResult result = adb(serial, List.of("shell", "pidof", packageName));
        return result.successful() && result.stdout().stream().anyMatch(StringUtils::hasText);
    }

    /**
     * 判断 dumpsys 输出是否包含指定应用详情块。
     *
     * @param lines 命令输出
     * @return 包含应用详情时返回 true
     */
    private boolean containsPackageDetail(List<String> lines) {
        return lines.stream().map(String::trim).anyMatch(line -> line.startsWith("Package ["));
    }

    /**
     * 浏览 Android 设备可见目录；普通未 root 设备的受限目录会由 adb 返回权限错误。
     *
     * @param serial 设备序列号
     * @param path 远端路径
     * @return 文件节点列表
     */
    public List<RemoteFileNode> listFiles(String serial, String path) {
        String remotePath = pathGuard.validateRemotePath(path);
        ensureConnected(serial);
        CommandResult result = adb(serial, List.of("shell", "ls", "-la", directoryListPath(remotePath)));
        List<RemoteFileNode> nodes = remoteFileParser.parseAndroidLs(result.stdout(), remotePath);
        if (!result.successful() && nodes.isEmpty()) {
            throw commandError("REMOTE_PATH_NOT_FOUND", "远端目录不存在或不可访问", HttpStatus.NOT_FOUND, result);
        }
        // Android 根目录可能部分文件无权限，ls 会返回非 0；只要 stdout 中有可解析节点，就展示可访问部分。
        return nodes;
    }

    /**
     * 在受控根目录内按文件名搜索，结果数量严格有界。
     *
     * @param serial 设备序列号
     * @param rootPath 搜索根目录
     * @param query 文件名片段
     * @param maxResults 最大结果数
     * @return 匹配绝对路径
     */
    public List<String> searchFiles(String serial, String rootPath, String query, int maxResults) {
        String safeRoot = pathGuard.validateRemotePath(rootPath);
        String safeQuery = validateSearchQuery(query);
        int limit = Math.min(500, Math.max(1, maxResults));
        ensureConnected(serial);
        CommandResult result = adb(serial, List.of(
                "shell", "find", safeRoot, "-maxdepth", "5", "-iname", "*" + safeQuery + "*", "-print"));
        if (!result.successful() && result.stdout().isEmpty()) {
            throw commandError("FILE_SEARCH_FAILED", "文件搜索失败", HttpStatus.BAD_GATEWAY, result);
        }
        return result.stdout().stream()
                .map(String::trim)
                .filter(path -> path.startsWith("/"))
                .limit(limit)
                .toList();
    }

    /**
     * 有界预览远端文本文件，最多读取 64 KiB。
     *
     * @param serial 设备序列号
     * @param remotePath 远端文件路径
     * @return 文本预览
     */
    public String previewTextFile(String serial, String remotePath) {
        String safePath = pathGuard.validateMutableFilePath(remotePath);
        RemoteFileNode detail = getFileDetail(serial, safePath);
        if (detail.type() != RemoteFileType.FILE) {
            throw new BusinessException("FILE_OPERATION_UNSUPPORTED", "当前仅支持预览文本文件", HttpStatus.BAD_REQUEST, safePath);
        }
        CommandResult result = adb(serial, List.of("shell", "head", "-c", "65536", safePath));
        if (!result.successful()) {
            throw commandError("FILE_PREVIEW_FAILED", "文件预览失败", HttpStatus.BAD_GATEWAY, result);
        }
        return String.join("\n", result.stdout());
    }

    /**
     * 将受控本机文件上传到远端目录，默认拒绝覆盖已有文件。
     *
     * @param serial 设备序列号
     * @param localPath 本机临时文件
     * @param remoteDirectory 远端目录
     * @param targetName 目标文件名
     * @return 上传后的文件详情
     */
    public RemoteFileNode pushFile(
            String serial, Path localPath, String remoteDirectory, String targetName) {
        Path safeLocal = validateLocalTransferFile(localPath);
        String safeDirectory = pathGuard.validateRemotePath(remoteDirectory);
        String safeName = pathGuard.validateFileName(targetName);
        String targetPath = "/".equals(safeDirectory)
                ? "/" + safeName
                : safeDirectory + "/" + safeName;
        ensureConnected(serial);
        if (fileExists(serial, targetPath)) {
            throw new BusinessException("FILE_TARGET_EXISTS", "目标文件已存在", HttpStatus.CONFLICT, targetPath);
        }
        CommandResult result = adb(serial, List.of("push", safeLocal.toString(), targetPath));
        if (!result.successful()) {
            throw commandError("FILE_PUSH_FAILED", "文件上传失败", HttpStatus.BAD_GATEWAY, result);
        }
        return getFileDetail(serial, targetPath);
    }

    /**
     * 读取远端单个文件详情，供右键菜单和详情面板按需刷新最新元数据。
     *
     * @param serial 设备序列号
     * @param remotePath 远端文件路径
     * @return 文件节点详情
     */
    public RemoteFileNode getFileDetail(String serial, String remotePath) {
        String safePath = pathGuard.validateMutableFilePath(remotePath);
        ensureConnected(serial);
        CommandResult result = adb(serial, List.of("shell", "ls", "-ld", safePath));
        List<RemoteFileNode> nodes = remoteFileParser.parseAndroidLs(result.stdout(), parentPath(safePath));
        if (!result.successful() || nodes.isEmpty()) {
            throw commandError("REMOTE_PATH_NOT_FOUND", "远端文件不存在或不可访问", HttpStatus.NOT_FOUND, result);
        }
        return nodes.get(0);
    }

    /**
     * 删除远端单个文件；不递归删除目录，避免误操作扩大影响。
     *
     * @param serial 设备序列号
     * @param remotePath 远端文件路径
     */
    public void deleteFile(String serial, String remotePath) {
        String safePath = pathGuard.validateMutableFilePath(remotePath);
        RemoteFileNode detail = getFileDetail(serial, safePath);
        if (detail.type() != RemoteFileType.FILE) {
            throw new BusinessException("FILE_OPERATION_UNSUPPORTED", "当前仅支持删除文件，不支持删除目录", HttpStatus.BAD_REQUEST, safePath);
        }
        CommandResult result = adb(serial, List.of("shell", "rm", "-f", safePath));
        if (!result.successful()) {
            throw commandError("FILE_DELETE_FAILED", "文件删除失败", HttpStatus.BAD_GATEWAY, result);
        }
    }

    /**
     * 重命名远端单个文件；目标名称只允许是同目录内的新文件名。
     *
     * @param serial 设备序列号
     * @param remotePath 源远端文件路径
     * @param newName 新文件名
     * @return 重命名后的文件节点
     */
    public RemoteFileNode renameFile(String serial, String remotePath, String newName) {
        String safePath = pathGuard.validateMutableFilePath(remotePath);
        String targetPath = pathGuard.siblingPath(safePath, newName);
        RemoteFileNode detail = getFileDetail(serial, safePath);
        if (detail.type() != RemoteFileType.FILE) {
            throw new BusinessException("FILE_OPERATION_UNSUPPORTED", "当前仅支持重命名文件，不支持重命名目录", HttpStatus.BAD_REQUEST, safePath);
        }
        if (!safePath.equals(targetPath) && fileExists(serial, targetPath)) {
            throw new BusinessException("FILE_RENAME_TARGET_EXISTS", "目标文件已存在", HttpStatus.CONFLICT, targetPath);
        }
        CommandResult result = adb(serial, List.of("shell", "mv", safePath, targetPath));
        if (!result.successful()) {
            throw commandError("FILE_RENAME_FAILED", "文件重命名失败", HttpStatus.BAD_GATEWAY, result);
        }
        return getFileDetail(serial, targetPath);
    }

    /**
     * 在源文件当前目录创建副本；副本文件名由后端查重生成，避免覆盖已有远端文件。
     *
     * @param serial 设备序列号
     * @param remotePath 源远端文件路径
     * @return 新副本文件节点
     */
    public RemoteFileNode copyFile(String serial, String remotePath) {
        String safePath = pathGuard.validateMutableFilePath(remotePath);
        RemoteFileNode detail = getFileDetail(serial, safePath);
        if (detail.type() != RemoteFileType.FILE) {
            throw new BusinessException("FILE_OPERATION_UNSUPPORTED", "当前仅支持复制文件，不支持复制目录", HttpStatus.BAD_REQUEST, safePath);
        }
        String targetPath = nextCopyPath(serial, safePath);
        CommandResult result = adb(serial, List.of("shell", "cp", safePath, targetPath));
        if (!result.successful()) {
            throw commandError("FILE_COPY_FAILED", "文件副本创建失败", HttpStatus.BAD_GATEWAY, result);
        }
        return getFileDetail(serial, targetPath);
    }

    /**
     * 生成当前目录内可用的副本路径；首个副本不带序号，重名后从 1 开始递增。
     *
     * @param serial 设备序列号
     * @param remotePath 源远端文件路径
     * @return 可用副本路径
     */
    private String nextCopyPath(String serial, String remotePath) {
        String fileName = pathGuard.fileName(remotePath);
        String baseName = copyBaseName(fileName);
        String extension = copyExtension(fileName);
        for (int sequence = 0; sequence < 10000; sequence++) {
            String suffix = sequence == 0 ? "" : String.valueOf(sequence);
            String candidateName = baseName + "-副本" + suffix + extension;
            String candidatePath = pathGuard.siblingPath(remotePath, candidateName);
            if (!fileExists(serial, candidatePath)) {
                return candidatePath;
            }
        }
        throw new BusinessException("FILE_COPY_NAME_EXHAUSTED", "副本文件名已达到上限", HttpStatus.CONFLICT, remotePath);
    }

    /**
     * 提取副本基础名；只按最后一个扩展名分隔，避免 `archive.tar.gz` 丢失 tar 语义。
     *
     * @param fileName 文件名
     * @return 不含最后扩展名的基础名
     */
    private String copyBaseName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 && dotIndex < fileName.length() - 1 ? fileName.substring(0, dotIndex) : fileName;
    }

    /**
     * 提取副本扩展名；无扩展名或隐藏文件名时不追加后缀。
     *
     * @param fileName 文件名
     * @return 带点号的扩展名
     */
    private String copyExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 && dotIndex < fileName.length() - 1 ? fileName.substring(dotIndex) : "";
    }

    /**
     * 检查远端路径是否存在；只用于重命名前的覆盖保护。
     *
     * @param serial 设备序列号
     * @param remotePath 远端路径
     * @return 路径存在返回 true
     */
    private boolean fileExists(String serial, String remotePath) {
        CommandResult result = adb(serial, List.of("shell", "ls", "-ld", remotePath));
        return result.successful()
                && !remoteFileParser.parseAndroidLs(result.stdout(), parentPath(remotePath)).isEmpty();
    }

    /**
     * 提取远端路径父目录，供 `ls -ld` 解析时恢复完整节点路径。
     *
     * @param remotePath 远端路径
     * @return 父目录路径
     */
    private String parentPath(String remotePath) {
        int index = remotePath.lastIndexOf('/');
        return index <= 0 ? "/" : remotePath.substring(0, index);
    }

    /**
     * 生成目录列表参数；给非根目录追加尾斜杠，用于跟随 `/sdcard` 这类符号链接目录。
     *
     * @param remotePath 规范化后的远端路径
     * @return adb ls 使用的路径参数
     */
    private String directoryListPath(String remotePath) {
        if ("/".equals(remotePath) || remotePath.endsWith("/")) {
            return remotePath;
        }
        return remotePath + "/";
    }

    /**
     * 拉取 Android 单个文件到服务端临时目录。
     *
     * @param serial 设备序列号
     * @param remotePath 远端文件路径
     * @return 本机临时文件路径
     */
    public Path pullFile(String serial, String remotePath) {
        String safePath = pathGuard.validateRemotePath(remotePath);
        ensureConnected(serial);
        validateFileSize(serial, safePath);
        Path localFile = createDownloadFile(pathGuard.fileName(safePath));
        CommandResult result = adb(serial, List.of("pull", safePath, localFile.toString()));
        if (!result.successful()) {
            deleteQuietly(localFile);
            throw commandError("REMOTE_PATH_NOT_FOUND", "远端文件不存在或下载失败", HttpStatus.NOT_FOUND, result);
        }
        return localFile;
    }

    /**
     * 导出 Android logcat 快照到服务端临时文件。
     *
     * @param serial 设备序列号
     * @return 本机日志文件路径
     */
    public Path exportLogs(String serial) {
        ensureConnected(serial);
        CommandResult result = adb(serial, List.of("logcat", "-d", "-v", "threadtime"));
        if (!result.successful()) {
            throw commandError("COMMAND_TIMEOUT", "日志导出失败", HttpStatus.GATEWAY_TIMEOUT, result);
        }
        Path logFile = createDownloadFile("android-logcat-" + serial + ".log");
        try {
            Files.write(logFile, result.stdout());
            return logFile;
        } catch (IOException ex) {
            deleteQuietly(logFile);
            throw new BusinessException("FILE_WRITE_FAILED", "写入日志文件失败", HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    /**
     * 截取 Android 当前屏幕并写入本机临时 PNG 文件。
     *
     * @param serial 设备序列号
     * @return 本机截图文件路径
     */
    public Path captureScreenshot(String serial) {
        ensureConnected(serial);
        Path screenshotFile = createDownloadFile("android-screenshot-" + serial + ".png");
        // 使用 exec-out 直接获取 PNG 二进制流，避免 shell 重定向和设备端临时文件残留。
        CommandResult result = runBinaryToFile(
                adbCommand(serial, List.of("exec-out", "screencap", "-p")),
                screenshotFile,
                properties.getCommandTimeout());
        if (!result.successful() || emptyFile(screenshotFile)) {
            deleteQuietly(screenshotFile);
            throw commandError("SCREENSHOT_FAILED", "设备截图失败", HttpStatus.BAD_GATEWAY, result);
        }
        return screenshotFile;
    }

    /**
     * 获取 adb 可执行路径。
     *
     * @return adb 路径
     */
    public String adbExecutable() {
        String executable = executableLocator.locate(ToolCatalog.ADB);
        if (!StringUtils.hasText(executable)) {
            throw new BusinessException("TOOL_NOT_FOUND", "adb 工具不存在", HttpStatus.CONFLICT, "adb");
        }
        return executable;
    }

    /**
     * 检查 Android 设备是否处于 connected 状态。
     *
     * @param serial 设备序列号
     */
    private void ensureConnected(String serial) {
        CommandResult result = adbWithoutSerial(List.of("devices"));
        for (String line : result.stdout()) {
            if (line.trim().startsWith(serial + "\tdevice") || line.trim().startsWith(serial + " device")) {
                return;
            }
            if (line.trim().startsWith(serial) && line.contains("unauthorized")) {
                throw new BusinessException("DEVICE_UNAUTHORIZED", "设备未授权", HttpStatus.CONFLICT, serial);
            }
        }
        throw new BusinessException("DEVICE_NOT_CONNECTED", "设备未连接", HttpStatus.CONFLICT, serial);
    }

    /**
     * 执行带设备序列号的 adb 命令。
     *
     * @param serial 设备序列号
     * @param args adb 参数
     * @return 命令结果
     */
    private CommandResult adb(String serial, List<String> args) {
        return commandRunner.run(adbCommand(serial, args));
    }

    /**
     * 组装带设备序列号的 adb 参数数组，统一避免 shell 字符串拼接。
     *
     * @param serial 设备序列号
     * @param args adb 参数
     * @return 完整命令参数
     */
    private List<String> adbCommand(String serial, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(adbExecutable());
        command.add("-s");
        command.add(serial);
        command.addAll(args);
        return command;
    }

    /**
     * 执行不带设备序列号的 adb 命令。
     *
     * @param args adb 参数
     * @return 命令结果
     */
    private CommandResult adbWithoutSerial(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(adbExecutable());
        command.addAll(args);
        return commandRunner.run(command);
    }

    /**
     * 读取 Android getprop 字段。
     *
     * @param serial 设备序列号
     * @param property 属性名
     * @return 属性值
     */
    private String getProp(String serial, String property) {
        CommandResult result = adb(serial, List.of("shell", "getprop", property));
        return result.successful() && !result.stdout().isEmpty() ? result.stdout().get(0).trim() : "";
    }

    /**
     * 读取电量百分比。
     *
     * @param serial 设备序列号
     * @return 电量，读取失败时返回 null
     */
    private Integer batteryLevel(String serial) {
        CommandResult result = adb(serial, List.of("shell", "dumpsys", "battery"));
        return result.stdout().stream()
                .map(String::trim)
                .filter(line -> line.startsWith("level:"))
                .findFirst()
                .map(line -> parseInteger(line.substring("level:".length()).trim()))
                .orElse(null);
    }

    /**
     * 读取屏幕分辨率。
     *
     * @param serial 设备序列号
     * @return 分辨率摘要
     */
    private String wmSize(String serial) {
        CommandResult result = adb(serial, List.of("shell", "wm", "size"));
        return result.stdout().stream()
                .filter(line -> line.contains(":"))
                .findFirst()
                .map(line -> line.substring(line.indexOf(':') + 1).trim())
                .orElse("");
    }

    /**
     * 读取公共存储摘要。
     *
     * @param serial 设备序列号
     * @return 存储摘要
     */
    private String storageSummary(String serial) {
        CommandResult result = adb(serial, List.of("shell", "df", "-h", "/sdcard"));
        return result.stdout().size() >= 2 ? result.stdout().get(result.stdout().size() - 1).trim() : "";
    }

    /**
     * 汇总 Android CPU/SoC 信息；不同厂商暴露的属性不一致，因此按可信度顺序拼接非重复值。
     *
     * @param serial 设备序列号
     * @return CPU/SoC 摘要
     */
    private String cpuSummary(String serial) {
        return joinDistinct(
                getProp(serial, "ro.soc.model"),
                getProp(serial, "ro.board.platform"),
                getProp(serial, "ro.hardware"));
    }

    /**
     * 汇总 Android GPU 信息；优先读取 EGL 硬件名，失败时保留空值让前端显示占位。
     *
     * @param serial 设备序列号
     * @return GPU 摘要
     */
    private String gpuSummary(String serial) {
        return firstText(getProp(serial, "ro.hardware.egl"), getProp(serial, "ro.gpu.renderer"));
    }

    /**
     * 读取 Android 屏幕像素密度。
     *
     * @param serial 设备序列号
     * @return 像素密度摘要
     */
    private String wmDensity(String serial) {
        CommandResult result = adb(serial, List.of("shell", "wm", "density"));
        return parseDensity(result.stdout());
    }

    /**
     * 读取 Android 内核版本；只保留版本号前段，避免编译用户和路径导致页面过长。
     *
     * @param serial 设备序列号
     * @return 内核版本摘要
     */
    private String kernelVersion(String serial) {
        CommandResult result = adb(serial, List.of("shell", "cat", "/proc/version"));
        return parseKernelVersion(result.stdout());
    }

    /**
     * 读取 Android 物理内存摘要。
     *
     * @param serial 设备序列号
     * @return 内存摘要
     */
    private String ramSummary(String serial) {
        CommandResult result = adb(serial, List.of("shell", "cat", "/proc/meminfo"));
        return parseRamSummary(result.stdout());
    }

    /**
     * 解析 wm density 输出，统一转成前端可读的 dpi 文本。
     *
     * @param lines 命令输出
     * @return 像素密度摘要
     */
    static String parseDensity(List<String> lines) {
        return lines.stream()
                .map(String::trim)
                .filter(line -> line.contains("density"))
                .findFirst()
                .map(AndroidDeviceService::densityValue)
                .orElse("");
    }

    /**
     * 解析 /proc/version 第一行，截断构建账号等冗长部分。
     *
     * @param lines 命令输出
     * @return 内核版本摘要
     */
    static String parseKernelVersion(List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        String line = lines.get(0).trim();
        int buildUserIndex = line.indexOf(" (");
        return buildUserIndex > 0 ? line.substring(0, buildUserIndex).trim() : line;
    }

    /**
     * 解析 /proc/meminfo 的 MemTotal，并格式化为 GB。
     *
     * @param lines 命令输出
     * @return 内存摘要
     */
    static String parseRamSummary(List<String> lines) {
        return lines.stream()
                .map(String::trim)
                .filter(line -> line.startsWith("MemTotal:"))
                .findFirst()
                .map(AndroidDeviceService::ramValue)
                .orElse("");
    }

    /**
     * 从密度输出行提取数字值；设备可能返回 Physical 或 Override 两种格式。
     *
     * @param line 输出行
     * @return dpi 文本
     */
    private static String densityValue(String line) {
        int splitIndex = line.indexOf(':');
        String value = splitIndex >= 0 ? line.substring(splitIndex + 1).trim() : line.trim();
        return StringUtils.hasText(value) ? value + " dpi" : "";
    }

    /**
     * 从 MemTotal 行提取 kB 并转换为 GB，便于前端直接展示。
     *
     * @param line MemTotal 输出行
     * @return GB 文本
     */
    private static String ramValue(String line) {
        String digits = line.replaceAll("[^0-9]", "");
        if (!StringUtils.hasText(digits)) {
            return "";
        }
        double gb = Long.parseLong(digits) / 1024.0D / 1024.0D;
        return String.format(Locale.ROOT, "%.1f GB", gb);
    }

    /**
     * 校验远端文件大小，避免大文件占满本机磁盘。
     *
     * @param serial 设备序列号
     * @param remotePath 远端路径
     */
    private void validateFileSize(String serial, String remotePath) {
        CommandResult result = adb(serial, List.of("shell", "stat", "-c", "%s", remotePath));
        long size = result.successful() && !result.stdout().isEmpty() ? parseLong(result.stdout().get(0).trim()) : -1L;
        if (size > properties.getMaxDownloadBytes()) {
            throw new BusinessException("FILE_TOO_LARGE", "文件超过下载大小限制", HttpStatus.PAYLOAD_TOO_LARGE, String.valueOf(size));
        }
    }

    /**
     * 创建下载临时文件。
     *
     * @param fileName 文件名
     * @return 临时文件路径
     */
    private Path createDownloadFile(String fileName) {
        try {
            Path root = Path.of(properties.getDownloadTempRoot());
            Files.createDirectories(root);
            String safeName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            return Files.createTempFile(root, "devbridge-", "-" + safeName);
        } catch (IOException ex) {
            throw new BusinessException("FILE_WRITE_FAILED", "创建临时文件失败", HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    /**
     * 执行二进制输出命令并直接落盘，避免通过文本行读取破坏 PNG 内容。
     *
     * @param command 命令参数
     * @param outputFile 输出文件
     * @param timeout 超时时间
     * @return 命令执行结果
     */
    private CommandResult runBinaryToFile(List<String> command, Path outputFile, Duration timeout) {
        try {
            Process process = new ProcessBuilder(command).redirectOutput(outputFile.toFile()).start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                processTreeTerminator.terminate(process);
                return new CommandResult(124, List.of(), readErrorLines(process), true);
            }
            return new CommandResult(process.exitValue(), List.of(), readErrorLines(process), false);
        } catch (IOException ex) {
            return new CommandResult(127, List.of(), List.of(ex.getMessage()), false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new CommandResult(130, List.of(), List.of("command interrupted"), false);
        }
    }

    /**
     * 读取二进制命令的错误输出；仅用于错误摘要，不参与截图内容处理。
     *
     * @param process 进程对象
     * @return stderr 行列表
     */
    private List<String> readErrorLines(Process process) throws IOException {
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return StringUtils.hasText(stderr) ? List.of(stderr.split("\\R")) : List.of();
    }

    /**
     * 判断临时截图文件是否为空，防止命令成功但设备未返回有效图片。
     *
     * @param path 文件路径
     * @return 空文件返回 true
     */
    private boolean emptyFile(Path path) {
        try {
            return Files.notExists(path) || Files.size(path) == 0L;
        } catch (IOException ex) {
            return true;
        }
    }

    /**
     * 创建命令失败业务异常。
     *
     * @param code 错误码
     * @param message 错误信息
     * @param status HTTP 状态
     * @param result 命令结果
     * @return 业务异常
     */
    private BusinessException commandError(String code, String message, HttpStatus status, CommandResult result) {
        String detail = result.timedOut() ? "timeout" : result.firstOutputLine();
        return new BusinessException(result.timedOut() ? "COMMAND_TIMEOUT" : code, message, status, detail);
    }

    /**
     * 解析整数，失败时返回 null。
     *
     * @param value 字符串
     * @return 整数
     */
    private Integer parseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 解析长整数，失败时返回 -1。
     *
     * @param value 字符串
     * @return 长整数
     */
    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    /**
     * 返回第一个有内容的字符串，避免 Android 厂商字段缺失时把空值写入详情。
     *
     * @param values 候选值
     * @return 非空字符串
     */
    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    /**
     * 返回第一个有内容且非重复的属性摘要，用于兼容不同 Android 厂商字段。
     *
     * @param values 候选值
     * @return 拼接后的摘要
     */
    private String joinDistinct(String... values) {
        List<String> uniqueValues = new ArrayList<>();
        for (String value : values) {
            if (!StringUtils.hasText(value) || uniqueValues.contains(value)) {
                continue;
            }
            uniqueValues.add(value);
        }
        return String.join(" / ", uniqueValues);
    }

    /**
     * 过滤 Android 属性中常见的无效占位值，避免前端把 unknown 当成真实版本展示。
     *
     * @param value 原始属性值
     * @return 可展示值
     */
    private String optionalUnknown(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "unknown".equals(normalized) || "n/a".equals(normalized) ? "" : value.trim();
    }

    /**
     * 静默删除临时文件，用于失败清理。
     *
     * @param path 文件路径
     */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 下载失败时清理临时文件，清理失败不覆盖原始业务错误。
        }
    }
}
