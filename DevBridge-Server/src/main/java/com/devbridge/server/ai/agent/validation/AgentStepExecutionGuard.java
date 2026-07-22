package com.devbridge.server.ai.agent.validation;

import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.runtime.AgentTaskService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Agent 步骤执行守卫，在工具前后应用条件并推进任务状态。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AgentStepExecutionGuard {

    private final AgentTaskService taskService;
    private final AgentStepValidationService validationService;

    /**
     * 注入任务和条件校验服务。
     *
     * @param taskService 任务服务
     * @param validationService 条件校验服务
     */
    public AgentStepExecutionGuard(
            AgentTaskService taskService, AgentStepValidationService validationService) {
        this.taskService = taskService;
        this.validationService = validationService;
    }

    /**
     * 执行步骤前置条件校验。
     *
     * @param taskId 任务标识
     * @param stepId 步骤标识
     * @param conditions 条件列表
     * @param attributes 结构化上下文
     * @return 校验结果
     */
    public AgentStepValidationResult validateBefore(
            String taskId,
            String stepId,
            List<AgentStepCondition> conditions,
            Map<String, Object> attributes) {
        return validate(taskId, stepId, AgentStepValidationPhase.PRECONDITION, conditions, attributes);
    }

    /**
     * 执行步骤后置条件校验。
     *
     * @param taskId 任务标识
     * @param stepId 步骤标识
     * @param conditions 条件列表
     * @param attributes 结构化上下文
     * @return 校验结果
     */
    public AgentStepValidationResult validateAfter(
            String taskId,
            String stepId,
            List<AgentStepCondition> conditions,
            Map<String, Object> attributes) {
        return validate(taskId, stepId, AgentStepValidationPhase.POSTCONDITION, conditions, attributes);
    }

    /**
     * 执行校验并按失败策略推进任务。
     *
     * @param taskId 任务标识
     * @param stepId 步骤标识
     * @param phase 校验阶段
     * @param conditions 条件列表
     * @param attributes 结构化上下文
     * @return 校验结果
     */
    private AgentStepValidationResult validate(
            String taskId,
            String stepId,
            AgentStepValidationPhase phase,
            List<AgentStepCondition> conditions,
            Map<String, Object> attributes) {
        AgentTask task = taskService.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 任务不存在: " + taskId));
        if (task.state() != AgentTaskState.RUNNING) {
            throw new IllegalStateException("只有 RUNNING 任务可以执行步骤校验");
        }
        AgentStepValidationContext context = new AgentStepValidationContext(taskId, stepId, attributes);
        AgentStepValidationResult result = validationService.validate(phase, context, conditions);
        if (!result.valid()) {
            applyFailure(task, stepId, phase, result.failureAction());
        }
        return result;
    }

    /**
     * 按条件策略重新规划或失败。
     *
     * @param task 当前任务
     * @param stepId 步骤标识
     * @param phase 校验阶段
     * @param action 失败动作
     */
    private void applyFailure(
            AgentTask task,
            String stepId,
            AgentStepValidationPhase phase,
            AgentConditionFailureAction action) {
        String reason = phase + " 校验失败, stepId=" + stepId;
        if (action == AgentConditionFailureAction.REPLAN) {
            AgentTask paused = taskService.transitionTask(task.taskId(), AgentTaskState.PAUSED, reason);
            taskService.transitionTask(paused.taskId(), AgentTaskState.PLANNING, "外部状态变化，重新规划");
            return;
        }
        taskService.transitionTask(task.taskId(), AgentTaskState.FAILED, reason);
    }
}
