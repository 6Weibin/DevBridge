package com.devbridge.server.ai.mcp.execution;

import com.devbridge.server.ai.mcp.model.AdbCommandPlan;
import com.devbridge.server.ai.mcp.model.AdbMcpToolDefinition;
import com.devbridge.server.ai.mcp.model.AdbMcpToolRequest;
import com.devbridge.server.model.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * ADB MCP 命令规划器，把工具调用参数转换为不含宿主机 shell 的 ADB 参数数组。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AdbCommandPlanner {

    private static final Set<String> RAW_ALLOWED_TOP_LEVEL = Set.of(
            "devices", "help", "version", "connect", "disconnect", "pair", "forward", "reverse",
            "mdns", "push", "pull", "sync", "shell", "emu", "install", "install-multiple",
            "install-multi-package", "uninstall", "bugreport", "jdwp", "logcat", "disable-verity",
            "enable-verity", "keygen", "wait-for-device", "wait-for-usb-device", "wait-for-local-device",
            "get-state", "get-serialno", "get-devpath", "remount", "reboot", "sideload",
            "root", "unroot", "usb", "tcpip", "start-server", "kill-server", "reconnect", "attach", "detach");
    private static final Set<String> GLOBAL_OPTIONS_WITH_VALUE = Set.of("-s", "-t", "-H", "-P", "-L", "--one-device");
    private static final Set<String> GLOBAL_OPTIONS_WITHOUT_VALUE = Set.of("-a", "-d", "-e", "--exit-on-write-error");
    private static final Set<String> ENVIRONMENT_KEYS = Set.of(
            "ADB_TRACE", "ADB_VENDOR_KEYS", "ANDROID_SERIAL", "ANDROID_LOG_TAGS",
            "ADB_LOCAL_TRANSPORT_MAX_PORT", "ADB_MDNS_AUTO_CONNECT");

    /**
     * 将工具请求规划为 ADB 参数数组。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @return ADB 命令计划
     */
    public AdbCommandPlan plan(AdbMcpToolRequest request, AdbMcpToolDefinition definition) {
        Map<String, Object> args = request.safeArguments();
        List<String> adbArgs = new ArrayList<>();
        appendGlobalOptions(adbArgs, stringList(args.get("globalOptions")));
        appendDevice(adbArgs, request.deviceSerial(), definition.requiresDevice());
        adbArgs.addAll(toolArguments(request.toolName(), args));
        validateTopLevel(adbArgs);
        return new AdbCommandPlan(
                request.requestId(),
                request.conversationId(),
                request.toolName(),
                List.copyOf(adbArgs),
                request.deviceSerial(),
                definition.requiresDevice(),
                environment(args.get("environment")),
                definition.timeout(),
                definition.outputLimit(),
                streaming(request.toolName(), adbArgs));
    }

    /**
     * 根据工具名称生成工具专属参数。
     *
     * @param toolName 工具名
     * @param args 工具参数
     * @return ADB 参数
     */
    private List<String> toolArguments(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "adb_devices" -> devicesArgs(args);
            case "adb_help" -> helpArgs(args);
            case "adb_version" -> List.of("version");
            case "adb_network", "adb_forward", "adb_reverse", "adb_debugging", "adb_security",
                    "adb_scripting", "adb_device_control", "adb_server", "adb_usb" -> actionArgs(args);
            case "adb_file_transfer" -> fileTransferArgs(args);
            case "adb_shell" -> shellArgs(args);
            case "adb_emu" -> prefix("emu", stringList(args.get("emuArgs")));
            case "adb_app_install" -> installArgs(args);
            case "adb_app_uninstall" -> uninstallArgs(args);
            case "adb_raw" -> rawArgs(args);
            default -> throw new BusinessException("ADB_COMMAND_UNSUPPORTED", "ADB MCP 工具不存在", HttpStatus.BAD_REQUEST, toolName);
        };
    }

    /**
     * 生成设备列表命令参数。
     *
     * @param args 工具参数
     * @return ADB 参数
     */
    private List<String> devicesArgs(Map<String, Object> args) {
        if (Boolean.TRUE.equals(args.get("longFormat")) || "true".equals(String.valueOf(args.get("longFormat")))) {
            return List.of("devices", "-l");
        }
        return List.of("devices");
    }

    /**
     * 生成帮助命令参数。
     *
     * @param args 工具参数
     * @return ADB 参数
     */
    private List<String> helpArgs(Map<String, Object> args) {
        String topic = stringValue(args.get("topic"));
        return StringUtils.hasText(topic) ? List.of("help", topic) : List.of("help");
    }

    /**
     * 生成 action + args 风格工具参数。
     *
     * @param args 工具参数
     * @return ADB 参数
     */
    private List<String> actionArgs(Map<String, Object> args) {
        String action = required(args.get("action"), "ADB_COMMAND_EMPTY", "ADB action 不能为空");
        List<String> values = new ArrayList<>();
        values.addAll(List.of(action.split("\\s+")));
        values.addAll(stringList(args.get("args")));
        return values;
    }

    /**
     * 生成文件传输命令参数。
     *
     * @param args 工具参数
     * @return ADB 参数
     */
    private List<String> fileTransferArgs(Map<String, Object> args) {
        String action = required(args.get("action"), "ADB_COMMAND_EMPTY", "文件传输 action 不能为空");
        List<String> values = new ArrayList<>();
        values.add(action);
        values.addAll(stringList(args.get("args")));
        if (StringUtils.hasText(stringValue(args.get("localPath")))) {
            values.add(stringValue(args.get("localPath")));
        }
        if (StringUtils.hasText(stringValue(args.get("remotePath")))) {
            values.add(stringValue(args.get("remotePath")));
        }
        return values;
    }

    /**
     * 生成任意 adb shell 命令参数。
     *
     * @param args 工具参数
     * @return ADB 参数
     */
    private List<String> shellArgs(Map<String, Object> args) {
        List<String> values = new ArrayList<>();
        values.add("shell");
        values.addAll(stringList(args.get("args")));
        String command = stringValue(args.get("command"));
        List<String> commandArgs = stringList(args.get("commandArgs"));
        if (StringUtils.hasText(command)) {
            values.add(command);
        }
        values.addAll(commandArgs);
        if (values.size() == 1) {
            throw new BusinessException("ADB_SHELL_COMMAND_EMPTY", "adb shell 命令不能为空", HttpStatus.BAD_REQUEST, "");
        }
        return values;
    }

    /**
     * 生成安装命令参数。
     *
     * @param args 工具参数
     * @return ADB 参数
     */
    private List<String> installArgs(Map<String, Object> args) {
        String action = stringValue(args.get("action"));
        String command = StringUtils.hasText(action) ? action : "install";
        List<String> values = new ArrayList<>();
        values.add(command);
        values.addAll(stringList(args.get("args")));
        values.addAll(stringList(args.get("apkPaths")));
        return values;
    }

    /**
     * 生成卸载命令参数。
     *
     * @param args 工具参数
     * @return ADB 参数
     */
    private List<String> uninstallArgs(Map<String, Object> args) {
        List<String> values = new ArrayList<>();
        values.add("uninstall");
        values.addAll(stringList(args.get("args")));
        values.add(required(args.get("packageName"), "ADB_COMMAND_EMPTY", "卸载包名不能为空"));
        return values;
    }

    /**
     * 生成原始 ADB 参数。
     *
     * @param args 工具参数
     * @return ADB 参数
     */
    private List<String> rawArgs(Map<String, Object> args) {
        List<String> values = stringList(args.get("args"));
        if (values.isEmpty()) {
            throw new BusinessException("ADB_COMMAND_EMPTY", "ADB 参数不能为空", HttpStatus.BAD_REQUEST, "");
        }
        return values;
    }

    /**
     * 在需要设备且未显式指定 global device option 时补充 -s。
     *
     * @param adbArgs 参数列表
     * @param serial 设备序列号
     * @param requiresDevice 是否需要设备
     */
    private void appendDevice(List<String> adbArgs, String serial, boolean requiresDevice) {
        if (!requiresDevice || !StringUtils.hasText(serial) || adbArgs.contains("-s") || adbArgs.contains("-d") || adbArgs.contains("-e")) {
            return;
        }
        adbArgs.add("-s");
        adbArgs.add(serial);
    }

    /**
     * 追加受控 global options，禁止未建模选项破坏参数边界。
     *
     * @param adbArgs 参数列表
     * @param options global options
     */
    private void appendGlobalOptions(List<String> adbArgs, List<String> options) {
        for (int index = 0; index < options.size(); index++) {
            String option = options.get(index);
            if (GLOBAL_OPTIONS_WITHOUT_VALUE.contains(option)) {
                adbArgs.add(option);
            } else if (GLOBAL_OPTIONS_WITH_VALUE.contains(option) && index + 1 < options.size()) {
                adbArgs.add(option);
                adbArgs.add(options.get(++index));
            }
        }
    }

    /**
     * 校验 raw 或规划后命令的顶层命令是否属于 ADB help 覆盖范围。
     *
     * @param adbArgs 参数数组
     */
    private void validateTopLevel(List<String> adbArgs) {
        String topLevel = topLevelCommand(adbArgs);
        if (!StringUtils.hasText(topLevel) || !RAW_ALLOWED_TOP_LEVEL.contains(topLevel)) {
            throw new BusinessException("ADB_COMMAND_UNSUPPORTED", "ADB 顶层命令不受支持", HttpStatus.BAD_REQUEST, topLevel);
        }
    }

    /**
     * 查找跳过 global options 后的 ADB 顶层命令。
     *
     * @param adbArgs 参数数组
     * @return 顶层命令
     */
    private String topLevelCommand(List<String> adbArgs) {
        for (int index = 0; index < adbArgs.size(); index++) {
            String arg = adbArgs.get(index);
            if (GLOBAL_OPTIONS_WITHOUT_VALUE.contains(arg)) {
                continue;
            }
            if (GLOBAL_OPTIONS_WITH_VALUE.contains(arg)) {
                index++;
                continue;
            }
            return arg;
        }
        return "";
    }

    /**
     * 解析受控环境变量，只允许 ADB 相关键。
     *
     * @param source 原始对象
     * @return 环境变量映射
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> environment(Object source) {
        if (!(source instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (ENVIRONMENT_KEYS.contains(key)) {
                result.put(key, stringValue(entry.getValue()));
            }
        }
        return result;
    }

    /**
     * 判断命令是否应使用长任务执行路径。
     *
     * @param toolName 工具名
     * @param adbArgs 参数数组
     * @return 长任务返回 true
     */
    private boolean streaming(String toolName, List<String> adbArgs) {
        return "adb_debugging".equals(toolName)
                || adbArgs.contains("logcat")
                || adbArgs.contains("bugreport")
                || adbArgs.contains("push")
                || adbArgs.contains("pull")
                || adbArgs.contains("sideload");
    }

    /**
     * 给命令添加固定前缀。
     *
     * @param command 前缀命令
     * @param args 参数数组
     * @return 拼接后的参数
     */
    private List<String> prefix(String command, List<String> args) {
        List<String> values = new ArrayList<>();
        values.add(command);
        values.addAll(args);
        return values;
    }

    /**
     * 将入参转换为字符串数组，忽略空字符串。
     *
     * @param source 原始对象
     * @return 字符串数组
     */
    private List<String> stringList(Object source) {
        if (!(source instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(this::stringValue)
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * 获取必填字符串。
     *
     * @param value 原始值
     * @param code 错误码
     * @param message 错误信息
     * @return 字符串
     */
    private String required(Object value, String code, String message) {
        String text = stringValue(value);
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(code, message, HttpStatus.BAD_REQUEST, "");
        }
        return text;
    }

    /**
     * 将对象转换为字符串。
     *
     * @param value 原始对象
     * @return 字符串
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
