package com.devbridge.server.ai.agent.validation;

import java.util.List;

/**
 * 一组步骤条件的校验结果。
 *
 * <p>by AI.Coding</p>
 *
 * @param valid 是否全部通过
 * @param failureAction 失败动作，通过时为空
 * @param checks 单条件结果
 */
public record AgentStepValidationResult(
        boolean valid, AgentConditionFailureAction failureAction, List<AgentConditionCheck> checks) {

    /**
     * 固化条件结果副本。
     */
    public AgentStepValidationResult {
        checks = List.copyOf(checks);
    }
}
