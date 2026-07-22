package com.devbridge.server.ai.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devbridge.server.ai.agent.checkpoint.AgentCheckpoint;
import com.devbridge.server.ai.agent.checkpoint.AgentRecoveryState;
import com.devbridge.server.ai.agent.checkpoint.AgentTaskRecovery;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmation;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationBinding;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationCoordinator;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationCoordinator.AgentConfirmationApproval;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationRiskLevel;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationStatus;
import com.devbridge.server.ai.agent.confirmation.AgentRuntimeContinuationService;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.api.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 确认 API 测试，验证批准和拒绝委派给后端协调器。
 *
 * <p>by AI.Coding</p>
 */
class AgentConfirmationControllerTest {

    /**
     * 验证批准接口调用自动恢复协调器。
     *
     * @throws Exception API 调用失败时抛出
     */
    @Test
    void approveShouldDelegateToCoordinator() throws Exception {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        RecordingContinuation continuation = new RecordingContinuation();
        MockMvc mockMvc = mockMvc(coordinator, continuation);

        mockMvc.perform(post("/api/ai/agent/tasks/task-1/confirmations/confirmation-1/approve")
                        .header("X-Agent-Conversation-Id", "conversation-1")
                        .header("X-Agent-Confirmation-Token", "signed-token"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        assertThat(coordinator.approvedTaskId).isEqualTo("task-1");
        assertThat(coordinator.approvedConfirmationId).isEqualTo("confirmation-1");
        assertThat(continuation.taskId).isEqualTo("task-1");
    }

    /**
     * 验证拒绝接口传递用户原因。
     *
     * @throws Exception API 调用失败时抛出
     */
    @Test
    void rejectShouldDelegateReasonToCoordinator() throws Exception {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        MockMvc mockMvc = mockMvc(coordinator, new RecordingContinuation());

        mockMvc.perform(post("/api/ai/agent/tasks/task-1/confirmations/confirmation-1/reject")
                        .header("X-Agent-Conversation-Id", "conversation-1")
                        .header("X-Agent-Confirmation-Token", "signed-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"不允许卸载\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(coordinator.rejectedTaskId).isEqualTo("task-1");
        assertThat(coordinator.rejectedConfirmationId).isEqualTo("confirmation-1");
        assertThat(coordinator.rejectedReason).isEqualTo("不允许卸载");
    }

    /**
     * 创建 MockMvc。
     *
     * @param coordinator 确认协调器
     * @param continuation 续跑入口
     * @return MockMvc
     */
    private MockMvc mockMvc(
            AgentConfirmationCoordinator coordinator,
            AgentRuntimeContinuationService continuation) {
        return MockMvcBuilders.standaloneSetup(
                        new AgentConfirmationController(coordinator, continuation))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * 不依赖字节码代理的确认协调器测试替身。
     *
     * <p>by AI.Coding</p>
     */
    private static final class RecordingCoordinator extends AgentConfirmationCoordinator {

        private String approvedTaskId;
        private String approvedConfirmationId;
        private String rejectedTaskId;
        private String rejectedConfirmationId;
        private String rejectedReason;

        /**
         * 创建不执行真实文件和状态操作的测试替身。
         */
        private RecordingCoordinator() {
            super(null, null, null, null, null);
        }

        /**
         * 记录批准参数并返回接受状态。
         *
         * @param taskId 任务标识
         * @param confirmationId 确认标识
         * @return 接受确认和恢复上下文
         */
        @Override
        public AgentConfirmationApproval approve(String taskId, String confirmationId) {
            this.approvedTaskId = taskId;
            this.approvedConfirmationId = confirmationId;
            AgentConfirmation confirmation = sampleConfirmation(AgentConfirmationStatus.ACCEPTED);
            Instant now = Instant.parse("2026-07-14T00:00:00Z");
            AgentTask task = new AgentTask(
                    "task-1", "conversation-1", "测试任务", AgentTaskState.RUNNING,
                    "用户确认后继续", 2, now, now);
            AgentCheckpoint checkpoint = new AgentCheckpoint(
                    "checkpoint-1", "task-1", 2, 3, AgentTaskState.RUNNING,
                    new AgentRecoveryState(null, List.of(), Map.of(), null, null), now);
            return new AgentConfirmationApproval(
                    confirmation, new AgentTaskRecovery(task, checkpoint), null);
        }

        /** 记录带授权绑定的批准请求。 */
        @Override
        public AgentConfirmationApproval approve(
                String taskId, String confirmationId, String conversationId, String approvalToken) {
            return approve(taskId, confirmationId);
        }

        /**
         * 记录拒绝参数并返回拒绝状态。
         *
         * @param taskId 任务标识
         * @param confirmationId 确认标识
         * @param reason 拒绝原因
         * @return 拒绝确认
         */
        @Override
        public AgentConfirmation reject(String taskId, String confirmationId, String reason) {
            this.rejectedTaskId = taskId;
            this.rejectedConfirmationId = confirmationId;
            this.rejectedReason = reason;
            return sampleConfirmation(AgentConfirmationStatus.REJECTED);
        }

        /** 记录带授权绑定的拒绝请求。 */
        @Override
        public AgentConfirmation reject(
                String taskId, String confirmationId, String conversationId,
                String approvalToken, String reason) {
            return reject(taskId, confirmationId, reason);
        }

        /**
         * 创建确认结果。
         *
         * @param status 确认状态
         * @return 确认记录
         */
        private AgentConfirmation sampleConfirmation(AgentConfirmationStatus status) {
            Instant now = Instant.parse("2026-07-14T00:00:00Z");
            AgentConfirmationBinding binding = new AgentConfirmationBinding(
                    "step", "tool-call", "app.uninstall", "sha256:test",
                    AgentConfirmationRiskLevel.MEDIUM, "卸载应用");
            return new AgentConfirmation(
                    "confirmation-1", "task-1", binding, status, now,
                    now.plusSeconds(120), now, "测试决策");
        }
    }

    /**
     * 记录批准后续跑任务的显式测试替身。
     *
     * <p>by AI.Coding</p>
     */
    private static final class RecordingContinuation extends AgentRuntimeContinuationService {

        private String taskId;

        /**
         * 创建不调用真实 Provider 的续跑替身。
         */
        private RecordingContinuation() {
            super(null, null);
        }

        /**
         * 记录恢复任务并返回空测试流。
         *
         * @param approval 已验证批准结果
         * @return 测试流
         */
        @Override
        public SseEmitter continueTask(AgentConfirmationApproval approval) {
            this.taskId = approval.recovery().task().taskId();
            return new SseEmitter();
        }
    }
}
