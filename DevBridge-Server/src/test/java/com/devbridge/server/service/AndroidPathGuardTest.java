package com.devbridge.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.model.BusinessException;
import org.junit.jupiter.api.Test;

/**
 * Android 远端路径安全测试，确保文件接口只能接收安全的绝对路径。
 *
 * <p>by AI.Coding</p>
 */
class AndroidPathGuardTest {

    private final AndroidPathGuard guard = new AndroidPathGuard();

    /**
     * 验证根目录和常见目录会被规范化。
     */
    @Test
    void validateRemotePathShouldAllowAbsolutePaths() {
        assertThat(guard.validateRemotePath("/")).isEqualTo("/");
        assertThat(guard.validateRemotePath("/sdcard/Download/")).isEqualTo("/sdcard/Download");
        assertThat(guard.validateRemotePath("/data/local/tmp")).isEqualTo("/data/local/tmp");
    }

    /**
     * 验证空路径、相对路径、上级目录和控制字符都会被拒绝。
     */
    @Test
    void validateRemotePathShouldRejectUnsafePath() {
        assertThatThrownBy(() -> guard.validateRemotePath(""))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> guard.validateRemotePath("sdcard/Download"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> guard.validateRemotePath("/sdcard/../data"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> guard.validateRemotePath("/sdcard/\u0000Download"))
                .isInstanceOf(BusinessException.class);
    }
}
