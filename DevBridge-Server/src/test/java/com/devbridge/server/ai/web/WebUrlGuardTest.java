package com.devbridge.server.ai.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 网页 URL 安全边界测试。
 *
 * <p>by AI.Coding</p>
 */
class WebUrlGuardTest {

    private final WebUrlGuard guard = new WebUrlGuard();

    /** 公网 HTTP/HTTPS 地址允许读取。 */
    @Test
    void shouldAllowPublicAddress() {
        assertThat(guard.requirePublicHttpUrl("https://8.8.8.8/docs").getHost()).isEqualTo("8.8.8.8");
    }

    /** 本机、私网、Metadata 和非 HTTP 协议必须阻断。 */
    @Test
    void shouldBlockNonPublicAddresses() {
        for (String url : new String[]{
                "http://127.0.0.1:18180", "http://192.168.1.2", "http://169.254.169.254",
                "file:///etc/passwd"}) {
            assertThatThrownBy(() -> guard.requirePublicHttpUrl(url))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
