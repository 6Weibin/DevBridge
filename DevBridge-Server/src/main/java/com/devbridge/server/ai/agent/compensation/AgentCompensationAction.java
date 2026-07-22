package com.devbridge.server.ai.agent.compensation;

import java.util.Map;

/**
 * Agent 步骤补偿动作定义。
 *
 * <p>by AI.Coding</p>
 *
 * @param actionId 动作标识
 * @param handlerType 处理器类型
 * @param parameters 结构化参数
 * @param requiresConfirmation 是否需要用户确认
 * @param description 动作说明
 */
public record AgentCompensationAction(
        String actionId,
        String handlerType,
        Map<String, Object> parameters,
        boolean requiresConfirmation,
        String description) {

    /**
     * 固化补偿参数副本。
     */
    public AgentCompensationAction {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
