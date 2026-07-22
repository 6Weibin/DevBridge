package com.devbridge.server.ai.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 敏感信息脱敏测试，确保日志出网前不会携带常见凭证。
 *
 * <p>by AI.Coding</p>
 */
class SensitiveDataMaskerTest {

    /**
     * 验证 Authorization、token、邮箱和手机号都会被脱敏。
     */
    @Test
    void maskTextShouldHideCommonSecrets() {
        SensitiveDataMasker masker = new SensitiveDataMasker();

        String masked = masker.maskText("Authorization: Bearer abc token=secret user=a@b.com phone=13812345678");

        assertThat(masked).doesNotContain("abc", "secret", "a@b.com", "13812345678");
        assertThat(masked).contains("Authorization: ***", "token=***", "***@***", "1**********");
    }

    /**
     * 验证设备序列号只保留首尾少量字符，便于定位但不完整暴露。
     */
    @Test
    void maskSerialShouldKeepOnlyEdges() {
        SensitiveDataMasker masker = new SensitiveDataMasker();

        assertThat(masker.maskSerial("ABCDEF123456")).isEqualTo("ABC***456");
        assertThat(masker.maskSerial("ABC")).isEqualTo("***");
    }
}
