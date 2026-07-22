package com.devbridge.server.ai.localshell.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.localshell.model.LocalShellMcpToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Local Shell 工具目录测试，确保暴露给模型的 MCP 工具和 Schema 稳定。
 *
 * <p>by AI.Coding</p>
 */
class LocalShellToolCatalogTest {

    private final LocalShellToolCatalog catalog = new LocalShellToolCatalog(new ObjectMapper());

    /**
     * 验证 P0 工具全部注册，避免 AI 缺少查询和取消运行中命令的能力。
     */
    @Test
    void listToolsShouldContainAllP0Tools() {
        List<String> toolNames = catalog.listTools().stream()
                .map(LocalShellMcpToolDefinition::name)
                .toList();

        assertThat(toolNames)
                .contains(
                        "local_shell_exec",
                        "local_shell_pwd",
                        "local_shell_list_dir",
                        "local_shell_read_text",
                        "local_shell_process_status",
                        "local_shell_cancel");
    }

    /**
     * 验证执行模式 Schema 使用标准 JSON Schema enum 数组。
     */
    @Test
    void execSchemaShouldUseEnumArrayForMode() {
        JsonNode mode = catalog.requireTool("local_shell_exec").inputSchema()
                .path("properties")
                .path("mode");
        List<String> modes = new ArrayList<>();
        mode.path("enum").forEach(item -> modes.add(item.asText()));

        assertThat(mode.path("enum").isArray()).isTrue();
        assertThat(modes).containsExactly("ARGV", "SHELL");
    }
}
