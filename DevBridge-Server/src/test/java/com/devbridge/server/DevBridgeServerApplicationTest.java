package com.devbridge.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.tool.AiToolRegistry;
import com.devbridge.server.ai.tool.AiToolScope;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 生产装配冒烟测试，防止多构造器注入或调度配置导致 JAR 启动失败。
 *
 * <p>by AI.Coding</p>
 */
@SpringBootTest(
        classes = DevBridgeServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "devbridge.control-plane.enabled=false",
                "devbridge.ai-config-root=target/test-runtime/config",
                "devbridge.ai-agent-data-root=target/test-runtime/agent",
                "devbridge.tool-artifact-root=target/test-runtime/artifacts",
                "devbridge.tool-audit-root=target/test-runtime/audit"
        })
class DevBridgeServerApplicationTest {

    @Autowired
    private AiToolRegistry toolRegistry;

    /** 完整 Spring 容器能够使用生产构造器和默认配置启动。 */
    @Test
    void contextShouldStart() {
        // SpringBootTest 在测试方法执行前已经完成全部生产 Bean 装配。
    }

    /** 通用助手范围必须同时向模型提供设备和网络工具。 */
    @Test
    void generalAssistantShouldExposeWebToolsToModel() {
        var toolNames = toolRegistry.toolCallbacks(AiToolScope.GENERAL_ASSISTANT, Platform.ANDROID).stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertThat(toolNames).contains("web_search", "web_fetch");
    }
}
