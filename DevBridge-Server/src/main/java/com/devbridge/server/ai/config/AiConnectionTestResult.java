package com.devbridge.server.ai.config;

/**
 * AI 连接测试结果。
 *
 * <p>by AI.Coding</p>
 *
 * @param available 是否可用
 * @param message 用户可读提示
 * @param provider Provider 类型
 * @param model 模型名称
 */
public record AiConnectionTestResult(boolean available, String message, String provider, String model) {
}
