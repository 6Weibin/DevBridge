package com.devbridge.server.ai.agent.compensation;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.agent.event.AgentEventFileCodec;
import com.devbridge.server.ai.agent.event.AgentEventSequencer;
import com.devbridge.server.ai.agent.event.FileAgentEventStore;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.runtime.AgentTaskService;
import com.devbridge.server.ai.agent.runtime.AgentTaskStateMachine;
import com.devbridge.server.ai.agent.runtime.CreateAgentTaskCommand;
import com.devbridge.server.ai.agent.store.InMemoryAgentTaskStore;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agent 补偿服务测试，覆盖逆序、不可逆和失败隔离。
 *
 * <p>by AI.Coding</p>
 */
class AgentCompensationServiceTest {

    /**
     * 验证可补偿步骤严格按逆序执行。
     *
     * @param tempDir 临时事件目录
     */
    @Test
    void compensateShouldRunInReverseOrder(@TempDir Path tempDir) {
        List<String> order = new ArrayList<>();
        AgentCompensationHandler handler = recordingHandler(order, false);
        TestRuntime runtime = runtime(tempDir, List.of(handler));
        AgentTask task = runtime.taskService().createTask(
                new CreateAgentTaskCommand("conversation-compensation", "补偿任务"));
        List<AgentCompletedStep> steps = List.of(
                step("step-1", 1, false, action("action-1", false)),
                step("step-2", 2, false, action("action-2", false)));

        AgentCompensationReport report = runtime.service().compensate(task.taskId(), steps);

        assertThat(order).containsExactly("action-2", "action-1");
        assertThat(report.outcomes()).extracting(AgentCompensationOutcome::status)
                .containsExactly(AgentCompensationStatus.COMPENSATED, AgentCompensationStatus.COMPENSATED);
    }

    /**
     * 验证不可逆步骤只记录影响，不调用补偿处理器。
     *
     * @param tempDir 临时事件目录
     */
    @Test
    void compensateShouldReportIrreversibleImpact(@TempDir Path tempDir) {
        List<String> order = new ArrayList<>();
        TestRuntime runtime = runtime(tempDir, List.of(recordingHandler(order, false)));
        AgentTask task = runtime.taskService().createTask(
                new CreateAgentTaskCommand("conversation-impact", "不可逆任务"));

        AgentCompensationReport report = runtime.service().compensate(
                task.taskId(), List.of(step("step-delete", 1, true, action("delete", false))));

        assertThat(order).isEmpty();
        assertThat(report.irreversibleImpacts()).containsExactly("step-delete 已产生不可逆影响");
        assertThat(report.outcomes().get(0).status()).isEqualTo(AgentCompensationStatus.SKIPPED_IRREVERSIBLE);
    }

    /**
     * 验证需要确认的补偿动作不会直接执行。
     *
     * @param tempDir 临时事件目录
     */
    @Test
    void compensateShouldStopActionThatRequiresConfirmation(@TempDir Path tempDir) {
        List<String> order = new ArrayList<>();
        TestRuntime runtime = runtime(tempDir, List.of(recordingHandler(order, false)));
        AgentTask task = runtime.taskService().createTask(
                new CreateAgentTaskCommand("conversation-confirm-compensation", "确认补偿"));

        AgentCompensationReport report = runtime.service().compensate(
                task.taskId(), List.of(step("step-confirm", 1, false, action("confirm", true))));

        assertThat(order).isEmpty();
        assertThat(report.outcomes().get(0).status())
                .isEqualTo(AgentCompensationStatus.REQUIRES_CONFIRMATION);
    }

    /**
     * 验证一个补偿失败后仍继续处理更早的可补偿步骤。
     *
     * @param tempDir 临时事件目录
     */
    @Test
    void compensateShouldContinueAfterHandlerFailure(@TempDir Path tempDir) {
        List<String> order = new ArrayList<>();
        TestRuntime runtime = runtime(tempDir, List.of(recordingHandler(order, true)));
        AgentTask task = runtime.taskService().createTask(
                new CreateAgentTaskCommand("conversation-failed-compensation", "失败补偿"));
        List<AgentCompletedStep> steps = List.of(
                step("step-ok", 1, false, action("ok", false)),
                step("step-fail", 2, false, action("fail", false)));

        AgentCompensationReport report = runtime.service().compensate(task.taskId(), steps);

        assertThat(order).containsExactly("fail", "ok");
        assertThat(report.outcomes()).extracting(AgentCompensationOutcome::status)
                .containsExactly(AgentCompensationStatus.FAILED, AgentCompensationStatus.COMPENSATED);
    }

    /**
     * 创建记录型补偿处理器。
     *
     * @param order 调用顺序
     * @param failOnNamedAction 是否在 fail 动作抛出异常
     * @return 补偿处理器
     */
    private AgentCompensationHandler recordingHandler(List<String> order, boolean failOnNamedAction) {
        return new AgentCompensationHandler() {
            /**
             * 返回处理器类型。
             *
             * @return 处理器类型
             */
            @Override
            public String type() {
                return "test";
            }

            /**
             * 记录补偿顺序并按测试配置失败。
             *
             * @param action 补偿动作
             * @param context 补偿上下文
             */
            @Override
            public void compensate(AgentCompensationAction action, AgentCompensationContext context) {
                order.add(action.actionId());
                if (failOnNamedAction && "fail".equals(action.actionId())) {
                    throw new IllegalStateException("补偿失败");
                }
            }
        };
    }

    /**
     * 创建已完成步骤。
     *
     * @param stepId 步骤标识
     * @param ordinal 执行顺序
     * @param irreversible 是否不可逆
     * @param action 补偿动作
     * @return 已完成步骤
     */
    private AgentCompletedStep step(
            String stepId, int ordinal, boolean irreversible, AgentCompensationAction action) {
        return new AgentCompletedStep(
                stepId, ordinal, action, irreversible, stepId + " 已产生不可逆影响");
    }

    /**
     * 创建补偿动作。
     *
     * @param actionId 动作标识
     * @param requiresConfirmation 是否需要确认
     * @return 补偿动作
     */
    private AgentCompensationAction action(String actionId, boolean requiresConfirmation) {
        return new AgentCompensationAction(
                actionId, "test", Map.of(), requiresConfirmation, "测试补偿");
    }

    /**
     * 创建测试 Runtime。
     *
     * @param root 临时事件目录
     * @param handlers 补偿处理器
     * @return 测试 Runtime
     */
    private TestRuntime runtime(Path root, List<AgentCompensationHandler> handlers) {
        AgentTaskService taskService = new AgentTaskService(
                new InMemoryAgentTaskStore(), new AgentTaskStateMachine());
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiAgentDataRoot(root.toString());
        FileAgentEventStore eventStore = new FileAgentEventStore(
                properties, new AgentEventFileCodec(new ObjectMapper().findAndRegisterModules()));
        AgentEventSequencer sequencer = new AgentEventSequencer(eventStore, event -> { });
        return new TestRuntime(
                taskService, new AgentCompensationService(taskService, sequencer, handlers));
    }

    /**
     * 测试 Runtime 依赖集合。
     *
     * @param taskService 任务服务
     * @param service 补偿服务
     */
    private record TestRuntime(
            AgentTaskService taskService, AgentCompensationService service) {
    }
}
