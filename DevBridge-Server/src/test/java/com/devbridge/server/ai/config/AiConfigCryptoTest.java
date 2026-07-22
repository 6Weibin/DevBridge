package com.devbridge.server.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.config.AiSystemKeyring.CommandExecutor;
import com.devbridge.server.ai.config.AiSystemKeyring.CommandResult;
import com.devbridge.server.ai.config.AiSystemKeyring.Platform;
import com.devbridge.server.ai.config.AiSystemKeyring.ReadState;
import com.devbridge.server.model.BusinessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AI 配置密钥保护测试，覆盖系统密钥环、旧文件迁移和安全文件回退。
 *
 * <p>by AI.Coding</p>
 */
class AiConfigCryptoTest {

    @TempDir
    Path tempDir;

    /**
     * 验证支持标准输入的系统密钥环使用固定命令传递密钥。
     */
    @Test
    void shouldUseNativeKeyringWithoutSecretInCommandArguments() {
        for (Platform platform : List.of(Platform.WINDOWS, Platform.LINUX)) {
            RecordingExecutor executor = new RecordingExecutor();
            AiSystemKeyring keyring = new AiSystemKeyring(platform, executor);
            executor.next = new CommandResult(true, 0, "");

            assertThat(keyring.store("root-id", "base64-secret")).isTrue();
            assertThat(executor.lastCommand()).noneMatch(value -> value.contains("base64-secret"));
            assertThat(executor.lastInput()).contains("base64-secret");

            executor.next = new CommandResult(true, 0, "base64-secret");
            assertThat(keyring.read("root-id").state()).isEqualTo(ReadState.FOUND);
            assertThat(executor.lastCommand()).noneMatch(value -> value.contains("base64-secret"));
        }
    }

    /** 验证 macOS 不使用会产生空密码项的 security CLI 写入方式。 */
    @Test
    void shouldUseSecureFallbackFileOnMacOs() {
        RecordingExecutor executor = new RecordingExecutor();
        AiConfigCrypto crypto = new AiConfigCrypto(new AiSystemKeyring(Platform.MACOS, executor));

        String cipherText = crypto.encrypt(tempDir, "sk-macos-fallback");

        assertThat(tempDir.resolve("ai-config.key")).exists();
        assertThat(crypto.decrypt(tempDir, cipherText)).isEqualTo("sk-macos-fallback");
        assertThat(executor.lastCommand()).noneMatch("add-generic-password"::equals);
        assertThat(executor.lastInput()).isEmpty();
    }

    /**
     * 验证密钥写入系统存储后不创建回退文件，并可由新 Crypto 实例继续解密。
     */
    @Test
    void shouldPersistAesKeyInSystemKeyringAcrossCryptoInstances() {
        MemoryKeyringExecutor executor = new MemoryKeyringExecutor();
        AiConfigCrypto first = crypto(executor);

        String cipherText = first.encrypt(tempDir, "sk-system-keyring");

        assertThat(tempDir.resolve("ai-config.key")).doesNotExist();
        assertThat(crypto(executor).decrypt(tempDir, cipherText)).isEqualTo("sk-system-keyring");
        assertThat(executor.commands).allMatch(command -> command.stream()
                .noneMatch(value -> value.contains(executor.secret == null ? "never" : executor.secret)));
    }

    /**
     * 验证旧版文件密钥能解密原密文，并在成功写入系统密钥环后删除回退文件。
     */
    @Test
    void shouldMigrateLegacyFileKeyWithoutChangingCipherFormat() {
        AiConfigCrypto legacy = new AiConfigCrypto();
        String cipherText = legacy.encrypt(tempDir, "sk-legacy-compatible");
        assertThat(tempDir.resolve("ai-config.key")).exists();
        MemoryKeyringExecutor executor = new MemoryKeyringExecutor();

        String plainText = crypto(executor).decrypt(tempDir, cipherText);

        assertThat(plainText).isEqualTo("sk-legacy-compatible");
        assertThat(tempDir.resolve("ai-config.key")).doesNotExist();
        assertThat(crypto(executor).decrypt(tempDir, cipherText)).isEqualTo("sk-legacy-compatible");
    }

    /**
     * 验证系统密钥环不可用时回退文件和目录被限制为仅当前所有者访问。
     *
     * @throws IOException 权限读取失败
     */
    @Test
    void shouldSecureFallbackFilePermissions() throws IOException {
        AiConfigCrypto crypto = new AiConfigCrypto();

        crypto.encrypt(tempDir, "sk-fallback");

        Path keyFile = tempDir.resolve("ai-config.key");
        assertThat(keyFile).exists();
        if (Files.getFileAttributeView(keyFile, PosixFileAttributeView.class) != null) {
            assertThat(Files.getPosixFilePermissions(tempDir))
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(keyFile))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    /**
     * 验证已有配置但系统密钥缺失时明确失败，不生成新密钥覆盖恢复路径。
     *
     * @throws IOException 配置文件写入失败
     */
    @Test
    void shouldRejectKeyRotationWhenExistingConfigurationHasNoKey() throws IOException {
        Files.writeString(tempDir.resolve("ai-config.json"), "{}", StandardCharsets.UTF_8);
        MemoryKeyringExecutor executor = new MemoryKeyringExecutor();
        String iv = Base64.getEncoder().encodeToString(new byte[12]);
        String payload = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> crypto(executor).decrypt(tempDir, iv + ":" + payload))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo("AI_CONFIG_DECRYPT_FAILED");
        assertThat(tempDir.resolve("ai-config.key")).doesNotExist();
    }

    /**
     * 创建使用 Linux Secret Service 命令协议的内存密钥环 Crypto。
     *
     * @param executor 内存执行器
     * @return Crypto
     */
    private AiConfigCrypto crypto(MemoryKeyringExecutor executor) {
        return new AiConfigCrypto(new AiSystemKeyring(Platform.LINUX, executor));
    }

    /**
     * 记录最后一次平台命令和标准输入的显式测试执行器。
     *
     * <p>by AI.Coding</p>
     */
    private static class RecordingExecutor implements CommandExecutor {

        private CommandResult next = CommandResult.unavailable();
        private List<String> command = List.of();
        private String input = "";

        /**
         * 记录调用并返回预设结果。
         *
         * @param command 命令
         * @param input 标准输入
         * @return 预设结果
         */
        @Override
        public CommandResult execute(List<String> command, String input) {
            this.command = List.copyOf(command);
            this.input = input;
            return next;
        }

        /**
         * 返回最后命令。
         *
         * @return 命令
         */
        private List<String> lastCommand() {
            return command;
        }

        /**
         * 返回最后标准输入。
         *
         * @return 输入
         */
        private String lastInput() {
            return input;
        }
    }

    /**
     * 模拟 Secret Service 的内存执行器，不使用 Mockito 或真实系统密钥环。
     *
     * <p>by AI.Coding</p>
     */
    private static class MemoryKeyringExecutor implements CommandExecutor {

        private final List<List<String>> commands = new ArrayList<>();
        private String secret;

        /**
         * 对 store 保存标准输入，对 lookup 返回已保存密钥。
         *
         * @param command Secret Service 命令
         * @param input 标准输入
         * @return 模拟结果
         */
        @Override
        public CommandResult execute(List<String> command, String input) {
            commands.add(List.copyOf(command));
            if (command.contains("store")) {
                secret = input.trim();
                return new CommandResult(true, 0, "");
            }
            if (secret == null) {
                return new CommandResult(true, 1, "");
            }
            return new CommandResult(true, 0, secret);
        }
    }
}
