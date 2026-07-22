package com.devbridge.server.ai.agent.validation;

/**
 * Agent 步骤前置或后置条件。
 *
 * <p>by AI.Coding</p>
 *
 * @param conditionId 条件标识
 * @param type 条件类型
 * @param target 校验目标
 * @param expected 期望值
 * @param phase 校验阶段
 * @param failureAction 失败动作
 * @param description 条件说明
 */
public record AgentStepCondition(
        String conditionId,
        AgentStepConditionType type,
        String target,
        String expected,
        AgentStepValidationPhase phase,
        AgentConditionFailureAction failureAction,
        String description) {
}
