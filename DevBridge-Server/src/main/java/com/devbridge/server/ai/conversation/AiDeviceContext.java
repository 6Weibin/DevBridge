package com.devbridge.server.ai.conversation;

/**
 * AI 对话中的当前设备上下文。
 *
 * <p>by AI.Coding</p>
 *
 * @param platform 平台
 * @param serial 设备序列号
 * @param model 设备型号
 * @param osVersion 系统版本
 * @param status 连接状态
 */
public record AiDeviceContext(String platform, String serial, String model, String osVersion, String status) {
}
