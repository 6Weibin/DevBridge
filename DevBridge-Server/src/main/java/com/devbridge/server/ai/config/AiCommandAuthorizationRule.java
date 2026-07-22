package com.devbridge.server.ai.config;

/**
 * AI 本机命令授权规则，供 Local Shell MCP 在执行前判断确认或阻断策略。
 *
 * <p>by AI.Coding</p>
 *
 * @param command 命令前缀或完整命令
 * @param level 安全等级：LOW 直接执行、MEDIUM 需要确认、HIGH 直接阻断
 */
public record AiCommandAuthorizationRule(String command, String level) {
}
