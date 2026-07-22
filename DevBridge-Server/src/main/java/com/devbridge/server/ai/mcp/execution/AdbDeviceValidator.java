package com.devbridge.server.ai.mcp.execution;

import com.devbridge.server.ai.mcp.model.AdbCommandPlan;
import com.devbridge.server.model.BusinessException;
import com.devbridge.server.model.DeviceInfo;
import com.devbridge.server.model.DeviceStatus;
import com.devbridge.server.model.Platform;
import com.devbridge.server.service.DeviceService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * ADB MCP 设备状态校验器，确保需要设备的工具只在 connected Android 设备上执行。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AdbDeviceValidator {

    private final DeviceService deviceService;

    /**
     * 注入设备枚举服务。
     *
     * @param deviceService 设备服务
     */
    public AdbDeviceValidator(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /**
     * 校验命令计划的目标设备；不需要设备的命令直接放行。
     *
     * @param plan 命令计划
     */
    public void validate(AdbCommandPlan plan) {
        if (!plan.requiresDevice()) {
            return;
        }
        if (!StringUtils.hasText(plan.deviceSerial())) {
            throw new BusinessException("ADB_DEVICE_NOT_FOUND", "未指定目标 Android 设备", HttpStatus.NOT_FOUND, availableSummary());
        }
        DeviceInfo device = findAndroidDevice(plan.deviceSerial());
        if (device == null) {
            throw new BusinessException("ADB_DEVICE_NOT_FOUND", "目标 Android 设备不存在", HttpStatus.NOT_FOUND, availableSummary());
        }
        if (device.status() == DeviceStatus.UNAUTHORIZED) {
            throw new BusinessException("ADB_DEVICE_UNAUTHORIZED", "设备未授权 USB 调试", HttpStatus.CONFLICT, device.serial());
        }
        if (device.status() == DeviceStatus.OFFLINE) {
            throw new BusinessException("ADB_DEVICE_OFFLINE", "设备处于 offline 状态", HttpStatus.CONFLICT, device.serial());
        }
    }

    /**
     * 按序列号查找 Android 设备。
     *
     * @param serial 设备序列号
     * @return 设备信息，未找到返回 null
     */
    private DeviceInfo findAndroidDevice(String serial) {
        return deviceService.listDevices().stream()
                .filter(device -> device.platform() == Platform.ANDROID)
                .filter(device -> serial.equals(device.serial()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 生成当前可用 Android 设备摘要，错误提示使用摘要而不是完整日志。
     *
     * @return 设备摘要
     */
    private String availableSummary() {
        List<String> devices = deviceService.listDevices().stream()
                .filter(device -> device.platform() == Platform.ANDROID)
                .map(device -> device.serial() + ":" + device.status().getValue())
                .toList();
        return devices.isEmpty() ? "no android devices" : String.join(",", devices);
    }
}
