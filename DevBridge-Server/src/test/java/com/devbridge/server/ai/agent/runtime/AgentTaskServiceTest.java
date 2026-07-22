package com.devbridge.server.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.ai.agent.event.AgentEvent;
import com.devbridge.server.ai.agent.event.AgentEventRequest;
import com.devbridge.server.ai.agent.event.AgentEventSequencer;
import com.devbridge.server.ai.agent.event.AgentEventType;
import com.devbridge.server.ai.agent.store.InMemoryAgentTaskStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Agent Runtime 任务服务测试，验证任务创建、读取和基础输入边界。
 *
 * <p>by AI.Coding</p>
 */
class AgentTaskServiceTest {

    /**
     * 验证 Runtime 创建任务时由后端生成标识，并以 CREATED 状态写入 Store。
     */
    @Test
    void createTaskShouldPersistCreatedTaskWithBackendGeneratedId() {
        InMemoryAgentTaskStore store = new InMemoryAgentTaskStore();
        AgentTaskService service = new AgentTaskService(store, new AgentTaskStateMachine());

        AgentTask task = service.createTask(new CreateAgentTaskCommand("conversation-1", "查询当前设备状态"));

        assertThat(task.taskId()).isNotBlank();
        assertThat(task.conversationId()).isEqualTo("conversation-1");
        assertThat(task.goal()).isEqualTo("查询当前设备状态");
        assertThat(task.state()).isEqualTo(AgentTaskState.CREATED);
        assertThat(task.version()).isEqualTo(1L);
        assertThat(task.createdAt()).isEqualTo(task.updatedAt());
        assertThat(store.findById(task.taskId())).contains(task);
    }

    /**
     * 验证任务查询只依赖任务标识，不依赖前端聊天消息结构。
     */
    @Test
    void findTaskShouldReturnStoredTask() {
        AgentTaskService service = service();
        AgentTask created = service.createTask(new CreateAgentTaskCommand("conversation-2", "检查本机端口"));

        Optional<AgentTask> found = service.findTask(created.taskId());

        assertThat(found).contains(created);
    }

    /**
     * 验证查询不存在的任务时返回空结果，避免基础模块引入 HTTP 异常语义。
     */
    @Test
    void findTaskShouldReturnEmptyForUnknownId() {
        AgentTaskService service = service();

        Optional<AgentTask> found = service.findTask("unknown-task");

        assertThat(found).isEmpty();
    }

    /**
     * 验证空会话标识不能创建任务，避免生成无法关联历史聊天的孤立任务。
     */
    @Test
    void createTaskShouldRejectBlankConversationId() {
        AgentTaskService service = service();

        assertThatThrownBy(() -> service.createTask(new CreateAgentTaskCommand(" ", "查询设备")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话标识不能为空");
    }

    /**
     * 验证空任务目标不能进入 Runtime。
     */
    @Test
    void createTaskShouldRejectBlankGoal() {
        AgentTaskService service = service();

        assertThatThrownBy(() -> service.createTask(new CreateAgentTaskCommand("conversation-3", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务目标不能为空");
    }

    /**
     * 验证过长目标会在持久化前被拒绝，防止单请求无界占用内存。
     */
    @Test
    void createTaskShouldRejectOversizedGoal() {
        AgentTaskService service = service();

        assertThatThrownBy(() -> service.createTask(new CreateAgentTaskCommand("conversation-4", "a".repeat(16_385))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务目标不能超过");
    }

    /**
     * 验证 Store 不允许相同任务标识覆盖既有任务。
     */
    @Test
    void storeShouldRejectDuplicateTaskId() {
        InMemoryAgentTaskStore store = new InMemoryAgentTaskStore();
        AgentTaskService service = new AgentTaskService(store, new AgentTaskStateMachine());
        AgentTask task = service.createTask(new CreateAgentTaskCommand("conversation-5", "查询应用列表"));

        assertThatThrownBy(() -> store.save(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("任务已经存在");
    }

    /** 相同会话、幂等键和目标必须返回原任务，不创建第二个 Task。 */
    @Test
    void createTaskShouldReuseSameIdempotentRequest() {
        AgentTaskService service = service();
        CreateAgentTaskCommand command = new CreateAgentTaskCommand(
                "conversation-idempotent", "查询设备", "turn-1");

        AgentTask first = service.createTask(command);
        AgentTask second = service.createTask(command);

        assertThat(second).isEqualTo(first);
    }

    /** 相同幂等键不能覆盖不同目标。 */
    @Test
    void createTaskShouldRejectChangedIdempotentRequest() {
        AgentTaskService service = service();
        service.createTask(new CreateAgentTaskCommand(
                "conversation-idempotent", "查询设备", "turn-2"));

        assertThatThrownBy(() -> service.createTask(new CreateAgentTaskCommand(
                "conversation-idempotent", "卸载应用", "turn-2")))
                .isInstanceOf(AgentTaskIdempotencyException.class);
    }

    /**
     * 验证 Chat 主链路可以在同一任务上完成启动、等待确认、恢复和完成。
     */
    @Test
    void applicationServiceShouldKeepConfirmationContinuationInSameTask() {
        AgentTaskService taskService = service();
        RecordingEventSequencer events = new RecordingEventSequencer();
        AgentTaskApplicationService application = new AgentTaskApplicationService(
                taskService, events, new AgentTaskCancellationCoordinator());

        AgentTask running = application.startTask(new CreateAgentTaskCommand("conversation-main", "分析设备日志"));
        AgentTask waiting = application.waitForConfirmation(running.taskId());
        AgentTask resumed = application.resumeTask(running.taskId(), "conversation-main");
        AgentTask completed = application.completeTask(running.taskId());

        assertThat(running.state()).isEqualTo(AgentTaskState.RUNNING);
        assertThat(waiting.state()).isEqualTo(AgentTaskState.WAITING_CONFIRMATION);
        assertThat(resumed.taskId()).isEqualTo(running.taskId());
        assertThat(resumed.state()).isEqualTo(AgentTaskState.RUNNING);
        assertThat(completed.state()).isEqualTo(AgentTaskState.COMPLETED);
        assertThat(events.types).contains(
                AgentEventType.TASK_CREATED,
                AgentEventType.TASK_STATE_CHANGED,
                AgentEventType.TASK_RESUMED,
                AgentEventType.TASK_COMPLETED);
    }

    /**
     * 验证任务恢复必须绑定原会话，防止跨历史会话接管等待确认任务。
     */
    @Test
    void applicationServiceShouldRejectCrossConversationResume() {
        AgentTaskService taskService = service();
        AgentTaskApplicationService application = new AgentTaskApplicationService(
                taskService, new RecordingEventSequencer(), new AgentTaskCancellationCoordinator());
        AgentTask task = application.startTask(new CreateAgentTaskCommand("conversation-a", "执行敏感操作"));
        application.waitForConfirmation(task.taskId());

        assertThatThrownBy(() -> application.resumeTask(task.taskId(), "conversation-b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前会话不匹配");
    }

    /**
     * 创建使用确定状态机的测试服务。
     *
     * @return 任务服务
     */
    private AgentTaskService service() {
        return new AgentTaskService(new InMemoryAgentTaskStore(), new AgentTaskStateMachine());
    }

    /**
     * 记录事件类型的显式测试序列器，避免任务生命周期测试依赖文件系统。
     *
     * <p>by AI.Coding</p>
     */
    private static class RecordingEventSequencer extends AgentEventSequencer {

        private final List<AgentEventType> types = new ArrayList<>();

        /**
         * 创建无外部 Store 的记录器。
         */
        RecordingEventSequencer() {
            super(null, null);
        }

        /**
         * 记录事件类型，任务生命周期测试不需要构造完整持久事件。
         *
         * @param taskId 任务标识
         * @param request 事件请求
         * @return 不需要返回事件
         */
        @Override
        public AgentEvent publish(String taskId, AgentEventRequest request) {
            types.add(request.eventType());
            return null;
        }
    }
}
