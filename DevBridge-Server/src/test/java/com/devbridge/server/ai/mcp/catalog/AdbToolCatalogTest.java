package com.devbridge.server.ai.mcp.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * ADB MCP 工具目录测试，确保顶层能力域都有稳定工具覆盖。
 *
 * <p>by AI.Coding</p>
 */
class AdbToolCatalogTest {

    /**
     * 验证工具目录覆盖设计要求的全部命令域。
     */
    @Test
    void listToolsShouldCoverAdbTopLevelDomains() {
        AdbToolCatalog catalog = new AdbToolCatalog(new ObjectMapper());

        assertThat(catalog.listTools())
                .extracting("name")
                .contains(
                        "adb_devices",
                        "adb_help",
                        "adb_version",
                        "adb_network",
                        "adb_forward",
                        "adb_reverse",
                        "adb_file_transfer",
                        "adb_shell",
                        "adb_app_install",
                        "adb_app_uninstall",
                        "adb_debugging",
                        "adb_security",
                        "adb_scripting",
                        "adb_device_control",
                        "adb_server",
                        "adb_usb",
                        "adb_raw");
    }

    /**
     * 验证工具定义包含输入和输出 Schema。
     */
    @Test
    void requireToolShouldReturnSchemas() {
        AdbToolCatalog catalog = new AdbToolCatalog(new ObjectMapper());

        assertThat(catalog.requireTool("adb_shell").inputSchema().get("properties").has("command")).isTrue();
        assertThat(catalog.requireTool("adb_shell").inputSchema().has("required")).isFalse();
        assertThat(catalog.requireTool("adb_shell").outputSchema().get("properties").has("confirmationToken")).isTrue();
    }
}
