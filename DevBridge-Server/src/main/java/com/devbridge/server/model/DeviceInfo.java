package com.devbridge.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 前端设备列表使用的最小设备信息。
 *
 * <p>by AI.Coding</p>
 *
 * @param id 设备唯一展示 ID
 * @param serial 设备序列号或 UDID
 * @param model 设备型号，PoC 阶段无法读取时使用平台默认文案
 * @param platform 设备平台
 * @param osVersion 系统版本，PoC 阶段可为空占位
 * @param status 连接状态
 */
public record DeviceInfo(
        String id,
        String serial,
        String model,
        @JsonProperty("platform") Platform platform,
        String osVersion,
        @JsonProperty("status") DeviceStatus status) {
}
