package com.devbridge.server.ai.agent.validation;

import java.util.Map;

/**
 * Agent 步骤校验上下文。
 *
 * <p>by AI.Coding</p>
 *
 * @param taskId 任务标识
 * @param stepId 步骤标识
 * @param attributes 已验证结构化属性
 */
public record AgentStepValidationContext(
        String taskId, String stepId, Map<String, Object> attributes) {

    /**
     * 固化属性副本。
     */
    public AgentStepValidationContext {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
