package com.devbridge.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.config.DevBridgeProperties;
import org.junit.jupiter.api.Test;

/**
 * 运行环境服务测试，确保前端看到的平台目录和工具定位逻辑一致。
 *
 * <p>by AI.Coding</p>
 */
class RuntimeEnvironmentServiceTest {

    /**
     * 验证运行环境会返回当前工具根目录和平台目录名。
     */
    @Test
    void currentEnvironmentShouldExposeToolDirectoryName() {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setBundledToolRoot("tools");
        ExecutableLocator locator = new ExecutableLocator(properties);
        RuntimeEnvironmentService service = new RuntimeEnvironmentService(properties, locator);

        assertThat(service.currentEnvironment().bundledToolRoot()).isEqualTo("tools");
        assertThat(service.currentEnvironment().toolDirectoryName()).isNotBlank();
        assertThat(service.currentEnvironment().appName()).isEqualTo("Ai DevBridge");
        assertThat(service.currentEnvironment().appVersion()).startsWith("V");
    }
}
