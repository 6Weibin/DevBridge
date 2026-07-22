package com.devbridge.server.ai.localshell.catalog;

import com.devbridge.server.ai.localshell.model.LocalShellMcpToolDefinition;
import com.devbridge.server.ai.mcp.model.AdbOutputLimit;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import com.devbridge.server.model.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Local Shell MCP 工具目录，集中声明 AI 可调用的本机命令能力。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class LocalShellToolCatalog {

    private final ObjectMapper objectMapper;
    private final List<LocalShellMcpToolDefinition> tools;

    /**
     * 创建工具目录并初始化固定工具清单。
     *
     * @param objectMapper JSON Schema 构造工具
     */
    public LocalShellToolCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.tools = createTools();
    }

    /**
     * 返回完整 Local Shell MCP 工具目录。
     *
     * @return 工具定义列表
     */
    public List<LocalShellMcpToolDefinition> listTools() {
        return tools;
    }

    /**
     * 按名称获取工具定义，不存在时返回稳定错误码。
     *
     * @param toolName 工具名
     * @return 工具定义
     */
    public LocalShellMcpToolDefinition requireTool(String toolName) {
        return tools.stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "LOCAL_SHELL_COMMAND_UNSUPPORTED",
                        "Local Shell MCP 工具不存在",
                        HttpStatus.BAD_REQUEST,
                        toolName));
    }

    /**
     * 创建全部工具定义，P0 提供通用执行和常用只读辅助能力。
     *
     * @return 工具定义列表
     */
    private List<LocalShellMcpToolDefinition> createTools() {
        return List.of(
                tool("local_shell_exec", "执行一条本机命令。优先使用 ARGV 模式；SHELL 模式支持管道和重定向。", AdbRiskLevel.MEDIUM, execSchema(), 30),
                tool("local_shell_pwd", "返回当前允许的工作目录。", AdbRiskLevel.LOW, workingDirectorySchema(), 5),
                tool("local_shell_list_dir", "列出允许目录内容，适合替代 ls/dir 的只读目录查看。", AdbRiskLevel.LOW, pathSchema("directory"), 10),
                tool("local_shell_read_text", "读取允许目录内的小文本文件，输出会脱敏并截断。", AdbRiskLevel.MEDIUM, pathSchema("filePath"), 10),
                tool("local_shell_process_status", "查询当前由 Local Shell MCP 启动且仍在运行的本机命令。", AdbRiskLevel.LOW, baseSchema(), 5),
                tool("local_shell_cancel", "取消当前由 Local Shell MCP 启动且仍在运行的本机命令。", AdbRiskLevel.LOW, requestIdSchema(), 5));
    }

    /**
     * 创建单个工具定义。
     *
     * @param name 工具名
     * @param description 工具说明
     * @param riskLevel 默认风险
     * @param inputSchema 输入 Schema
     * @param timeoutSeconds 默认超时秒数
     * @return 工具定义
     */
    private LocalShellMcpToolDefinition tool(
            String name,
            String description,
            AdbRiskLevel riskLevel,
            JsonNode inputSchema,
            int timeoutSeconds) {
        return new LocalShellMcpToolDefinition(
                name,
                description,
                inputSchema,
                outputSchema(),
                riskLevel,
                Duration.ofSeconds(timeoutSeconds),
                AdbOutputLimit.defaults(),
                false);
    }

    /**
     * 创建本机命令执行 Schema。
     *
     * @return JSON Schema
     */
    private ObjectNode execSchema() {
        ObjectNode schema = workingDirectorySchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        ObjectNode mode = objectMapper.createObjectNode().put("type", "string");
        mode.set("enum", array("ARGV", "SHELL"));
        properties.set("mode", mode);
        properties.set("argv", stringArraySchema());
        properties.set("commandLine", objectMapper.createObjectNode().put("type", "string"));
        properties.set("environment", objectMapper.createObjectNode().put("type", "object"));
        properties.set("timeoutMillis", objectMapper.createObjectNode().put("type", "integer"));
        return schema;
    }

    /**
     * 创建带工作目录的基础 Schema。
     *
     * @return JSON Schema
     */
    private ObjectNode workingDirectorySchema() {
        ObjectNode schema = baseSchema();
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("workingDirectory", objectMapper.createObjectNode().put("type", "string"));
        schema.set("properties", properties);
        return schema;
    }

    /**
     * 创建路径参数 Schema。
     *
     * @param property 路径字段名
     * @return JSON Schema
     */
    private ObjectNode pathSchema(String property) {
        ObjectNode schema = workingDirectorySchema();
        ((ObjectNode) schema.get("properties")).set(property, objectMapper.createObjectNode().put("type", "string"));
        schema.set("required", array(property));
        return schema;
    }

    /**
     * 创建取消工具请求 ID Schema。
     *
     * @return JSON Schema
     */
    private ObjectNode requestIdSchema() {
        ObjectNode schema = baseSchema();
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("requestId", objectMapper.createObjectNode().put("type", "string"));
        schema.set("properties", properties);
        schema.set("required", array("requestId"));
        return schema;
    }

    /**
     * 创建统一输出 Schema，字段与现有工具卡片结果结构兼容。
     *
     * @return 输出 Schema
     */
    private ObjectNode outputSchema() {
        ObjectNode schema = baseSchema();
        ObjectNode properties = objectMapper.createObjectNode();
        for (String property : List.of("status", "stdout", "stderr", "message", "errorCode", "riskLevel", "confirmationToken", "toolTitle", "commandSummary")) {
            properties.set(property, objectMapper.createObjectNode().put("type", "string"));
        }
        for (String property : List.of("timedOut", "truncated", "confirmationRequired")) {
            properties.set(property, objectMapper.createObjectNode().put("type", "boolean"));
        }
        properties.set("exitCode", objectMapper.createObjectNode().put("type", "integer"));
        properties.set("durationMillis", objectMapper.createObjectNode().put("type", "integer"));
        schema.set("properties", properties);
        return schema;
    }

    /**
     * 创建基础 JSON Schema。
     *
     * @return 基础 Schema
     */
    private ObjectNode baseSchema() {
        return objectMapper.createObjectNode().put("type", "object");
    }

    /**
     * 创建字符串数组 Schema。
     *
     * @return 字符串数组 Schema
     */
    private ObjectNode stringArraySchema() {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "array");
        schema.set("items", objectMapper.createObjectNode().put("type", "string"));
        return schema;
    }

    /**
     * 创建 required 数组。
     *
     * @param values 必填字段
     * @return JSON 数组
     */
    private ArrayNode array(String... values) {
        ArrayNode array = objectMapper.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
