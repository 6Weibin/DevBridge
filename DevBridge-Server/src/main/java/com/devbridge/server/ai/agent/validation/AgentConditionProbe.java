package com.devbridge.server.ai.agent.validation;

/**
 * Agent 条件探针边界，领域模块可以按类型提供实际状态检查。
 *
 * <p>by AI.Coding</p>
 */
public interface AgentConditionProbe {

    /**
     * 获取探针支持的条件类型。
     *
     * @return 条件类型
     */
    AgentStepConditionType type();

    /**
     * 执行条件检查。
     *
     * @param condition 条件定义
     * @param context 校验上下文
     * @return 条件结果
     */
    AgentConditionCheck evaluate(
            AgentStepCondition condition, AgentStepValidationContext context);
}
