package com.devbridge.server.ai.agent.compensation;

import com.devbridge.server.ai.agent.event.AgentEventContext;
import com.devbridge.server.ai.agent.event.AgentEventRequest;
import com.devbridge.server.ai.agent.event.AgentEventScope;
import com.devbridge.server.ai.agent.event.AgentEventSequencer;
import com.devbridge.server.ai.agent.event.AgentEventType;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.runtime.AgentTaskService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Agent 补偿服务，按已完成步骤逆序执行领域补偿并发布结果事件。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AgentCompensationService {

    private final AgentTaskService taskService;
    private final AgentEventSequencer eventSequencer;
    private final Map<String, AgentCompensationHandler> handlers = new HashMap<>();

    /**
     * 注入任务、事件和领域补偿处理器。
     *
     * @param taskService 任务服务
     * @param eventSequencer 事件序列器
     * @param availableHandlers 补偿处理器
     */
    public AgentCompensationService(
            AgentTaskService taskService,
            AgentEventSequencer eventSequencer,
            List<AgentCompensationHandler> availableHandlers) {
        this.taskService = taskService;
        this.eventSequencer = eventSequencer;
        for (AgentCompensationHandler handler : availableHandlers) {
            if (handlers.putIfAbsent(handler.type(), handler) != null) {
                throw new IllegalStateException("重复的补偿处理器: " + handler.type());
            }
        }
    }

    /**
     * 按原执行顺序逆序补偿已完成步骤。
     *
     * @param taskId 任务标识
     * @param completedSteps 已完成步骤
     * @return 补偿报告
     */
    public AgentCompensationReport compensate(
            String taskId, List<AgentCompletedStep> completedSteps) {
        AgentTask task = taskService.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 任务不存在: " + taskId));
        List<AgentCompletedStep> reversed = completedSteps.stream()
                .sorted(Comparator.comparingInt(AgentCompletedStep::ordinal).reversed())
                .toList();
        List<AgentCompensationOutcome> outcomes = new ArrayList<>();
        List<String> irreversibleImpacts = new ArrayList<>();
        for (AgentCompletedStep step : reversed) {
            AgentCompensationOutcome outcome = compensateStep(task, step, irreversibleImpacts);
            outcomes.add(outcome);
            publish(task, outcome);
        }
        boolean successful = outcomes.stream().noneMatch(outcome ->
                outcome.status() == AgentCompensationStatus.FAILED
                        || outcome.status() == AgentCompensationStatus.REQUIRES_CONFIRMATION);
        return new AgentCompensationReport(successful, outcomes, irreversibleImpacts);
    }

    /**
     * 补偿单个步骤并把异常转成结构化结果。
     *
     * @param task 任务快照
     * @param step 已完成步骤
     * @param irreversibleImpacts 不可逆影响集合
     * @return 步骤补偿结果
     */
    private AgentCompensationOutcome compensateStep(
            AgentTask task,
            AgentCompletedStep step,
            List<String> irreversibleImpacts) {
        if (step.irreversible()) {
            irreversibleImpacts.add(step.impactSummary());
            return outcome(step, AgentCompensationStatus.SKIPPED_IRREVERSIBLE, step.impactSummary());
        }
        AgentCompensationAction action = step.compensation();
        if (action == null) {
            return outcome(step, AgentCompensationStatus.NO_ACTION, "步骤未配置补偿动作");
        }
        if (action.requiresConfirmation()) {
            return outcome(step, AgentCompensationStatus.REQUIRES_CONFIRMATION, "补偿动作需要用户确认");
        }
        AgentCompensationHandler handler = handlers.get(action.handlerType());
        if (handler == null) {
            return outcome(step, AgentCompensationStatus.FAILED, "未找到补偿处理器");
        }
        try {
            handler.compensate(action, new AgentCompensationContext(task.taskId(), step.stepId()));
            return outcome(step, AgentCompensationStatus.COMPENSATED, "补偿执行成功");
        } catch (RuntimeException ex) {
            return outcome(step, AgentCompensationStatus.FAILED, safeMessage(ex));
        }
    }

    /**
     * 构造步骤补偿结果。
     *
     * @param step 已完成步骤
     * @param status 结果状态
     * @param message 结果说明
     * @return 补偿结果
     */
    private AgentCompensationOutcome outcome(
            AgentCompletedStep step, AgentCompensationStatus status, String message) {
        return new AgentCompensationOutcome(step.stepId(), status, message);
    }

    /**
     * 发布步骤补偿结果事件。
     *
     * @param task 任务快照
     * @param outcome 补偿结果
     */
    private void publish(AgentTask task, AgentCompensationOutcome outcome) {
        AgentEventContext context = new AgentEventContext(
                task.conversationId(), null, outcome.stepId(), null, null, null, task.version());
        eventSequencer.publish(task.taskId(), new AgentEventRequest(
                AgentEventType.STEP_COMPENSATED,
                AgentEventScope.STEP,
                context,
                Map.of("status", outcome.status().name(), "message", outcome.message()),
                Instant.now(),
                "compensation-service"));
    }

    /**
     * 返回不含堆栈和敏感参数的异常摘要。
     *
     * @param ex 补偿异常
     * @return 安全摘要
     */
    private String safeMessage(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
