package com.devbridge.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.command.CommandResult;
import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.AppDetail;
import com.devbridge.server.model.BusinessException;
import com.devbridge.server.model.InstalledApp;
import com.devbridge.server.model.RemoteFileType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Android 设备服务测试，覆盖真机 adb 输出中的关键详情解析。
 *
 * <p>by AI.Coding</p>
 */
class AndroidDeviceServiceTest {

    /**
     * 验证 wm density 输出能转成统一 dpi 文本。
     */
    @Test
    void parseDensityShouldReturnDpiText() {
        String density = AndroidDeviceService.parseDensity(List.of("Physical density: 480"));

        assertThat(density).isEqualTo("480 dpi");
    }

    /**
     * 验证内核版本只保留前段版本摘要，避免构建账号等冗长文本撑开页面。
     */
    @Test
    void parseKernelVersionShouldTrimBuildUserSuffix() {
        String kernel = AndroidDeviceService.parseKernelVersion(List.of(
                "Linux version 5.4.86-qgki-gc7d7ad5aea6b-dirty (builder@host) #1 SMP PREEMPT"));

        assertThat(kernel).isEqualTo("Linux version 5.4.86-qgki-gc7d7ad5aea6b-dirty");
    }

    /**
     * 验证 MemTotal 能按 GB 格式展示，便于设备信息页直接渲染。
     */
    @Test
    void parseRamSummaryShouldFormatGigabytes() {
        String ram = AndroidDeviceService.parseRamSummary(List.of(
                "MemTotal:        7541104 kB",
                "MemFree:          123456 kB"));

        assertThat(ram).isEqualTo("7.2 GB");
    }

    /**
     * 验证根目录部分文件无权限时，仍返回 stdout 中可访问的目录项。
     */
    @Test
    void listFilesShouldReturnPartialNodesWhenLsHasPermissionDenied() {
        FakeCommandRunner runner = new FakeCommandRunner(new CommandResult(
                1,
                List.of(
                        "total 148",
                        "drwxr-xr-x 1 root root 1384 2018-08-08 00:01 system",
                        "lrw-r--r-- 1 root root 11 2018-08-08 00:01 sdcard -> /storage/self/primary"),
                List.of("ls: //init.rc: Permission denied"),
                false));
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(),
                runner,
                new DevBridgeProperties(),
                new AndroidPathGuard(),
                new RemoteFileParser());

        var nodes = service.listFiles("SERIAL-1", "/");

        assertThat(nodes).extracting("name").containsExactly("sdcard", "system");
        assertThat(nodes.get(0).type()).isEqualTo(RemoteFileType.DIRECTORY);
    }

    /**
     * 验证文件详情读取会解析 `ls -ld` 输出并保留完整远端路径。
     */
    @Test
    void getFileDetailShouldReturnSingleFileNode() {
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(),
                new FileOperationCommandRunner(),
                new DevBridgeProperties(),
                new AndroidPathGuard(),
                new RemoteFileParser());

        var detail = service.getFileDetail("SERIAL-1", "/sdcard/demo.txt");

        assertThat(detail.name()).isEqualTo("demo.txt");
        assertThat(detail.path()).isEqualTo("/sdcard/demo.txt");
        assertThat(detail.type()).isEqualTo(RemoteFileType.FILE);
    }

    /**
     * 验证删除文件只执行单文件 `rm -f`，不走目录递归删除。
     */
    @Test
    void deleteFileShouldRunSingleFileRemoveCommand() {
        FileOperationCommandRunner runner = new FileOperationCommandRunner();
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(),
                runner,
                new DevBridgeProperties(),
                new AndroidPathGuard(),
                new RemoteFileParser());

        service.deleteFile("SERIAL-1", "/sdcard/demo.txt");

        assertThat(runner.commands).anySatisfy(command -> assertThat(command)
                .containsExactly("adb", "-s", "SERIAL-1", "shell", "rm", "-f", "/sdcard/demo.txt"));
    }

    /**
     * 验证重命名文件会先检查目标路径，再执行同目录 `mv`。
     */
    @Test
    void renameFileShouldRunMoveCommandAndReturnRenamedNode() {
        FileOperationCommandRunner runner = new FileOperationCommandRunner();
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(),
                runner,
                new DevBridgeProperties(),
                new AndroidPathGuard(),
                new RemoteFileParser());

        var detail = service.renameFile("SERIAL-1", "/sdcard/demo.txt", "renamed.txt");

        assertThat(detail.path()).isEqualTo("/sdcard/renamed.txt");
        assertThat(runner.commands).anySatisfy(command -> assertThat(command)
                .containsExactly("adb", "-s", "SERIAL-1", "shell", "mv", "/sdcard/demo.txt", "/sdcard/renamed.txt"));
    }

    /**
     * 验证重命名目标文件名不允许携带路径分隔符，防止借重命名移动到其它目录。
     */
    @Test
    void renameFileShouldRejectPathLikeNewName() {
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(),
                new FileOperationCommandRunner(),
                new DevBridgeProperties(),
                new AndroidPathGuard(),
                new RemoteFileParser());

        assertThatThrownBy(() -> service.renameFile("SERIAL-1", "/sdcard/demo.txt", "../evil.txt"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("REMOTE_PATH_FORBIDDEN");
    }

    /**
     * 验证创建首个副本时使用不带序号的命名规则。
     */
    @Test
    void copyFileShouldUseUnnumberedCopyNameWhenAvailable() {
        FileOperationCommandRunner runner = new FileOperationCommandRunner();
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(),
                runner,
                new DevBridgeProperties(),
                new AndroidPathGuard(),
                new RemoteFileParser());

        var detail = service.copyFile("SERIAL-1", "/sdcard/demo.txt");

        assertThat(detail.path()).isEqualTo("/sdcard/demo-副本.txt");
        assertThat(runner.commands).anySatisfy(command -> assertThat(command)
                .containsExactly("adb", "-s", "SERIAL-1", "shell", "cp", "/sdcard/demo.txt", "/sdcard/demo-副本.txt"));
    }

    /**
     * 验证已有首个副本时，后续副本从序号 1 开始递增。
     */
    @Test
    void copyFileShouldIncrementCopyNameWhenFirstCopyExists() {
        FileOperationCommandRunner runner = new FileOperationCommandRunner(true);
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(),
                runner,
                new DevBridgeProperties(),
                new AndroidPathGuard(),
                new RemoteFileParser());

        var detail = service.copyFile("SERIAL-1", "/sdcard/demo.txt");

        assertThat(detail.path()).isEqualTo("/sdcard/demo-副本1.txt");
        assertThat(runner.commands).anySatisfy(command -> assertThat(command)
                .containsExactly("adb", "-s", "SERIAL-1", "shell", "cp", "/sdcard/demo.txt", "/sdcard/demo-副本1.txt"));
    }

    /**
     * 验证文件搜索拒绝模型注入通配符或路径片段。
     */
    @Test
    void searchFilesShouldRejectUnsafeQuery() {
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(), new FileOperationCommandRunner(), new DevBridgeProperties(),
                new AndroidPathGuard(), new RemoteFileParser());

        assertThatThrownBy(() -> service.searchFiles("SERIAL-1", "/sdcard", "../*.db", 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("FILE_SEARCH_INVALID");
    }

    /**
     * 验证上传使用参数化 adb push，完成后读取目标详情做后置校验。
     */
    @Test
    void pushFileShouldVerifyUploadedTarget(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("demo.bin");
        Files.write(source, new byte[] {1, 2, 3});
        FileOperationCommandRunner runner = new FileOperationCommandRunner();
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(), runner, new DevBridgeProperties(),
                new AndroidPathGuard(), new RemoteFileParser());

        var detail = service.pushFile("SERIAL-1", source, "/sdcard", "demo.bin");

        assertThat(detail.path()).isEqualTo("/sdcard/demo.bin");
        assertThat(runner.commands).anySatisfy(command -> assertThat(command)
                .containsExactly("adb", "-s", "SERIAL-1", "push", source.toAbsolutePath().toString(), "/sdcard/demo.bin"));
    }

    /**
     * 验证应用列表会解析包名、版本名称和 dumpsys 中的应用名称。
     */
    @Test
    void listInstalledAppsShouldReturnVersionNamesAndLabels() {
        FakeCommandRunner runner = new FakeCommandRunner(new CommandResult(
                0,
                List.of("package:/data/app/base.apk=com.example.demo uid:10234 versionCode:42"),
                List.of(),
                false));
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(),
                runner,
                new DevBridgeProperties(),
                new AndroidPathGuard(),
                new RemoteFileParser());

        var apps = service.listInstalledApps("SERIAL-1");

        assertThat(apps).containsExactly(new InstalledApp(
                "Demo App",
                "com.example.demo",
                "1.2.3",
                "42",
                false));
    }

    /**
     * 验证应用详情会按指定包名读取单包 dumpsys 输出。
     */
    @Test
    void getAppDetailShouldReturnPackageDetail() {
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(),
                new FakeCommandRunner(new CommandResult(0, List.of(), List.of(), false)),
                new DevBridgeProperties(),
                new AndroidPathGuard(),
                new RemoteFileParser());

        AppDetail detail = service.getAppDetail("SERIAL-1", "com.example.demo");

        assertThat(detail.packageName()).isEqualTo("com.example.demo");
        assertThat(detail.name()).isEqualTo("Demo App");
        assertThat(detail.versionName()).isEqualTo("1.2.3");
        assertThat(detail.grantedPermissions()).containsExactly("android.permission.INTERNET");
    }

    /**
     * 验证卸载应用会执行带包名的 pm uninstall 命令。
     */
    @Test
    void uninstallAppShouldRunPmUninstall() {
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(),
                new FakeCommandRunner(new CommandResult(0, List.of(), List.of(), false)),
                new DevBridgeProperties(),
                new AndroidPathGuard(),
                new RemoteFileParser());

        service.uninstallApp("SERIAL-1", "com.example.demo");
    }

    /**
     * 验证卸载命令没有输出 Success 但包已消失时，仍按真实成功处理。
     */
    @Test
    void uninstallAppShouldTreatSilentRemovalAsSuccess() {
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(),
                new SilentUninstallCommandRunner(),
                new DevBridgeProperties(),
                new AndroidPathGuard(),
                new RemoteFileParser());

        service.uninstallApp("SERIAL-1", "com.example.silent");
    }

    /**
     * 验证退出码为 0 但输出 Failure 时，不能误判为卸载成功。
     */
    @Test
    void uninstallAppShouldRejectFailureOutputEvenWhenExitCodeIsZero() {
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(),
                new FailureUninstallCommandRunner(),
                new DevBridgeProperties(),
                new AndroidPathGuard(),
                new RemoteFileParser());

        assertThatThrownBy(() -> service.uninstallApp("SERIAL-1", "com.example.failed"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("APP_UNINSTALL_FAILED");
    }

    /**
     * 验证不存在的包在卸载前被识别，避免设备返回内部错误导致前端误判。
     */
    @Test
    void uninstallAppShouldRejectMissingPackageBeforePmUninstall() {
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(),
                new FakeCommandRunner(new CommandResult(0, List.of(), List.of(), false)),
                new DevBridgeProperties(),
                new AndroidPathGuard(),
                new RemoteFileParser());

        assertThatThrownBy(() -> service.uninstallApp("SERIAL-1", "com.missing.demo"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("APP_NOT_FOUND");
    }

    /**
     * 验证系统包不会进入卸载命令，普通 adb 权限下系统应用不能直接删除。
     */
    @Test
    void uninstallAppShouldRejectSystemPackage() {
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(),
                new FakeCommandRunner(new CommandResult(0, List.of(), List.of(), false)),
                new DevBridgeProperties(),
                new AndroidPathGuard(),
                new RemoteFileParser());

        assertThatThrownBy(() -> service.uninstallApp("SERIAL-1", "com.android.settings"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("APP_UNINSTALL_FORBIDDEN");
    }

    /**
     * 验证 APK 安装使用参数数组并在成功后精确检查期望包名。
     */
    @Test
    void installAppShouldVerifyExpectedPackage(@TempDir Path tempDir) throws Exception {
        Path apk = tempDir.resolve("demo.apk");
        Files.write(apk, new byte[] {1, 2, 3});
        AppOperationCommandRunner runner = new AppOperationCommandRunner();
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(), runner, new DevBridgeProperties(),
                new AndroidPathGuard(), new RemoteFileParser());

        service.installApp("SERIAL-1", apk, "com.example.demo");

        assertThat(runner.commands).anySatisfy(command -> assertThat(command)
                .containsExactly("adb", "-s", "SERIAL-1", "install", "-r", apk.toAbsolutePath().toString()));
    }

    /**
     * 验证应用启动和停止都执行后置进程检查。
     */
    @Test
    void launchAndStopAppShouldVerifyProcessState() {
        AppOperationCommandRunner runner = new AppOperationCommandRunner();
        AndroidDeviceService service = new AndroidDeviceService(
                new FakeExecutableLocator(), runner, new DevBridgeProperties(),
                new AndroidPathGuard(), new RemoteFileParser());

        boolean running = service.launchApp("SERIAL-1", "com.example.demo");
        boolean stopped = service.stopApp("SERIAL-1", "com.example.demo");

        assertThat(running).isTrue();
        assertThat(stopped).isTrue();
        assertThat(runner.commands).anySatisfy(command -> assertThat(command).contains("monkey"));
        assertThat(runner.commands).anySatisfy(command -> assertThat(command).contains("force-stop"));
    }

    /**
     * 测试用工具定位器，固定返回 adb 命令名，避免依赖本机文件。
     */
    private static class FakeExecutableLocator extends ExecutableLocator {

        /**
         * 构造测试定位器。
         */
        FakeExecutableLocator() {
            super(new DevBridgeProperties());
        }

        /**
         * 返回工具名本身，方便测试命令参数。
         *
         * @param definition 工具定义
         * @return 命令名
         */
        @Override
        public String locate(ToolDefinition definition) {
            return definition.commands().get(0);
        }
    }

    /**
     * 测试用命令执行器，区分设备检测和目录列表命令。
     */
    private static class FakeCommandRunner extends CommandRunner {

        private final CommandResult listResult;

        /**
         * 构造测试命令执行器。
         *
         * @param listResult 目录列表结果
         */
        FakeCommandRunner(CommandResult listResult) {
            super(new DevBridgeProperties(), Runnable::run);
            this.listResult = listResult;
        }

        /**
         * 模拟 adb devices 和 adb shell ls 输出。
         *
         * @param command 命令参数
         * @return 命令结果
         */
        @Override
        public CommandResult run(List<String> command) {
            boolean systemPackageQuery = command.indexOf("-s") != command.lastIndexOf("-s");
            if (command.contains("devices")) {
                return new CommandResult(0, List.of("SERIAL-1\tdevice"), List.of(), false);
            }
            if (command.contains("list")
                    && command.contains("package")
                    && command.contains("com.example.demo")
                    && !systemPackageQuery) {
                return new CommandResult(0, List.of("package:/data/app/base.apk=com.example.demo"), List.of(), false);
            }
            if (command.contains("list") && command.contains("package") && command.contains("com.android.settings")) {
                return new CommandResult(0, List.of("package:/system/app/Settings.apk=com.android.settings"), List.of(), false);
            }
            if (command.contains("uninstall") && command.contains("com.example.demo")) {
                return new CommandResult(0, List.of("Success"), List.of(), false);
            }
            if (command.contains("dumpsys") && command.contains("package")) {
                return new CommandResult(0, List.of(
                        "Package [com.example.demo] (abc):",
                        "  userId=10234",
                        "  codePath=/data/app/com.example.demo",
                        "  versionCode=42 minSdk=23 targetSdk=35",
                        "  nonLocalizedLabel=Demo App icon=0x7f010001",
                        "  versionName=1.2.3",
                        "  install permissions:",
                        "    android.permission.INTERNET: granted=true",
                        "  User 0: ceDataInode=1 installed=true hidden=false suspended=false stopped=false enabled=0"), List.of(), false);
            }
            return listResult;
        }
    }

    /**
     * 模拟设备卸载成功但未输出 Success，后续包列表查询已查不到该包。
     */
    private static class SilentUninstallCommandRunner extends CommandRunner {

        private boolean removed;

        /**
         * 构造静默卸载命令执行器。
         */
        SilentUninstallCommandRunner() {
            super(new DevBridgeProperties(), Runnable::run);
        }

        /**
         * 模拟 adb devices、卸载前后包列表和无输出卸载结果。
         *
         * @param command 命令参数
         * @return 命令结果
         */
        @Override
        public CommandResult run(List<String> command) {
            boolean systemPackageQuery = command.indexOf("-s") != command.lastIndexOf("-s");
            if (command.contains("devices")) {
                return new CommandResult(0, List.of("SERIAL-1\tdevice"), List.of(), false);
            }
            if (command.contains("uninstall") && command.contains("com.example.silent")) {
                removed = true;
                return new CommandResult(0, List.of(), List.of(), false);
            }
            if (command.contains("list")
                    && command.contains("package")
                    && command.contains("com.example.silent")
                    && !systemPackageQuery) {
                return removed
                        ? new CommandResult(0, List.of(), List.of(), false)
                        : new CommandResult(0, List.of("package:/data/app/base.apk=com.example.silent"), List.of(), false);
            }
            return new CommandResult(0, List.of(), List.of(), false);
        }
    }

    /**
     * 模拟设备返回退出码 0 但输出 Failure 的异常卸载结果。
     */
    private static class FailureUninstallCommandRunner extends CommandRunner {

        /**
         * 构造失败卸载命令执行器。
         */
        FailureUninstallCommandRunner() {
            super(new DevBridgeProperties(), Runnable::run);
        }

        /**
         * 模拟 adb devices、包存在和带 Failure 的卸载输出。
         *
         * @param command 命令参数
         * @return 命令结果
         */
        @Override
        public CommandResult run(List<String> command) {
            boolean systemPackageQuery = command.indexOf("-s") != command.lastIndexOf("-s");
            if (command.contains("devices")) {
                return new CommandResult(0, List.of("SERIAL-1\tdevice"), List.of(), false);
            }
            if (command.contains("uninstall") && command.contains("com.example.failed")) {
                return new CommandResult(0, List.of("Failure [DELETE_FAILED_INTERNAL_ERROR]"), List.of(), false);
            }
            if (command.contains("list")
                    && command.contains("package")
                    && command.contains("com.example.failed")
                    && !systemPackageQuery) {
                return new CommandResult(0, List.of("package:/data/app/base.apk=com.example.failed"), List.of(), false);
            }
            return new CommandResult(0, List.of(), List.of(), false);
        }
    }

    /**
     * 模拟应用安装、启动、停止和后置状态查询。
     */
    private static class AppOperationCommandRunner extends CommandRunner {

        private final List<List<String>> commands = new java.util.ArrayList<>();
        private boolean stopped;

        /**
         * 创建应用操作执行器。
         */
        AppOperationCommandRunner() {
            super(new DevBridgeProperties(), Runnable::run);
        }

        /**
         * 返回应用操作所需的确定性命令结果。
         *
         * @param command 命令参数
         * @return 命令结果
         */
        @Override
        public CommandResult run(List<String> command) {
            commands.add(command);
            if (command.contains("devices")) {
                return new CommandResult(0, List.of("SERIAL-1\tdevice"), List.of(), false);
            }
            if (command.contains("list") && command.contains("package") && command.contains("com.example.demo")) {
                return new CommandResult(0, List.of("package:com.example.demo"), List.of(), false);
            }
            if (command.contains("install")) {
                return new CommandResult(0, List.of("Success"), List.of(), false);
            }
            if (command.contains("monkey")) {
                stopped = false;
                return new CommandResult(0, List.of("Events injected: 1"), List.of(), false);
            }
            if (command.contains("force-stop")) {
                stopped = true;
                return new CommandResult(0, List.of(), List.of(), false);
            }
            if (command.contains("pidof")) {
                return stopped
                        ? new CommandResult(1, List.of(), List.of(), false)
                        : new CommandResult(0, List.of("1234"), List.of(), false);
            }
            return new CommandResult(0, List.of(), List.of(), false);
        }
    }

    /**
     * 模拟 Android 文件详情、删除和重命名命令，记录实际命令参数用于断言安全调用方式。
     */
    private static class FileOperationCommandRunner extends CommandRunner {

        private final List<List<String>> commands = new java.util.ArrayList<>();
        private final boolean firstCopyExists;
        private boolean renamed;
        private String copiedPath = "";
        private String uploadedPath = "";

        /**
         * 构造文件操作命令执行器。
         */
        FileOperationCommandRunner() {
            this(false);
        }

        /**
         * 构造文件操作命令执行器，可模拟首个副本已存在的场景。
         *
         * @param firstCopyExists 首个副本是否已存在
         */
        FileOperationCommandRunner(boolean firstCopyExists) {
            super(new DevBridgeProperties(), Runnable::run);
            this.firstCopyExists = firstCopyExists;
        }

        /**
         * 模拟 adb devices、ls -ld、rm、mv 和 cp 的最小行为。
         *
         * @param command 命令参数
         * @return 命令结果
         */
        @Override
        public CommandResult run(List<String> command) {
            commands.add(command);
            if (command.contains("devices")) {
                return new CommandResult(0, List.of("SERIAL-1\tdevice"), List.of(), false);
            }
            if (command.contains("rm")) {
                return new CommandResult(0, List.of(), List.of(), false);
            }
            if (command.contains("mv")) {
                renamed = true;
                return new CommandResult(0, List.of(), List.of(), false);
            }
            if (command.contains("cp")) {
                copiedPath = command.get(command.size() - 1);
                return new CommandResult(0, List.of(), List.of(), false);
            }
            if (command.contains("push")) {
                uploadedPath = command.get(command.size() - 1);
                return new CommandResult(0, List.of("1 file pushed"), List.of(), false);
            }
            if (command.contains("ls") && command.contains("-ld")) {
                return fileDetail(command);
            }
            return new CommandResult(0, List.of(), List.of(), false);
        }

        /**
         * 根据查询路径返回源文件、目标不存在或重命名后的目标文件。
         *
         * @param command 命令参数
         * @return 文件详情命令结果
         */
        private CommandResult fileDetail(List<String> command) {
            String path = command.get(command.size() - 1);
            if ("/sdcard/demo.txt".equals(path)) {
                return lsDetail("/sdcard/demo.txt");
            }
            if ("/sdcard/demo-副本.txt".equals(path) && (firstCopyExists || path.equals(copiedPath))) {
                return lsDetail("/sdcard/demo-副本.txt");
            }
            if ("/sdcard/demo-副本1.txt".equals(path) && path.equals(copiedPath)) {
                return lsDetail("/sdcard/demo-副本1.txt");
            }
            if ("/sdcard/renamed.txt".equals(path) && renamed) {
                return lsDetail("/sdcard/renamed.txt");
            }
            if (path.equals(uploadedPath)) {
                return lsDetail(uploadedPath);
            }
            return new CommandResult(1, List.of(), List.of("No such file or directory"), false);
        }

        /**
         * 生成 Android `ls -ld` 文件输出样本。
         *
         * @param path 文件路径
         * @return 命令结果
         */
        private CommandResult lsDetail(String path) {
            return new CommandResult(
                    0,
                    List.of("-rw-r--r-- 1 shell shell 12 2026-07-03 10:00 " + path),
                    List.of(),
                    false);
        }
    }
}
