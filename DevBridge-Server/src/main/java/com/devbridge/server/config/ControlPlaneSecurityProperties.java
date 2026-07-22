package com.devbridge.server.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地控制面安全配置，管理会话令牌和受信前端来源。
 *
 * <p>by AI.Coding</p>
 */
@ConfigurationProperties(prefix = "devbridge.control-plane")
public class ControlPlaneSecurityProperties {

    private boolean enabled = true;
    private String token = "";
    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://127.0.0.1:15173",
            "http://localhost:15173",
            "http://127.0.0.1:5173",
            "http://localhost:5173"));

    /**
     * 判断控制面认证是否启用。
     *
     * @return 启用返回 true
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置控制面认证开关。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取外部注入的会话令牌；为空时运行期自动生成。
     *
     * @return 会话令牌
     */
    public String getToken() {
        return token;
    }

    /**
     * 设置会话令牌。
     *
     * @param token 会话令牌
     */
    public void setToken(String token) {
        this.token = token == null ? "" : token;
    }

    /**
     * 获取受信前端 Origin。
     *
     * @return 受信 Origin
     */
    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    /**
     * 设置受信前端 Origin。
     *
     * @param allowedOrigins 受信 Origin
     */
    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }
}
