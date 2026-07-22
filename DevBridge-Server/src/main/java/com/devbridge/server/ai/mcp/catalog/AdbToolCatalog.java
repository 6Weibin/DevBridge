package com.devbridge.server.ai.mcp.catalog;

import com.devbridge.server.ai.mcp.model.AdbMcpToolDefinition;
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
 * ADB MCP 工具目录，集中声明项目内置 ADB 版本对应的顶层能力。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AdbToolCatalog {

    public static final String ADB_VERSION = "1.0.41 / 37.0.0-14910828";

    private final ObjectMapper objectMapper;
    private final List<AdbMcpToolDefinition> tools;

    /**
     * 创建工具目录并初始化固定工具清单。
     *
     * @param objectMapper JSON Schema 构造工具
     */
    public AdbToolCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.tools = createTools();
    }

    /**
     * 返回完整 ADB MCP 工具目录。
     *
     * @return 工具定义列表
     */
    public List<AdbMcpToolDefinition> listTools() {
        return tools;
    }

    /**
     * 按名称获取工具定义，不存在时返回稳定错误码。
     *
     * @param toolName 工具名
     * @return 工具定义
     */
    public AdbMcpToolDefinition requireTool(String toolName) {
        return tools.stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "ADB_COMMAND_UNSUPPORTED",
                        "ADB MCP 工具不存在",
                        HttpStatus.BAD_REQUEST,
                        toolName));
    }

    /**
     * 创建全部工具定义，按 ADB help 顶层命令域组织。
     *
     * @return 工具定义列表
     */
    private List<AdbMcpToolDefinition> createTools() {
        return List.of(
                tool("adb_devices", "列出 Android 设备，覆盖 adb devices [-l]", AdbRiskLevel.LOW, false, listSchema("longFormat"), 10),
                tool("adb_help", "查看 ADB 帮助，覆盖 adb help", AdbRiskLevel.LOW, false, listSchema("topic"), 5),
                tool("adb_version", "查看 ADB 版本，覆盖 adb version", AdbRiskLevel.LOW, false, objectSchema(), 5),
                tool("adb_network", "管理 ADB 网络连接，覆盖 connect、disconnect、pair、mdns", AdbRiskLevel.MEDIUM, false, actionSchema(), 30),
                tool("adb_forward", "管理 adb forward 端口规则", AdbRiskLevel.MEDIUM, true, actionSchema(), 20),
                tool("adb_reverse", "管理 adb reverse 端口规则", AdbRiskLevel.MEDIUM, true, actionSchema(), 20),
                tool("adb_file_transfer", "执行 push、pull、sync 文件传输", AdbRiskLevel.HIGH, true, fileSchema(), 300),
                tool("adb_shell", "执行任意 adb shell COMMAND...，例如查询应用列表可使用 command=pm、commandArgs=[list,packages]", AdbRiskLevel.LOW, true, shellSchema(), 60),
                tool("adb_emu", "执行 adb emu COMMAND", AdbRiskLevel.MEDIUM, true, rawArgsSchema("emuArgs"), 30),
                tool("adb_app_install", "安装 APK，覆盖 install、install-multiple、install-multi-package", AdbRiskLevel.HIGH, true, installSchema(), 300),
                tool("adb_app_uninstall", "卸载应用，覆盖 adb uninstall", AdbRiskLevel.HIGH, true, uninstallSchema(), 120),
                tool("adb_debugging", "调试与日志工具，覆盖 bugreport、jdwp、logcat", AdbRiskLevel.LOW, true, actionSchema(), 180),
                tool("adb_security", "安全相关命令，覆盖 disable-verity、enable-verity、keygen", AdbRiskLevel.HIGH, false, actionSchema(), 60),
                tool("adb_scripting", "脚本辅助命令，覆盖 wait-for、get-state、get-serialno、get-devpath", AdbRiskLevel.LOW, false, actionSchema(), 60),
                tool("adb_device_control", "设备控制命令，覆盖 remount、reboot、sideload、root、unroot、usb、tcpip", AdbRiskLevel.HIGH, true, actionSchema(), 300),
                tool("adb_server", "ADB server 管理，覆盖 start-server、kill-server、reconnect", AdbRiskLevel.MEDIUM, false, actionSchema(), 30),
                tool("adb_usb", "USB attach/detach 调试命令", AdbRiskLevel.MEDIUM, false, actionSchema(), 30),
                tool("adb_raw", "受控顶层 ADB 参数数组，覆盖 global options 和少见组合", AdbRiskLevel.MEDIUM, false, rawArgsSchema("args"), 120));
    }

    /**
     * 创建单个工具定义，统一输出 Schema 和默认输出限制。
     *
     * @param name 工具名
     * @param description 工具说明
     * @param riskLevel 默认风险
     * @param requiresDevice 是否需要设备
     * @param inputSchema 输入 Schema
     * @param timeoutSeconds 超时秒数
     * @return 工具定义
     */
    private AdbMcpToolDefinition tool(
            String name,
            String description,
            AdbRiskLevel riskLevel,
            boolean requiresDevice,
            JsonNode inputSchema,
            int timeoutSeconds) {
        return new AdbMcpToolDefinition(
                name,
                description + "。ADB 基准版本：" + ADB_VERSION,
                inputSchema,
                outputSchema(),
                riskLevel,
                Duration.ofSeconds(timeoutSeconds),
                AdbOutputLimit.defaults(),
                requiresDevice);
    }

    /**
     * 创建通用对象 Schema。
     *
     * @return JSON Schema
     */
    private ObjectNode objectSchema() {
        ObjectNode schema = baseSchema();
        schema.set("properties", objectMapper.createObjectNode());
        return schema;
    }

    /**
     * 创建带布尔或字符串字段的轻量 Schema。
     *
     * @param property 字段名
     * @return JSON Schema
     */
    private ObjectNode listSchema(String property) {
        ObjectNode schema = baseSchema();
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set(property, objectMapper.createObjectNode().put("type", "string"));
        schema.set("properties", properties);
        return schema;
    }

    /**
     * 创建 action + args 形式的通用 Schema。
     *
     * @return JSON Schema
     */
    private ObjectNode actionSchema() {
        ObjectNode schema = baseSchema();
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("action", objectMapper.createObjectNode().put("type", "string"));
        properties.set("args", stringArraySchema());
        properties.set("globalOptions", stringArraySchema());
        properties.set("environment", objectMapper.createObjectNode().put("type", "object"));
        schema.set("properties", properties);
        schema.set("required", array("action"));
        return schema;
    }

    /**
     * 创建 shell 工具 Schema，支持命令字符串和参数数组两种形式。
     *
     * @return JSON Schema
     */
    private ObjectNode shellSchema() {
        ObjectNode schema = baseSchema();
        ObjectNode properties = objectMapper.createObjectNode();
        // shell 工具实际使用 command/commandArgs；不能继承 action 必填，否则模型会被误导为必须传无效 action。
        properties.set("command", objectMapper.createObjectNode().put("type", "string"));
        properties.set("commandArgs", stringArraySchema());
        properties.set("args", stringArraySchema());
        properties.set("globalOptions", stringArraySchema());
        properties.set("environment", objectMapper.createObjectNode().put("type", "object"));
        schema.set("properties", properties);
        return schema;
    }

    /**
     * 创建文件传输 Schema。
     *
     * @return JSON Schema
     */
    private ObjectNode fileSchema() {
        ObjectNode schema = actionSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("localPath", objectMapper.createObjectNode().put("type", "string"));
        properties.set("remotePath", objectMapper.createObjectNode().put("type", "string"));
        return schema;
    }

    /**
     * 创建安装工具 Schema。
     *
     * @return JSON Schema
     */
    private ObjectNode installSchema() {
        ObjectNode schema = actionSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("apkPaths", stringArraySchema());
        return schema;
    }

    /**
     * 创建卸载工具 Schema。
     *
     * @return JSON Schema
     */
    private ObjectNode uninstallSchema() {
        ObjectNode schema = actionSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("packageName", objectMapper.createObjectNode().put("type", "string"));
        return schema;
    }

    /**
     * 创建原始参数数组 Schema。
     *
     * @param property 参数字段名
     * @return JSON Schema
     */
    private ObjectNode rawArgsSchema(String property) {
        ObjectNode schema = baseSchema();
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set(property, stringArraySchema());
        properties.set("environment", objectMapper.createObjectNode().put("type", "object"));
        schema.set("properties", properties);
        schema.set("required", array(property));
        return schema;
    }

    /**
     * 创建统一输出 Schema，字段与 AdbMcpToolResult 对齐。
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
        return objectMapper.createObjectNode()
                .put("type", "array")
                .set("items", objectMapper.createObjectNode().put("type", "string"));
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
