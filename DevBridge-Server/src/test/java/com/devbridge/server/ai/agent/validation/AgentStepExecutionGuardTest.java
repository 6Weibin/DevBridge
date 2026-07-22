package com.devbridge.server.ai.agent.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.runtime.AgentTaskService;
import com.devbridge.server.ai.agent.runtime.AgentTaskStateMachine;
import com.devbridge.server.ai.agent.runtime.CreateAgentTaskCommand;
import com.devbridge.server.ai.agent.store.InMemoryAgentTaskStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agent 步骤执行守卫测试，覆盖前后置条件和失败动作。
 *
 * <p>by AI.Coding</p>
 */
class AgentStepExecutionGuardTest {

    /**
     * 验证存在的授权路径通过前置校验，任务保持运行状态。
     *
     * @param tempDir 临时目录
     * @throws Exception 文件创建失败时抛出
     */
    @Test
    void preconditionsShouldPassForExistingPath(@TempDir Path tempDir) throws Exception {
        Path file = Files.createFile(tempDir.resolve("artifact.txt"));
        TestRuntime runtime = runtime(List.of(new PathExistsConditionProbe()));
        AgentTask task = runningTask(runtime.taskService());
        AgentStepCondition condition = condition(
                AgentStepConditionType.PATH_EXISTS, file.toString(),
                AgentStepValidationPhase.PRECONDITION, AgentConditionFailureAction.FAIL);

        AgentStepValidationResult result = runtime.guard().validateBefore(
                task.taskId(), "step-path", List.of(condition), Map.of());

        assertThat(result.valid()).isTrue();
        assertThat(runtime.taskService().findTask(task.taskId()).orElseThrow().state())
                .isEqualTo(AgentTaskState.RUNNING);
    }

    /**
     * 验证可重新规划条件失败时通过 PAUSED 合法转换进入 PLANNING。
     */
    @Test
    void preconditionFailureShouldReplanTask() {
        TestRuntime runtime = runtime(List.of(alwaysFailingProbe()));
        AgentTask task = runningTask(runtime.taskService());
        AgentStepCondition condition = condition(
                AgentStepConditionType.CUSTOM, "resource", AgentStepValidationPhase.PRECONDITION,
                AgentConditionFailureAction.REPLAN);

        AgentStepValidationResult result = runtime.guard().validateBefore(
                task.taskId(), "step-resource", List.of(condition), Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(runtime.taskService().findTask(task.taskId()).orElseThrow().state())
                .isEqualTo(AgentTaskState.PLANNING);
    }

    /**
     * 验证后置条件失败时任务明确失败，不能把步骤当作成功。
     */
    @Test
    void postconditionFailureShouldFailTask() {
        TestRuntime runtime = runtime(List.of(alwaysFailingProbe()));
        AgentTask task = runningTask(runtime.taskService());
        AgentStepCondition condition = condition(
                AgentStepConditionType.CUSTOM, "result", AgentStepValidationPhase.POSTCONDITION,
                AgentConditionFailureAction.FAIL);

        AgentStepValidationResult result = runtime.guard().validateAfter(
                task.taskId(), "step-result", List.of(condition), Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(runtime.taskService().findTask(task.taskId()).orElseThrow().state())
                .isEqualTo(AgentTaskState.FAILED);
    }

    /**
     * 验证没有对应 Probe 的条件按失败处理，不能静默跳过。
     */
    @Test
    void missingProbeShouldFailClosed() {
        TestRuntime runtime = runtime(List.of());
        AgentTask task = runningTask(runtime.taskService());
        AgentStepCondition condition = condition(
                AgentStepConditionType.DEVICE_CONNECTED, "serial", AgentStepValidationPhase.PRECONDITION,
                AgentConditionFailureAction.FAIL);

        AgentStepValidationResult result = runtime.guard().validateBefore(
                task.taskId(), "step-device", List.of(condition), Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.checks().get(0).message()).contains("Probe");
    }

    /**
     * 创建始终失败的测试 Probe。
     *
     * @return 测试 Probe
     */
    private AgentConditionProbe alwaysFailingProbe() {
        return new AgentConditionProbe() {
            /**
             * 返回自定义条件类型。
             *
             * @return 条件类型
             */
            @Override
            public AgentStepConditionType type() {
                return AgentStepConditionType.CUSTOM;
            }

            /**
             * 返回确定失败结果。
             *
             * @param condition 条件定义
             * @param context 校验上下文
             * @return 失败结果
             */
            @Override
            public AgentConditionCheck evaluate(
                    AgentStepCondition condition, AgentStepValidationContext context) {
                return new AgentConditionCheck(condition.conditionId(), false, "missing", "条件不满足");
            }
        };
    }

    /**
     * 创建条件。
     *
     * @param type 条件类型
     * @param target 目标值
     * @param phase 校验阶段
     * @param action 失败动作
     * @return 条件定义
     */
    private AgentStepCondition condition(
            AgentStepConditionType type,
            String target,
            AgentStepValidationPhase phase,
            AgentConditionFailureAction action) {
        return new AgentStepCondition("condition-1", type, target, "true", phase, action, "测试条件");
    }

    /**
     * 创建测试 Runtime。
     *
     * @param probes 条件 Probe
     * @return 测试 Runtime
     */
    private TestRuntime runtime(List<AgentConditionProbe> probes) {
        AgentTaskService taskService = new AgentTaskService(
                new InMemoryAgentTaskStore(), new AgentTaskStateMachine());
        AgentStepValidationService validationService = new AgentStepValidationService(probes);
        return new TestRuntime(taskService, new AgentStepExecutionGuard(taskService, validationService));
    }

    /**
     * 创建运行中任务。
     *
     * @param service 任务服务
     * @return 运行中任务
     */
    private AgentTask runningTask(AgentTaskService service) {
        AgentTask task = service.createTask(new CreateAgentTaskCommand("conversation-validation", "校验任务"));
        task = service.transitionTask(task.taskId(), AgentTaskState.PLANNING, "开始规划");
        return service.transitionTask(task.taskId(), AgentTaskState.RUNNING, "开始执行");
    }

    /**
     * 测试 Runtime 依赖集合。
     *
     * @param taskService 任务服务
     * @param guard 执行守卫
     */
    private record TestRuntime(AgentTaskService taskService, AgentStepExecutionGuard guard) {
    }
}
