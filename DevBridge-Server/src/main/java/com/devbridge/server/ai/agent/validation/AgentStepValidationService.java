package com.devbridge.server.ai.agent.validation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Agent 步骤条件校验服务，按条件类型路由到本地 Probe。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AgentStepValidationService {

    private final Map<AgentStepConditionType, AgentConditionProbe> probes =
            new EnumMap<>(AgentStepConditionType.class);

    /**
     * 注册全部条件 Probe；重复类型在启动时直接失败。
     *
     * @param availableProbes 条件 Probe
     */
    public AgentStepValidationService(List<AgentConditionProbe> availableProbes) {
        for (AgentConditionProbe probe : availableProbes) {
            AgentConditionProbe existing = probes.putIfAbsent(probe.type(), probe);
            if (existing != null) {
                throw new IllegalStateException("重复的 Agent 条件 Probe: " + probe.type());
            }
        }
    }

    /**
     * 校验指定阶段的全部条件。
     *
     * @param phase 校验阶段
     * @param context 校验上下文
     * @param conditions 条件列表
     * @return 汇总结果
     */
    public AgentStepValidationResult validate(
            AgentStepValidationPhase phase,
            AgentStepValidationContext context,
            List<AgentStepCondition> conditions) {
        List<AgentConditionCheck> checks = new ArrayList<>();
        AgentConditionFailureAction action = null;
        for (AgentStepCondition condition : conditions) {
            if (condition.phase() != phase) {
                continue;
            }
            AgentConditionCheck check = evaluate(condition, context);
            checks.add(check);
            if (!check.valid()) {
                action = mergeAction(action, condition.failureAction());
            }
        }
        return new AgentStepValidationResult(action == null, action, checks);
    }

    /**
     * 调用对应 Probe；缺少 Probe 时按失败关闭。
     *
     * @param condition 条件定义
     * @param context 校验上下文
     * @return 条件结果
     */
    private AgentConditionCheck evaluate(
            AgentStepCondition condition, AgentStepValidationContext context) {
        AgentConditionProbe probe = probes.get(condition.type());
        if (probe == null) {
            return new AgentConditionCheck(
                    condition.conditionId(), false, "unavailable",
                    "未注册条件 Probe: " + condition.type());
        }
        return probe.evaluate(condition, context);
    }

    /**
     * 合并失败动作，明确失败优先于重新规划。
     *
     * @param current 当前动作
     * @param next 新动作
     * @return 合并动作
     */
    private AgentConditionFailureAction mergeAction(
            AgentConditionFailureAction current, AgentConditionFailureAction next) {
        if (current == AgentConditionFailureAction.FAIL || next == AgentConditionFailureAction.FAIL) {
            return AgentConditionFailureAction.FAIL;
        }
        return AgentConditionFailureAction.REPLAN;
    }
}
