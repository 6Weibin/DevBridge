package com.devbridge.server.ai.localshell.policy;

import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.BusinessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Local Shell 安全策略，负责工作目录、环境变量和超时边界。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class LocalShellPolicyService {

    private final DevBridgeProperties properties;

    /**
     * 注入配置。
     *
     * @param properties DevBridge 配置
     */
    public LocalShellPolicyService(DevBridgeProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析并校验工作目录；为空时使用当前 JVM 工作目录并要求在允许目录内。
     *
     * @param rawDirectory 原始工作目录
     * @return 规范化工作目录
     */
    public Path workingDirectory(String rawDirectory) {
        Path directory = StringUtils.hasText(rawDirectory)
                ? Path.of(rawDirectory)
                : Path.of(System.getProperty("user.dir"));
        Path normalized = normalize(directory);
        if (!Files.isDirectory(normalized)) {
            throw new BusinessException("LOCAL_SHELL_WORKDIR_DENIED", "本机命令工作目录不存在", HttpStatus.BAD_REQUEST, normalized.toString());
        }
        validateDeniedDirectory(normalized);
        validateAllowedDirectory(normalized);
        return normalized;
    }

    /**
     * 过滤模型传入的环境变量，只保留配置允许的 Key。
     *
     * @param rawEnvironment 原始环境变量
     * @return 受控环境变量
     */
    public Map<String, String> environment(Map<String, Object> rawEnvironment) {
        if (rawEnvironment == null || rawEnvironment.isEmpty()) {
            return Map.of();
        }
        List<String> allowed = properties.getAiMcpLocalShell().getAllowedEnvironmentKeys();
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : rawEnvironment.entrySet()) {
            if (allowed.contains(entry.getKey()) && entry.getValue() != null) {
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    /**
     * 计算受控超时时间，不能超过配置上限。
     *
     * @param timeoutMillis 请求超时毫秒
     * @return 超时时间
     */
    public Duration timeout(Object timeoutMillis) {
        Duration fallback = properties.getAiMcpLocalShell().getDefaultTimeout();
        Duration max = properties.getAiMcpLocalShell().getMaxTimeout();
        if (timeoutMillis == null) {
            return fallback.compareTo(max) > 0 ? max : fallback;
        }
        long millis = parseLong(timeoutMillis, fallback.toMillis());
        if (millis <= 0) {
            return fallback;
        }
        Duration requested = Duration.ofMillis(millis);
        return requested.compareTo(max) > 0 ? max : requested;
    }

    /**
     * 规范化路径，优先解析真实路径，路径不存在时退回绝对规范化路径。
     *
     * @param path 原始路径
     * @return 规范化路径
     */
    public Path normalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ex) {
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * 解析长整型配置值。
     *
     * @param value 原始值
     * @param fallback 默认值
     * @return 长整型值
     */
    private long parseLong(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * 校验目录没有命中拒绝目录。
     *
     * @param directory 工作目录
     */
    private void validateDeniedDirectory(Path directory) {
        for (String denied : deniedDirectories()) {
            Path deniedPath = normalize(Path.of(denied));
            boolean rootDenied = deniedPath.getParent() == null && directory.equals(deniedPath);
            boolean childDenied = deniedPath.getParent() != null && directory.startsWith(deniedPath);
            if (rootDenied || childDenied) {
                throw new BusinessException("LOCAL_SHELL_WORKDIR_DENIED", "本机命令工作目录命中系统拒绝目录", HttpStatus.CONFLICT, directory.toString());
            }
        }
    }

    /**
     * 校验目录位于允许目录内。
     *
     * @param directory 工作目录
     */
    private void validateAllowedDirectory(Path directory) {
        for (Path allowed : allowedDirectories()) {
            if (directory.startsWith(allowed)) {
                return;
            }
        }
        throw new BusinessException("LOCAL_SHELL_WORKDIR_DENIED", "本机命令工作目录不在允许范围内", HttpStatus.CONFLICT, directory.toString());
    }

    /**
     * 获取允许目录；未配置时默认允许用户主目录。
     *
     * @return 允许目录列表
     */
    private List<Path> allowedDirectories() {
        List<String> configured = properties.getAiMcpLocalShell().getAllowedWorkingDirectories();
        if (configured == null || configured.isEmpty()) {
            return List.of(defaultAllowedRoot());
        }
        return configured.stream().map(value -> normalize(Path.of(value))).toList();
    }

    /**
     * 获取默认允许根目录。
     *
     * @return 用户主目录
     */
    private Path defaultAllowedRoot() {
        return normalize(Path.of(System.getProperty("user.home")));
    }

    /**
     * 获取拒绝目录；未配置时使用系统关键目录。
     *
     * @return 拒绝目录列表
     */
    private List<String> deniedDirectories() {
        List<String> configured = properties.getAiMcpLocalShell().getDeniedWorkingDirectories();
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return List.of("C:\\Windows", "C:\\Program Files");
        }
        return List.of("/", "/System", "/private/etc", "/etc", "/bin", "/sbin", "/usr/bin");
    }
}
