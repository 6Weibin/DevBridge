package com.devbridge.server.security;

import com.devbridge.server.config.ControlPlaneSecurityProperties;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * 本地控制面会话令牌服务，令牌仅存在于当前后端进程内存。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class LocalControlPlaneTokenService {

    public static final String TOKEN_HEADER = "X-Ai-DevBridge-Token";
    public static final String TOKEN_COOKIE = "AI_DEVBRIDGE_SESSION";

    private static final int TOKEN_BYTES = 32;

    private volatile String activeToken;

    /**
     * 使用外部令牌或安全随机数初始化当前进程令牌。
     *
     * @param properties 控制面安全配置
     */
    public LocalControlPlaneTokenService(ControlPlaneSecurityProperties properties) {
        String configured = properties.getToken();
        this.activeToken = configured == null || configured.isBlank()
                ? generateToken()
                : configured.trim();
    }

    /**
     * 获取当前令牌，仅供认证过滤器签发同源会话 Cookie。
     *
     * @return 当前令牌
     */
    public String token() {
        return activeToken;
    }

    /**
     * 使用常量时间比较校验请求令牌。
     *
     * @param candidate 请求令牌
     * @return 匹配返回 true
     */
    public boolean matches(String candidate) {
        String expected = activeToken;
        if (expected == null || candidate == null || candidate.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 应用关闭时使当前令牌立即失效。
     */
    @PreDestroy
    public void invalidate() {
        activeToken = null;
    }

    /**
     * 生成 256 位 URL 安全随机令牌。
     *
     * @return 随机令牌
     */
    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
