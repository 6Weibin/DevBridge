package com.devbridge.server.service;

import com.devbridge.server.command.CommandResult;
import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.model.CommandDiagnostic;
import com.devbridge.server.model.DeviceInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * USB 设备枚举服务，通过受控 CLI 调用汇总多平台设备。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class DeviceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceService.class);
    private static final Duration ADB_ENUMERATION_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration ADB_DAEMON_TIMEOUT = Duration.ofSeconds(15);

    private final ExecutableLocator executableLocator;
    private final CommandRunner commandRunner;
    private final DeviceOutputParser parser = new DeviceOutputParser();

    /**
     * 注入工具定位和命令执行能力。
     *
     * @param executableLocator 工具路径定位器
     * @param commandRunner 命令执行器
     */
    public DeviceService(ExecutableLocator executableLocator, CommandRunner commandRunner) {
        this.executableLocator = executableLocator;
        this.commandRunner = commandRunner;
    }

    /**
     * 枚举 Android、HarmonyOS、iOS 设备；单个平台失败不影响其他平台。
     *
     * @return 设备列表
     */
    public List<DeviceInfo> listDevices() {
        List<DeviceInfo> devices = new ArrayList<>();
        devices.addAll(listByCommand(ToolCatalog.ADB, List.of("devices"), parser::parseAdbDevices));
        devices.addAll(listByCommand(ToolCatalog.HDC, List.of("list", "targets"), parser::parseHdcTargets));
        devices.addAll(listByCommand(ToolCatalog.IDEVICE_ID, List.of("-l"), parser::parseIosDevices));
        return devices;
    }

    /**
     * 诊断 adb 设备枚举命令，直接暴露后端进程看到的 stdout/stderr，便于定位 USB 授权或进程权限问题。
     *
     * @return adb 命令诊断结果
     */
    public CommandDiagnostic diagnoseAdbDevices() {
        return diagnoseCommand(ToolCatalog.ADB, List.of("devices", "-l"));
    }

    /**
     * 按工具定义执行设备枚举命令，工具不存在或命令失败时返回空列表。
     *
     * @param definition 工具定义
     * @param args 枚举参数
     * @param outputParser 输出解析函数
     * @return 设备列表
     */
    private List<DeviceInfo> listByCommand(
            ToolDefinition definition,
            List<String> args,
            java.util.function.Function<List<String>, List<DeviceInfo>> outputParser) {
        String executable = executableLocator.locate(definition);
        if (executable.isBlank()) {
            return List.of();
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(args);
        CommandResult result = runDeviceCommand(definition, command);
        if (!result.successful() && ToolCatalog.ADB.equals(definition)) {
            // adb daemon 启动和 USB 握手经常超过通用 3 秒超时；先重置异常 daemon，再用专用超时重试一次。
            recoverAdbDaemon(executable, result);
            result = runDeviceCommand(definition, command);
        }
        if (!result.successful()) {
            LOGGER.warn(
                    "设备枚举命令执行失败，tool={}, exitCode={}, timedOut={}, stderr={}",
                    definition.name(),
                    result.exitCode(),
                    result.timedOut(),
                    result.stderr());
            return List.of();
        }
        return outputParser.apply(result.stdout());
    }

    /**
     * 执行诊断命令；工具缺失时返回 127，避免诊断接口抛出 500。
     *
     * @param definition 工具定义
     * @param args 命令参数
     * @return 命令诊断结果
     */
    private CommandDiagnostic diagnoseCommand(ToolDefinition definition, List<String> args) {
        String executable = executableLocator.locate(definition);
        if (executable.isBlank()) {
            return new CommandDiagnostic(List.of(definition.commands().get(0)), 127, List.of(), List.of("tool not found"), false);
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(args);
        CommandResult result = runDeviceCommand(definition, command);
        if (!result.successful() && ToolCatalog.ADB.equals(definition)) {
            // 启动页依赖诊断接口判断 ADB 服务是否可用；协议异常时必须复用 daemon 恢复逻辑。
            recoverAdbDaemon(executable, result);
            result = runDeviceCommand(definition, command);
        }
        return new CommandDiagnostic(command, result.exitCode(), result.stdout(), result.stderr(), result.timedOut());
    }

    /**
     * 执行设备枚举命令；ADB 枚举需要覆盖 daemon 启动时间，不能使用过短的通用命令超时。
     *
     * @param definition 工具定义
     * @param command 完整命令
     * @return 命令执行结果
     */
    private CommandResult runDeviceCommand(ToolDefinition definition, List<String> command) {
        if (ToolCatalog.ADB.equals(definition)) {
            return commandRunner.run(command, ADB_ENUMERATION_TIMEOUT);
        }
        return commandRunner.run(command);
    }

    /**
     * 修复 ADB daemon 异常状态；协议错误通常来自上一次启动被短超时强杀，必须先清理再启动。
     *
     * @param executable adb 可执行路径
     * @param previousResult 上一次 adb 结果
     */
    private void recoverAdbDaemon(String executable, CommandResult previousResult) {
        if (shouldResetAdbDaemon(previousResult)) {
            commandRunner.run(List.of(executable, "kill-server"), ADB_DAEMON_TIMEOUT);
        }
        commandRunner.run(List.of(executable, "start-server"), ADB_DAEMON_TIMEOUT);
    }

    /**
     * 判断是否需要重置 ADB daemon，避免普通 unauthorized/offline 结果误杀全局 adb server。
     *
     * @param result adb 执行结果
     * @return 需要重置返回 true
     */
    private boolean shouldResetAdbDaemon(CommandResult result) {
        String stderr = String.join("\n", result.stderr()).toLowerCase();
        return result.timedOut()
                || stderr.contains("protocol fault")
                || stderr.contains("connection reset")
                || stderr.contains("cannot connect to daemon")
                || stderr.contains("failed to check server version");
    }
}
