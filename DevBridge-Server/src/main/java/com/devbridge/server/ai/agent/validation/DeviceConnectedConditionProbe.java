package com.devbridge.server.ai.agent.validation;

import com.devbridge.server.model.DeviceStatus;
import com.devbridge.server.service.DeviceService;
import org.springframework.stereotype.Component;

/**
 * 手机设备在线条件探针，复用现有统一设备枚举服务。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class DeviceConnectedConditionProbe implements AgentConditionProbe {

    private final DeviceService deviceService;

    /**
     * 注入设备服务。
     *
     * @param deviceService 设备服务
     */
    public DeviceConnectedConditionProbe(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /**
     * 返回设备连接条件类型。
     *
     * @return 条件类型
     */
    @Override
    public AgentStepConditionType type() {
        return AgentStepConditionType.DEVICE_CONNECTED;
    }

    /**
     * 按设备序列号检查当前是否仍处于连接状态。
     *
     * @param condition 条件定义
     * @param context 校验上下文
     * @return 设备检查结果
     */
    @Override
    public AgentConditionCheck evaluate(
            AgentStepCondition condition, AgentStepValidationContext context) {
        boolean connected = deviceService.listDevices().stream()
                .anyMatch(device -> condition.target().equals(device.serial())
                        && device.status() == DeviceStatus.CONNECTED);
        return new AgentConditionCheck(
                condition.conditionId(), connected, Boolean.toString(connected),
                connected ? "设备已连接" : "设备未连接或不可用");
    }
}
