package com.devbridge.server.ai.config;

import com.devbridge.server.ai.config.AiSystemKeyring.KeyringRead;
import com.devbridge.server.ai.config.AiSystemKeyring.ReadState;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.BusinessException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * AI 配置密钥加解密工具，优先使用系统密钥环保存本机 AES 密钥。
 *
 * <p>现有 AES-GCM 密文格式保持不变；旧密钥文件会迁移到系统密钥环，密钥环不可用时才使用最小权限文件。</p>
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AiConfigCrypto {

    private static final String KEY_FILENAME = "ai-config.key";
    private static final String CONFIG_FILENAME = "ai-config.json";
    private static final String AES = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_KEY_BYTES = 32;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final SecureRandom secureRandom = new SecureRandom();
    private final AiSystemKeyring keyring;
    private volatile CachedKey cachedKey;

    /**
     * 创建不使用系统密钥环的兼容实例，主要供显式构造的测试和受限嵌入环境使用。
     */
    public AiConfigCrypto() {
        this(AiSystemKeyring.disabled());
    }

    /**
     * Spring 生产构造器，启用系统密钥环并在启动阶段校验配置目录权限。
     *
     * @param keyring 系统密钥环
     * @param properties DevBridge 配置
     */
    @Autowired
    AiConfigCrypto(AiSystemKeyring keyring, DevBridgeProperties properties) {
        this(keyring);
        initialize(Path.of(properties.getAiConfigRoot()));
    }

    /**
     * 创建使用指定密钥环的可测试实例。
     *
     * @param keyring 系统密钥环
     */
    AiConfigCrypto(AiSystemKeyring keyring) {
        this.keyring = keyring;
    }

    /**
     * 加密 API Key；密文格式继续使用 base64(iv):base64(cipherText)。
     *
     * @param root AI 配置目录
     * @param plainText API Key 明文
     * @return API Key 密文
     */
    public String encrypt(Path root, String plainText) {
        try {
            SecretKey key = loadOrCreateKey(root);
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException | IOException ex) {
            throw cryptoError("AI_CONFIG_ENCRYPT_FAILED", "AI 配置加密失败", ex);
        }
    }

    /**
     * 解密 API Key；密钥缺失或不可用时明确失败，禁止生成新密钥破坏已有配置。
     *
     * @param root AI 配置目录
     * @param cipherText API Key 密文
     * @return API Key 明文
     */
    public String decrypt(Path root, String cipherText) {
        try {
            String[] parts = cipherText.split(":", 2);
            if (parts.length != 2) {
                throw new GeneralSecurityException("invalid cipher text");
            }
            SecretKey key = loadOrCreateKey(root);
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException ex) {
            throw cryptoError("AI_CONFIG_DECRYPT_FAILED", "AI 配置解密失败", ex);
        }
    }

    /**
     * 使用持久 AI 配置密钥生成 HMAC-SHA256，用于本地一次性授权令牌签名。
     *
     * @param root AI 配置目录
     * @param payload 待签名规范文本
     * @return URL 安全 Base64 签名
     */
    public String sign(Path root, String payload) {
        try {
            SecretKey key = loadOrCreateKey(root);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IOException ex) {
            throw cryptoError("AI_TOKEN_SIGN_FAILED", "AI 授权令牌签名失败", ex);
        }
    }

    /**
     * 创建配置目录并校验现有回退密钥的最小权限，供 Spring 启动阶段调用。
     *
     * @param root AI 配置目录
     */
    void initialize(Path root) {
        try {
            prepareStorage(root);
        } catch (IOException ex) {
            throw cryptoError("AI_CONFIG_KEY_PERMISSION_INVALID", "AI 配置密钥权限校验失败", ex);
        }
    }

    /**
     * 按旧文件、系统密钥环和安全回退文件的顺序读取或创建 AES 密钥。
     *
     * @param root AI 配置目录
     * @return AES 密钥
     * @throws IOException 文件读写失败
     * @throws GeneralSecurityException 密钥格式或状态异常
     */
    private SecretKey loadOrCreateKey(Path root) throws IOException, GeneralSecurityException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        CachedKey current = cachedKey;
        if (current != null && current.root().equals(normalizedRoot)) {
            return current.key();
        }
        synchronized (this) {
            current = cachedKey;
            if (current != null && current.root().equals(normalizedRoot)) {
                return current.key();
            }
            SecretKey resolved = resolveKey(normalizedRoot);
            // AES 密钥只缓存在当前 JVM 内存，避免每条历史消息重复启动系统密钥环进程。
            cachedKey = new CachedKey(normalizedRoot, resolved);
            return resolved;
        }
    }

    /**
     * 按旧文件、系统密钥环和安全回退文件的顺序解析 AES 密钥。
     *
     * @param normalizedRoot 已规范化配置根目录
     * @return AES 密钥
     * @throws IOException 文件读写失败
     * @throws GeneralSecurityException 密钥格式或状态异常
     */
    private SecretKey resolveKey(Path normalizedRoot) throws IOException, GeneralSecurityException {
        prepareStorage(normalizedRoot);
        Path keyFile = normalizedRoot.resolve(KEY_FILENAME);
        String keyId = keyId(normalizedRoot);
        if (Files.exists(keyFile)) {
            return readLegacyAndMigrate(keyFile, keyId);
        }
        KeyringRead keyringRead = keyring.read(keyId);
        if (keyringRead.state() == ReadState.FOUND) {
            return decodeKey(keyringRead.secret());
        }
        if (Files.exists(normalizedRoot.resolve(CONFIG_FILENAME))) {
            throw new GeneralSecurityException("existing AI configuration key is unavailable");
        }
        return createKey(normalizedRoot, keyFile, keyId);
    }

    /**
     * 读取旧回退文件并尽力迁移到系统密钥环，迁移失败时继续使用已加固文件。
     *
     * @param keyFile 旧密钥文件
     * @param keyId 密钥环标识
     * @return AES 密钥
     * @throws IOException 文件读取失败
     * @throws GeneralSecurityException 密钥格式错误
     */
    private SecretKey readLegacyAndMigrate(Path keyFile, String keyId)
            throws IOException, GeneralSecurityException {
        String encoded = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
        SecretKey key = decodeKey(encoded);
        if (keyring.store(keyId, encoded)) {
            try {
                Files.deleteIfExists(keyFile);
            } catch (IOException ignored) {
                // 删除只是迁移收尾，失败时保留已加固回退文件，不影响现有密文继续解密。
            }
        }
        return key;
    }

    /**
     * 生成新 AES 密钥，优先写入系统密钥环，失败时原子写入受限权限文件。
     *
     * @param root 配置根目录
     * @param keyFile 回退密钥文件
     * @param keyId 密钥环标识
     * @return AES 密钥
     * @throws GeneralSecurityException 密钥生成失败
     * @throws IOException 回退文件写入失败
     */
    private SecretKey createKey(Path root, Path keyFile, String keyId)
            throws GeneralSecurityException, IOException {
        KeyGenerator generator = KeyGenerator.getInstance(AES);
        generator.init(AES_KEY_BYTES * Byte.SIZE, secureRandom);
        SecretKey key = generator.generateKey();
        String encoded = Base64.getEncoder().encodeToString(key.getEncoded());
        if (!keyring.store(keyId, encoded)) {
            writeFallbackKey(root, keyFile, encoded);
        }
        return key;
    }

    /**
     * 原子写入最小权限回退密钥，避免留下部分内容或宽权限窗口。
     *
     * @param root 配置根目录
     * @param keyFile 最终密钥文件
     * @param encoded Base64 密钥
     * @throws IOException 写入失败
     */
    private void writeFallbackKey(Path root, Path keyFile, String encoded) throws IOException {
        Path temp = root.resolve("." + KEY_FILENAME + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(encoded);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            securePermissions(temp, false);
            moveAtomically(temp, keyFile);
            securePermissions(keyFile, false);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 创建并加固配置目录，同时修正和验证已有回退密钥权限。
     *
     * @param root 配置根目录
     * @throws IOException 权限处理失败
     */
    private void prepareStorage(Path root) throws IOException {
        Files.createDirectories(root);
        securePermissions(root, true);
        Path keyFile = root.resolve(KEY_FILENAME);
        if (Files.exists(keyFile)) {
            if (!Files.isRegularFile(keyFile)) {
                throw new IOException("AI 配置密钥路径不是普通文件");
            }
            securePermissions(keyFile, false);
        }
    }

    /**
     * 在 POSIX 或 Windows ACL 文件系统上设置并验证仅当前所有者可访问。
     *
     * @param path 目录或文件
     * @param directory 是否目录
     * @throws IOException 权限设置或验证失败
     */
    private void securePermissions(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            Set<PosixFilePermission> expected = directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS;
            Files.setPosixFilePermissions(path, expected);
            if (!Files.getPosixFilePermissions(path).equals(expected)) {
                throw new IOException("无法应用 AI 配置最小 POSIX 权限");
            }
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(path))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(List.of(ownerOnly));
            if (acl.getAcl().stream().anyMatch(entry -> !entry.principal().equals(ownerOnly.principal()))) {
                throw new IOException("无法应用 AI 配置最小 Windows ACL 权限");
            }
            return;
        }
        throw new IOException("当前文件系统不支持可验证的最小权限");
    }

    /**
     * 原子发布密钥文件，不支持原子移动时在同目录安全降级。
     *
     * @param source 临时文件
     * @param target 最终文件
     * @throws IOException 移动失败
     */
    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    /**
     * 解码并校验 256 位 AES 密钥。
     *
     * @param encoded Base64 密钥
     * @return AES 密钥
     * @throws GeneralSecurityException 格式或长度错误
     */
    private SecretKey decodeKey(String encoded) throws GeneralSecurityException {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length != AES_KEY_BYTES) {
                throw new GeneralSecurityException("invalid AES key length");
            }
            return new SecretKeySpec(bytes, AES);
        } catch (IllegalArgumentException ex) {
            throw new GeneralSecurityException("invalid AES key encoding", ex);
        }
    }

    /**
     * 由规范化配置目录派生不暴露真实路径的稳定密钥环账号。
     *
     * @param root 配置根目录
     * @return 密钥环账号
     */
    private String keyId(Path root) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(root.toString().getBytes(StandardCharsets.UTF_8));
            return "ai-config-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    /**
     * 构造不包含密钥内容的业务异常。
     *
     * @param code 错误码
     * @param message 用户消息
     * @param error 原始异常
     * @return 业务异常
     */
    private BusinessException cryptoError(String code, String message, Exception error) {
        return new BusinessException(code, message, HttpStatus.CONFLICT, error.getMessage());
    }

    /**
     * 当前 JVM 内已解析的单一配置根密钥。
     *
     * <p>by AI.Coding</p>
     */
    private record CachedKey(Path root, SecretKey key) {
    }
}
