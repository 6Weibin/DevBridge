package com.devbridge.server.ai.mcp.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.mcp.catalog.AdbToolCatalog;
import com.devbridge.server.ai.mcp.model.AdbCommandPlan;
import com.devbridge.server.ai.mcp.model.AdbMcpToolRequest;
import com.devbridge.server.model.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADB 命令规划测试，确保工具入参只转换为参数数组。
 *
 * <p>by AI.Coding</p>
 */
class AdbCommandPlannerTest {

    private final AdbToolCatalog catalog = new AdbToolCatalog(new ObjectMapper());
    private final AdbCommandPlanner planner = new AdbCommandPlanner();

    /**
     * 验证任意 shell 字符串作为 adb shell 参数传递，不经过宿主机 shell。
     */
    @Test
    void planShouldKeepShellCommandAsAdbArgument() {
        AdbMcpToolRequest request = new AdbMcpToolRequest(
                "adb_shell",
                "c1",
                "serial-1",
                Map.of("command", "getprop ro.build.version.release"),
                "",
                "r1");

        AdbCommandPlan plan = planner.plan(request, catalog.requireTool("adb_shell"));

        assertThat(plan.adbArguments()).containsExactly("-s", "serial-1", "shell", "getprop ro.build.version.release");
    }

    /**
     * 验证 raw 工具拒绝不在 ADB help 顶层清单中的命令。
     */
    @Test
    void planShouldRejectUnsupportedRawCommand() {
        AdbMcpToolRequest request = new AdbMcpToolRequest(
                "adb_raw",
                "c1",
                "",
                Map.of("args", List.of("unknown-command")),
                "",
                "r1");

        assertThatThrownBy(() -> planner.plan(request, catalog.requireTool("adb_raw")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("ADB 顶层命令不受支持");
    }
}
