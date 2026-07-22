package com.devbridge.server.ai.agent.validation;

/**
 * 单个条件的结构化校验结果。
 *
 * <p>by AI.Coding</p>
 *
 * @param conditionId 条件标识
 * @param valid 是否满足
 * @param actual 实际值摘要
 * @param message 结果说明
 */
public record AgentConditionCheck(
        String conditionId, boolean valid, String actual, String message) {
}
