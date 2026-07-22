package com.devbridge.server.ai.config;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * AI 配置系统密钥环，统一适配 macOS、Windows 和 Linux 的原生凭据存储。
 *
 * <p>该组件只传输随机 AES 密钥，不处理 Provider API Key，也不会记录命令、输入或输出。</p>
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AiSystemKeyring {

    private static final String SERVICE = "Ai DevBridge AI Config";
    private static final int MAX_OUTPUT_BYTES = 4096;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(8);
    private static final String WINDOWS_READ_SCRIPT = """
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            $null = [Windows.Security.Credentials.PasswordVault,Windows.Security.Credentials,ContentType=WindowsRuntime]
            $vault = New-Object Windows.Security.Credentials.PasswordVault
            try {
              $credential = $vault.Retrieve($args[0], $args[1])
              $credential.RetrievePassword()
              [Console]::Out.Write($credential.Password)
            } catch { exit 44 }
            """;
    private static final String WINDOWS_WRITE_SCRIPT = """
            $secret = [Console]::In.ReadToEnd().TrimEnd("`r", "`n")
            $null = [Windows.Security.Credentials.PasswordVault,Windows.Security.Credentials,ContentType=WindowsRuntime]
            $null = [Windows.Security.Credentials.PasswordCredential,Windows.Security.Credentials,ContentType=WindowsRuntime]
            $vault = New-Object Windows.Security.Credentials.PasswordVault
            try {
              $existing = $vault.Retrieve($args[0], $args[1])
              $vault.Remove($existing)
            } catch { }
            try {
              $credential = New-Object Windows.Security.Credentials.PasswordCredential($args[0], $args[1], $secret)
              $vault.Add($credential)
            } catch { exit 45 }
            """;

    private final Platform platform;
    private final CommandExecutor commandExecutor;

    /**
     * 根据当前操作系统选择密钥环后端。
     */
    public AiSystemKeyring() {
        this(Platform.current(), new ProcessCommandExecutor());
    }

    /**
     * 创建可测试的指定平台密钥环。
     *
     * @param platform 平台
     * @param commandExecutor 命令执行器
     */
    AiSystemKeyring(Platform platform, CommandExecutor commandExecutor) {
        this.platform = platform;
        this.commandExecutor = commandExecutor;
    }

    /**
     * 创建禁用系统密钥环的实例，供兼容测试或受限运行环境使用。
     *
     * @return 禁用实例
     */
    static AiSystemKeyring disabled() {
        return new AiSystemKeyring(Platform.UNSUPPORTED, (command, input) -> CommandResult.unavailable());
    }

    /**
     * 从系统密钥环读取指定 AES 密钥。
     *
     * @param keyId 根目录派生的稳定标识
     * @return 读取结果
     */
    KeyringRead read(String keyId) {
        if (platform == Platform.UNSUPPORTED) {
            return KeyringRead.unavailable();
        }
        CommandResult result = commandExecutor.execute(readCommand(keyId), "");
        if (!result.available()) {
            return KeyringRead.unavailable();
        }
        if (result.exitCode() == 0 && !result.output().isBlank()) {
            return KeyringRead.found(result.output().trim());
        }
        return missingExitCode(result.exitCode()) ? KeyringRead.missing() : KeyringRead.unavailable();
    }

    /**
     * 将 AES 密钥写入系统密钥环；失败时由上层决定是否使用安全文件回退。
     *
     * @param keyId 根目录派生的稳定标识
     * @param secret Base64 AES 密钥
     * @return 写入成功返回 true
     */
    boolean store(String keyId, String secret) {
        if (platform == Platform.UNSUPPORTED) {
            return false;
        }
        if (platform == Platform.MACOS) {
            // macOS security CLI 的 -w 不读取标准输入，返回成功也可能只保存空密码。
            // 为避免密钥丢失，macOS 统一使用上层提供的 0600 权限回退文件。
            return false;
        }
        CommandResult result = commandExecutor.execute(writeCommand(keyId), secret + System.lineSeparator());
        return result.available() && result.exitCode() == 0;
    }

    /**
     * 构造平台读取命令，所有参数通过进程参数数组传递。
     *
     * @param keyId 密钥标识
     * @return 命令参数
     */
    private List<String> readCommand(String keyId) {
        return switch (platform) {
            case MACOS -> List.of(
                    "/usr/bin/security", "find-generic-password", "-a", keyId, "-s", SERVICE, "-w");
            case WINDOWS -> List.of(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    WINDOWS_READ_SCRIPT, SERVICE, keyId);
            case LINUX -> List.of(
                    "secret-tool", "lookup", "service", SERVICE, "account", keyId);
            case UNSUPPORTED -> List.of();
        };
    }

    /**
     * 构造平台写入命令；密钥通过标准输入传递，避免出现在命令行参数中。
     *
     * @param keyId 密钥标识
     * @return 命令参数
     */
    private List<String> writeCommand(String keyId) {
        return switch (platform) {
            case MACOS -> List.of(
                    "/usr/bin/security", "add-generic-password", "-U", "-a", keyId,
                    "-s", SERVICE, "-w");
            case WINDOWS -> List.of(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    WINDOWS_WRITE_SCRIPT, SERVICE, keyId);
            case LINUX -> List.of(
                    "secret-tool", "store", "--label=" + SERVICE, "service", SERVICE, "account", keyId);
            case UNSUPPORTED -> List.of();
        };
    }

    /**
     * 判断平台命令的“凭据不存在”退出码。
     *
     * @param exitCode 退出码
     * @return 不存在返回 true
     */
    private boolean missingExitCode(int exitCode) {
        return switch (platform) {
            case MACOS, WINDOWS -> exitCode == 44;
            case LINUX -> exitCode == 1;
            case UNSUPPORTED -> false;
        };
    }

    /**
     * 支持的系统密钥环平台。
     *
     * <p>by AI.Coding</p>
     */
    enum Platform {
        MACOS,
        WINDOWS,
        LINUX,
        UNSUPPORTED;

        /**
         * 根据 JVM 操作系统名称选择平台。
         *
         * @return 当前平台
         */
        static Platform current() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("mac")) {
                return MACOS;
            }
            if (os.contains("win")) {
                return WINDOWS;
            }
            return os.contains("linux") ? LINUX : UNSUPPORTED;
        }
    }

    /**
     * 系统密钥环读取结果。
     *
     * <p>by AI.Coding</p>
     *
     * @param state 读取状态
     * @param secret Base64 AES 密钥
     */
    record KeyringRead(ReadState state, String secret) {

        /**
         * 创建读取成功结果。
         *
         * @param secret 密钥
         * @return 结果
         */
        static KeyringRead found(String secret) {
            return new KeyringRead(ReadState.FOUND, secret);
        }

        /**
         * 创建凭据不存在结果。
         *
         * @return 结果
         */
        static KeyringRead missing() {
            return new KeyringRead(ReadState.MISSING, "");
        }

        /**
         * 创建密钥环不可用结果。
         *
         * @return 结果
         */
        static KeyringRead unavailable() {
            return new KeyringRead(ReadState.UNAVAILABLE, "");
        }
    }

    /**
     * 系统密钥环读取状态。
     *
     * <p>by AI.Coding</p>
     */
    enum ReadState {
        FOUND,
        MISSING,
        UNAVAILABLE
    }

    /**
     * 可替换命令执行边界，测试使用显式 Fake，生产使用受限进程实现。
     *
     * <p>by AI.Coding</p>
     */
    @FunctionalInterface
    interface CommandExecutor {

        /**
         * 执行固定平台命令。
         *
         * @param command 参数化命令
         * @param input 标准输入
         * @return 结果
         */
        CommandResult execute(List<String> command, String input);
    }

    /**
     * 有界命令结果，不保留或暴露标准错误详情。
     *
     * <p>by AI.Coding</p>
     *
     * @param available 命令是否可执行
     * @param exitCode 退出码
     * @param output 有界标准输出
     */
    record CommandResult(boolean available, int exitCode, String output) {

        /**
         * 创建命令不可用结果。
         *
         * @return 结果
         */
        static CommandResult unavailable() {
            return new CommandResult(false, -1, "");
        }
    }

    /**
     * 使用 ProcessBuilder 执行固定白名单命令，输出和等待时间均有上限。
     *
     * <p>by AI.Coding</p>
     */
    private static class ProcessCommandExecutor implements CommandExecutor {

        /**
         * 执行系统密钥环命令；任何启动、超时或读取异常均视为后端不可用。
         *
         * @param command 参数化命令
         * @param input 标准输入
         * @return 有界结果
         */
        @Override
        public CommandResult execute(List<String> command, String input) {
            Process process = null;
            try {
                process = new ProcessBuilder(command).redirectErrorStream(true).start();
                writeInput(process, input);
                if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    return CommandResult.unavailable();
                }
                byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
                if (output.length > MAX_OUTPUT_BYTES) {
                    return CommandResult.unavailable();
                }
                return new CommandResult(
                        true, process.exitValue(), new String(output, StandardCharsets.UTF_8));
            } catch (IOException ex) {
                return CommandResult.unavailable();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return CommandResult.unavailable();
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }

        /**
         * 写入并关闭子进程标准输入，使凭据命令不会等待交互输入。
         *
         * @param process 子进程
         * @param input 输入文本
         * @throws IOException 写入失败
         */
        private void writeInput(Process process, String input) throws IOException {
            try (OutputStream output = process.getOutputStream()) {
                if (input != null && !input.isEmpty()) {
                    output.write(input.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            }
        }
    }
}
