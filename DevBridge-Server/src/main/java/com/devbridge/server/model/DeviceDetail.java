package com.devbridge.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 跨平台设备详情模型，字段读取失败时允许为空字符串或 null。
 *
 * <p>by AI.Coding</p>
 *
 * @param id 设备唯一展示 ID
 * @param serial 设备序列号
 * @param platform 设备平台
 * @param status 设备连接状态
 * @param brand 品牌
 * @param model 型号
 * @param osVersion 系统版本
 * @param apiLevel Android API 级别
 * @param battery 电量百分比
 * @param resolution 屏幕分辨率
 * @param storage 存储摘要
 * @param gpu Android GPU 摘要
 * @param density Android 像素密度
 * @param buildFingerprint Android Build 指纹
 * @param securityPatch Android 安全补丁日期
 * @param bootloader Android Bootloader 版本
 * @param kernelVersion Android 内核版本
 * @param baseband Android 基带版本
 * @param deviceName iOS 设备名称
 * @param buildNumber iOS Build 号
 * @param ecid iOS ECID
 * @param activationState iOS 激活状态
 * @param modelIdentifier iOS 机型标识符
 * @param cpuArchitecture iOS CPU 架构
 * @param hardwareModel iOS 硬件型号
 * @param hardwarePlatform iOS 硬件平台
 * @param deviceClass iOS 设备类型
 * @param modelNumber iOS 销售型号
 * @param cpu 处理器摘要
 * @param ram 内存摘要
 * @param nfcSupport NFC 支持状态
 */
public record DeviceDetail(
        String id,
        String serial,
        @JsonProperty("platform") Platform platform,
        @JsonProperty("status") DeviceStatus status,
        String brand,
        String model,
        String osVersion,
        String apiLevel,
        Integer battery,
        String resolution,
        String storage,
        String gpu,
        String density,
        String buildFingerprint,
        String securityPatch,
        String bootloader,
        String kernelVersion,
        String baseband,
        String deviceName,
        String buildNumber,
        String ecid,
        String activationState,
        String modelIdentifier,
        String cpuArchitecture,
        String hardwareModel,
        String hardwarePlatform,
        String deviceClass,
        String modelNumber,
        String cpu,
        String ram,
        Boolean nfcSupport) {
}
