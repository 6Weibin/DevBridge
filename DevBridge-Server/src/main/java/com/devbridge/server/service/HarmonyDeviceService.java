package com.devbridge.server.service;

import com.devbridge.server.command.CommandResult;
import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.model.BusinessException;
import com.devbridge.server.model.CommandDiagnostic;
import com.devbridge.server.model.DeviceDetail;
import com.devbridge.server.model.DeviceStatus;
import com.devbridge.server.model.Platform;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * HarmonyOS 设备服务，通过 HDC 白名单参数读取连接状态和基础设备详情。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class HarmonyDeviceService {

    private final ExecutableLocator executableLocator;
    private final CommandRunner commandRunner;

    /**
     * 注入工具定位和命令执行能力。
     *
     * @param executableLocator 工具定位器
     * @param commandRunner 命令执行器
     */
    public HarmonyDeviceService(ExecutableLocator executableLocator, CommandRunner commandRunner) {
        this.executableLocator = executableLocator;
        this.commandRunner = commandRunner;
    }

    /**
     * 读取 HarmonyOS 基础设备详情，未知字段保持为空而不是伪造 Android 信息。
     *
     * @param serial 设备序列号
     * @return 设备详情
     */
    public DeviceDetail getDetail(String serial) {
        ensureConnected(serial);
        String brand = firstText(parameter(serial, "const.product.brand"), "HarmonyOS");
        String model = firstText(parameter(serial, "const.product.model"), "Harmony Device");
        String version = firstText(
                parameter(serial, "const.product.software.version"),
                parameter(serial, "const.ohos.apiversion"));
        return new DeviceDetail(
                Platform.HARMONY.getValue() + ":" + serial,
                serial,
                Platform.HARMONY,
                DeviceStatus.CONNECTED,
                brand,
                model,
                version,
                parameter(serial, "const.ohos.apiversion"),
                null,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                parameter(serial, "const.product.cpu.abilist"),
                "",
                "",
                "",
                "",
                parameter(serial, "const.product.cpu.abilist"),
                "",
                null);
    }

    /**
     * 返回 HDC 连接诊断信息。
     *
     * @return 诊断结果
     */
    public CommandDiagnostic diagnoseConnection() {
        List<String> command = List.of(hdcExecutable(), "list", "targets");
        CommandResult result = commandRunner.run(command);
        return new CommandDiagnostic(command, result.exitCode(), result.stdout(), result.stderr(), result.timedOut());
    }

    /**
     * 校验目标 HarmonyOS 设备在 HDC 列表中。
     *
     * @param serial 设备序列号
     */
    public void ensureConnected(String serial) {
        if (!StringUtils.hasText(serial)) {
            throw new BusinessException("DEVICE_NOT_CONNECTED", "HarmonyOS 设备序列号不能为空", HttpStatus.BAD_REQUEST, "");
        }
        CommandResult result = commandRunner.run(List.of(hdcExecutable(), "list", "targets"));
        boolean connected = result.successful() && result.stdout().stream()
                .map(String::trim)
                .anyMatch(line -> line.equals(serial) || line.startsWith(serial + "\t") || line.startsWith(serial + " "));
        if (!connected) {
            throw new BusinessException("DEVICE_NOT_CONNECTED", "HarmonyOS 设备未连接", HttpStatus.CONFLICT, serial);
        }
    }

    /**
     * 获取 HDC 可执行路径。
     *
     * @return HDC 路径
     */
    public String hdcExecutable() {
        String executable = executableLocator.locate(ToolCatalog.HDC);
        if (!StringUtils.hasText(executable)) {
            throw new BusinessException("TOOL_NOT_FOUND", "HDC 工具不存在", HttpStatus.CONFLICT, "hdc");
        }
        return executable;
    }

    /**
     * 读取 HDC 参数值，失败时返回空值以保持详情接口可用。
     *
     * @param serial 设备序列号
     * @param key 参数键
     * @return 参数值
     */
    private String parameter(String serial, String key) {
        List<String> command = new ArrayList<>();
        command.add(hdcExecutable());
        command.add("-t");
        command.add(serial);
        command.add("shell");
        command.add("param");
        command.add("get");
        command.add(key);
        CommandResult result = commandRunner.run(command);
        return result.successful() && !result.stdout().isEmpty() ? result.stdout().get(0).trim() : "";
    }

    /**
     * 取第一个非空文本。
     *
     * @param first 第一候选
     * @param second 第二候选
     * @return 非空文本
     */
    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }
}
