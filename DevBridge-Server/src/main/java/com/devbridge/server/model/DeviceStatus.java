package com.devbridge.server.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * USB 设备连接状态。
 *
 * <p>by AI.Coding</p>
 */
public enum DeviceStatus {
    CONNECTED("connected"),
    UNAUTHORIZED("unauthorized"),
    OFFLINE("offline");

    private final String value;

    /**
     * 保存前端约定的状态字符串。
     *
     * @param value 前端状态值
     */
    DeviceStatus(String value) {
        this.value = value;
    }

    /**
     * 获取前端使用的状态值。
     *
     * @return 状态值
     */
    @JsonValue
    public String getValue() {
        return value;
    }
}
