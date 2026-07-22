package com.devbridge.server.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.store.InMemoryAgentTaskStore;
import org.junit.jupiter.api.Test;

/**
 * Agent Task 状态机测试，覆盖主要业务路径和非法转换。
 *
 * <p>by AI.Coding</p>
 */
class AgentTaskStateMachineTest {

    /**
     * 验证正常任务只能按创建、规划、运行和完成顺序推进。
     */
    @Test
    void transitionShouldCompleteNormalTaskFlow() {
        AgentTaskService service = service();
        AgentTask task = service.createTask(new CreateAgentTaskCommand("conversation-1", "查询设备"));

        task = service.transitionTask(task.taskId(), AgentTaskState.PLANNING, "开始规划");
        task = service.transitionTask(task.taskId(), AgentTaskState.RUNNING, "开始执行");
        task = service.transitionTask(task.taskId(), AgentTaskState.COMPLETED, "目标已完成");

        assertThat(task.state()).isEqualTo(AgentTaskState.COMPLETED);
        assertThat(task.stateReason()).isEqualTo("目标已完成");
        assertThat(task.version()).isEqualTo(4L);
    }

    /**
     * 验证敏感操作等待确认后可以回到原任务继续运行。
     */
    @Test
    void transitionShouldResumeAfterConfirmation() {
        AgentTaskService service = service();
        AgentTask task = runningTask(service);

        task = service.transitionTask(task.taskId(), AgentTaskState.WAITING_CONFIRMATION, "等待用户确认");
        task = service.transitionTask(task.taskId(), AgentTaskState.RUNNING, "用户已确认");

        assertThat(task.state()).isEqualTo(AgentTaskState.RUNNING);
        assertThat(task.version()).isEqualTo(5L);
    }

    /**
     * 验证运行中的任务可以进入明确失败终态。
     */
    @Test
    void transitionShouldFailRunningTask() {
        AgentTaskService service = service();
        AgentTask task = runningTask(service);

        task = service.transitionTask(task.taskId(), AgentTaskState.FAILED, "工具执行失败");

        assertThat(task.state()).isEqualTo(AgentTaskState.FAILED);
        assertThat(task.stateReason()).isEqualTo("工具执行失败");
    }

    /**
     * 验证等待状态仍可由用户取消。
     */
    @Test
    void transitionShouldCancelWaitingTask() {
        AgentTaskService service = service();
        AgentTask task = runningTask(service);
        task = service.transitionTask(task.taskId(), AgentTaskState.WAITING_INPUT, "缺少包名");

        task = service.transitionTask(task.taskId(), AgentTaskState.CANCELED, "用户取消");

        assertThat(task.state()).isEqualTo(AgentTaskState.CANCELED);
    }

    /**
     * 验证 CREATED 不能跳过规划和执行直接完成。
     */
    @Test
    void transitionShouldRejectIllegalStateJump() {
        AgentTaskService service = service();
        AgentTask task = service.createTask(new CreateAgentTaskCommand("conversation-5", "查询应用"));

        assertThatThrownBy(() -> service.transitionTask(task.taskId(), AgentTaskState.COMPLETED, "直接完成"))
                .isInstanceOf(AgentTaskTransitionException.class)
                .hasMessageContaining("CREATED")
                .hasMessageContaining("COMPLETED");
    }

    /**
     * 验证终态不能恢复为运行态，重试必须创建新的 Turn 或任务。
     */
    @Test
    void transitionShouldRejectLeavingTerminalState() {
        AgentTaskService service = service();
        AgentTask task = runningTask(service);
        AgentTask completed = service.transitionTask(task.taskId(), AgentTaskState.COMPLETED, "已完成");

        assertThatThrownBy(() -> service.transitionTask(completed.taskId(), AgentTaskState.RUNNING, "再次运行"))
                .isInstanceOf(AgentTaskTransitionException.class)
                .hasMessageContaining("终态");
    }

    /**
     * 验证状态变化必须提供原因，保证后续事件和审计可解释。
     */
    @Test
    void transitionShouldRejectBlankReason() {
        AgentTaskService service = service();
        AgentTask task = service.createTask(new CreateAgentTaskCommand("conversation-7", "查询文件"));

        assertThatThrownBy(() -> service.transitionTask(task.taskId(), AgentTaskState.PLANNING, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("状态变化原因不能为空");
    }

    /**
     * 创建测试用 Runtime 服务。
     *
     * @return 任务服务
     */
    private AgentTaskService service() {
        InMemoryAgentTaskStore store = new InMemoryAgentTaskStore();
        return new AgentTaskService(store, new AgentTaskStateMachine());
    }

    /**
     * 创建已经进入 RUNNING 的任务，减少测试中的重复步骤。
     *
     * @param service 任务服务
     * @return 运行中任务
     */
    private AgentTask runningTask(AgentTaskService service) {
        AgentTask task = service.createTask(new CreateAgentTaskCommand("conversation-running", "执行任务"));
        task = service.transitionTask(task.taskId(), AgentTaskState.PLANNING, "开始规划");
        return service.transitionTask(task.taskId(), AgentTaskState.RUNNING, "开始执行");
    }
}
