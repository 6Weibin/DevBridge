package com.devbridge.server.service;

import com.devbridge.server.command.CommandResult;
import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.model.BusinessException;
import com.devbridge.server.model.DeviceDetail;
import com.devbridge.server.model.DeviceStatus;
import com.devbridge.server.model.Platform;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * iOS 设备能力服务，基于 libimobiledevice 白名单命令读取非文件系统类设备信息。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class IosDeviceService {

    private final ExecutableLocator executableLocator;
    private final CommandRunner commandRunner;

    /**
     * 注入工具定位和短命令执行能力。
     *
     * @param executableLocator 工具定位器
     * @param commandRunner 命令执行器
     */
    public IosDeviceService(ExecutableLocator executableLocator, CommandRunner commandRunner) {
        this.executableLocator = executableLocator;
        this.commandRunner = commandRunner;
    }

    /**
     * 获取 iOS 设备详情；只映射前端展示需要的白名单字段，避免暴露全量设备信息。
     *
     * @param udid iOS 设备 UDID
     * @return iOS 设备详情
     */
    public DeviceDetail getDetail(String udid) {
        ensureConnected(udid);
        String productType = firstText(readOptionalKey(udid, "", "ProductType"), "iOS Device");
        String hardwareModel = readOptionalKey(udid, "", "HardwareModel");
        String hardwarePlatform = readOptionalKey(udid, "", "HardwarePlatform");
        String productVersion = readOptionalKey(udid, "", "ProductVersion");
        String availableStorage = firstText(
                readOptionalKey(udid, "com.apple.disk_usage", "AmountDataAvailable"),
                readOptionalKey(udid, "com.apple.disk_usage", "TotalDataAvailable"));
        return new DeviceDetail(
                Platform.IOS.getValue() + ":" + udid,
                udid,
                Platform.IOS,
                DeviceStatus.CONNECTED,
                "Apple",
                productType,
                prefix("iOS ", productVersion),
                "",
                parseInteger(readOptionalKey(udid, "com.apple.mobile.battery", "BatteryCurrentCapacity")),
                "",
                storageSummary(readOptionalKey(udid, "com.apple.disk_usage", "TotalDiskCapacity"), availableStorage),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                readOptionalKey(udid, "", "DeviceName"),
                readOptionalKey(udid, "", "BuildVersion"),
                readOptionalKey(udid, "", "UniqueChipID"),
                readOptionalKey(udid, "", "ActivationState"),
                productType,
                readOptionalKey(udid, "", "CPUArchitecture"),
                hardwareModel,
                hardwarePlatform,
                readOptionalKey(udid, "", "DeviceClass"),
                readOptionalKey(udid, "", "ModelNumber"),
                hardwareSummary(hardwarePlatform, hardwareModel),
                "",
                null);
    }

    /**
     * 校验 iOS 设备可用于日志采集；日志启动前只做连接校验，避免误启动无效长进程。
     *
     * @param udid iOS 设备 UDID
     */
    public void ensureLoggable(String udid) {
        ensureConnected(udid);
    }

    /**
     * 获取 idevicesyslog 可执行路径，供 iOS 实时日志长进程使用。
     *
     * @return idevicesyslog 可执行路径
     */
    public String idevicesyslogExecutable() {
        String executable = executableLocator.locate(ToolCatalog.IDEVICESYSLOG);
        if (!StringUtils.hasText(executable)) {
            throw new BusinessException("TOOL_NOT_FOUND", "idevicesyslog 工具不存在", HttpStatus.CONFLICT, "idevicesyslog");
        }
        return executable;
    }

    /**
     * 解析 ideviceinfo 的 `key: value` 输出格式，保留原始 key 便于白名单映射。
     *
     * @param lines 命令输出行
     * @return key-value 映射
     */
    static Map<String, String> parseKeyValueLines(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines) {
            int splitIndex = line.indexOf(':');
            if (splitIndex <= 0) {
                continue;
            }
            String key = line.substring(0, splitIndex).trim();
            String value = line.substring(splitIndex + 1).trim();
            if (!key.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    /**
     * 读取 iOS 白名单字段；可选字段失败时返回空，避免磁盘等扩展域异常拖垮基础信息。
     *
     * @param udid iOS 设备 UDID
     * @param domain 查询域，空表示默认域
     * @param key 查询 key
     * @return key 对应文本
     */
    private String readOptionalKey(String udid, String domain, String key) {
        CommandResult result = ideviceinfo(udid, domain, key);
        if (!result.successful() || result.stdout().isEmpty()) {
            return "";
        }
        return result.stdout().get(0).trim();
    }

    /**
     * 确认 iOS 设备仍在当前连接列表中。
     *
     * @param udid iOS 设备 UDID
     */
    private void ensureConnected(String udid) {
        CommandResult result = run(ToolCatalog.IDEVICE_ID, List.of("-l"));
        if (!result.successful()) {
            throw new BusinessException("DEVICE_NOT_CONNECTED", "iOS 设备未连接或未信任此电脑", HttpStatus.CONFLICT, result.firstOutputLine());
        }
        boolean connected = result.stdout().stream().map(String::trim).anyMatch(udid::equals);
        if (!connected) {
            throw new BusinessException("DEVICE_NOT_CONNECTED", "iOS 设备未连接", HttpStatus.CONFLICT, udid);
        }
    }

    /**
     * 执行 ideviceinfo 命令，按需指定 domain 和 key。
     *
     * @param udid iOS 设备 UDID
     * @param domain 查询域，空表示默认域
     * @param key 查询 key，空表示该域全部 key
     * @return 命令结果
     */
    private CommandResult ideviceinfo(String udid, String domain, String key) {
        List<String> args = new ArrayList<>();
        args.add("-u");
        args.add(udid);
        if (StringUtils.hasText(domain)) {
            args.add("-q");
            args.add(domain);
        }
        if (StringUtils.hasText(key)) {
            args.add("-k");
            args.add(key);
        }
        return run(ToolCatalog.IDEVICEINFO, args);
    }

    /**
     * 执行受控工具命令，统一处理工具缺失错误。
     *
     * @param definition 工具定义
     * @param args 命令参数
     * @return 命令结果
     */
    private CommandResult run(ToolDefinition definition, List<String> args) {
        String executable = executableLocator.locate(definition);
        if (!StringUtils.hasText(executable)) {
            throw new BusinessException("TOOL_NOT_FOUND", definition.name() + " 工具不存在", HttpStatus.CONFLICT, definition.name());
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(args);
        return commandRunner.run(command);
    }

    /**
     * 生成 iOS 存储摘要。
     *
     * @param disk 磁盘信息
     * @return 存储摘要
     */
    private String storageSummary(String totalValue, String availableValue) {
        long total = parseLong(totalValue);
        long available = parseLong(availableValue);
        if (total <= 0L) {
            return "";
        }
        if (available < 0L) {
            return formatBytes(total);
        }
        return formatBytes(available) + " 可用 / " + formatBytes(total) + " 总计";
    }

    /**
     * 汇总 iOS 硬件平台和硬件型号，作为前端处理器栏的低风险硬件摘要。
     *
     * @param hardwarePlatform 硬件平台
     * @param hardwareModel 硬件型号
     * @return 硬件摘要
     */
    private String hardwareSummary(String hardwarePlatform, String hardwareModel) {
        if (StringUtils.hasText(hardwarePlatform) && StringUtils.hasText(hardwareModel)) {
            return hardwarePlatform + " / " + hardwareModel;
        }
        return firstText(hardwarePlatform, hardwareModel);
    }

    /**
     * 给有内容的值补前缀。
     *
     * @param prefix 前缀
     * @param value 原始值
     * @return 带前缀的值
     */
    private String prefix(String prefix, String value) {
        return StringUtils.hasText(value) ? prefix + value : "";
    }

    /**
     * 取第一个非空文本。
     *
     * @param value 候选值
     * @param fallback 兜底值
     * @return 非空文本
     */
    private String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    /**
     * 解析整数，失败时返回 null。
     *
     * @param value 原始值
     * @return 整数或 null
     */
    private Integer parseInteger(String value) {
        try {
            return StringUtils.hasText(value) ? Integer.parseInt(value.trim()) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 解析长整数，失败时返回 -1。
     *
     * @param value 原始值
     * @return 长整数或 -1
     */
    private long parseLong(String value) {
        try {
            return StringUtils.hasText(value) ? Long.parseLong(value.trim()) : -1L;
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    /**
     * 格式化字节数，前端直接展示可读摘要。
     *
     * @param bytes 字节数
     * @return 可读大小
     */
    private String formatBytes(long bytes) {
        double gib = bytes / 1024.0 / 1024.0 / 1024.0;
        return String.format(java.util.Locale.ROOT, "%.1f GB", gib);
    }
}
