package com.devbridge.server.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 移动设备平台类型。
 *
 * <p>by AI.Coding</p>
 */
public enum Platform {
    ANDROID("android"),
    HARMONY("harmony"),
    IOS("ios");

    private final String value;

    /**
     * 保存前端约定的平台字符串，避免接口层暴露 Java 枚举大写名称。
     *
     * @param value 前端平台值
     */
    Platform(String value) {
        this.value = value;
    }

    /**
     * 获取前端使用的平台值。
     *
     * @return 平台值
     */
    @JsonValue
    public String getValue() {
        return value;
    }
}
