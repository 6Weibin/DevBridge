package com.devbridge.server.service;

import com.devbridge.server.config.DevBridgeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 外部工具路径定位器，优先使用项目内置工具，再搜索配置路径、PATH 和常见安装目录。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class ExecutableLocator {

    private final DevBridgeProperties properties;

    /**
     * 注入工具路径配置。
     *
     * @param properties DevBridge 配置
     */
    public ExecutableLocator(DevBridgeProperties properties) {
        this.properties = properties;
    }

    /**
     * 定位指定工具的第一个可执行路径。
     *
     * @param definition 工具定义
     * @return 找到时返回绝对路径，否则返回空字符串
     */
    public String locate(ToolDefinition definition) {
        for (String candidate : bundledCandidates(definition.commands())) {
            if (isExecutable(candidate)) {
                return Path.of(candidate).toAbsolutePath().toString();
            }
        }
        String configured = properties.getTools().get(definition.name());
        if (isExecutable(configured)) {
            return Path.of(configured).toAbsolutePath().toString();
        }
        for (String candidate : candidatePaths(definition.commands())) {
            if (isExecutable(candidate)) {
                return Path.of(candidate).toAbsolutePath().toString();
            }
        }
        return "";
    }

    /**
     * 返回当前运行平台对应的内置工具目录名，供诊断接口和前端展示复用。
     *
     * @return 目录架构名
     */
    public String currentToolDirectoryName() {
        return osArchitecture();
    }

    /**
     * 生成项目内置工具候选路径；adb 官方包会解压到 platform-tools 子目录。
     *
     * @param commands 候选命令名
     * @return 内置工具候选路径
     */
    private List<String> bundledCandidates(List<String> commands) {
        List<String> candidates = new ArrayList<>();
        for (String root : bundledRoots()) {
            for (String command : commands) {
                candidates.add(Path.of(root, command).toString());
                candidates.add(Path.of(root, "platform-tools", command).toString());
                if (isWindows()) {
                    candidates.add(Path.of(root, command + ".exe").toString());
                    candidates.add(Path.of(root, "platform-tools", command + ".exe").toString());
                }
            }
        }
        return candidates;
    }

    /**
     * 生成当前系统架构下的内置工具目录，兼容从工程目录和仓库根目录启动两种方式。
     *
     * @return 内置工具根目录列表
     */
    private List<String> bundledRoots() {
        String osArch = osArchitecture();
        String root = properties.getBundledToolRoot();
        return List.of(
                Path.of(root, osArch).toString(),
                Path.of("DevBridge-Server", root, osArch).toString());
    }

    /**
     * 生成候选路径；Windows 下补充 PATHEXT，macOS/Linux 下搜索 PATH 即可。
     *
     * @param commands 候选命令名
     * @return 候选路径列表
     */
    private List<String> candidatePaths(List<String> commands) {
        List<String> candidates = new ArrayList<>();
        List<String> directories = pathDirectories();
        for (String command : commands) {
            for (String directory : directories) {
                candidates.add(Path.of(directory, command).toString());
                if (isWindows()) {
                    candidates.add(Path.of(directory, command + ".exe").toString());
                }
            }
        }
        return candidates;
    }

    /**
     * 汇总 PATH 和常见安装目录，避免只依赖 shell 的 command -v。
     *
     * @return 搜索目录列表
     */
    private List<String> pathDirectories() {
        List<String> directories = new ArrayList<>();
        String path = System.getenv("PATH");
        if (StringUtils.hasText(path)) {
            directories.addAll(List.of(path.split(java.io.File.pathSeparator)));
        }
        directories.addAll(List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "C:\\Program Files\\libimobiledevice"));
        return directories;
    }

    /**
     * 计算工具目录使用的系统架构名称。
     *
     * @return 目录架构名
     */
    private String osArchitecture() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String os = osName.contains("mac") ? "darwin" : osName.contains("win") ? "windows" : "linux";
        String cpu = arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64";
        return os + "-" + cpu;
    }

    /**
     * 判断路径是否可执行。
     *
     * @param path 待检查路径
     * @return 可执行返回 true
     */
    private boolean isExecutable(String path) {
        return StringUtils.hasText(path) && Files.isRegularFile(Path.of(path)) && Files.isExecutable(Path.of(path));
    }

    /**
     * 判断当前系统是否 Windows，用于补充 exe 后缀。
     *
     * @return Windows 返回 true
     */
    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
