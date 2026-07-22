package com.devbridge.server.ai.agent.api;

import com.devbridge.server.ai.agent.confirmation.AgentConfirmationCoordinator;
import com.devbridge.server.ai.agent.confirmation.AgentConfirmationCoordinator.AgentConfirmationApproval;
import com.devbridge.server.ai.agent.confirmation.AgentRuntimeContinuationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.beans.factory.annotation.Value;

/**
 * Agent 敏感操作确认 API，批准后由后端自动继续原任务。
 *
 * <p>by AI.Coding</p>
 */
@RestController
@RequestMapping("/api/ai/agent/tasks/{taskId}/confirmations")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class AgentConfirmationController {

    private final AgentConfirmationCoordinator coordinator;
    private final AgentRuntimeContinuationService continuation;

    @Value("${devbridge.ai-features.agent-runtime-enabled:true}")
    private boolean agentRuntimeEnabled = true;

    /**
     * 注入确认协调器。
     *
     * @param coordinator 确认协调器
     * @param continuation 后端续跑入口
     */
    public AgentConfirmationController(
            AgentConfirmationCoordinator coordinator,
            AgentRuntimeContinuationService continuation) {
        this.coordinator = coordinator;
        this.continuation = continuation;
    }

    /**
     * 批准敏感操作，后端自动恢复原任务。
     *
     * @param taskId 任务标识
     * @param confirmationId 确认标识
     * @return 后端自动续跑事件流
     */
    @PostMapping(value = "/{confirmationId}/approve", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter approve(
            @PathVariable String taskId,
            @PathVariable String confirmationId,
            @RequestHeader("X-Agent-Conversation-Id") String conversationId,
            @RequestHeader("X-Agent-Confirmation-Token") String approvalToken) {
        requireRuntimeEnabled();
        AgentConfirmationApproval approval = coordinator.approve(
                taskId, confirmationId, conversationId, approvalToken);
        return continuation.continueTask(approval);
    }

    /**
     * 拒绝敏感操作并停止依赖步骤。
     *
     * @param taskId 任务标识
     * @param confirmationId 确认标识
     * @param request 决策请求
     * @return 已拒绝确认
     */
    @PostMapping("/{confirmationId}/reject")
    public AgentConfirmationResponse reject(
            @PathVariable String taskId,
            @PathVariable String confirmationId,
            @RequestHeader("X-Agent-Conversation-Id") String conversationId,
            @RequestHeader("X-Agent-Confirmation-Token") String approvalToken,
            @RequestBody(required = false) AgentConfirmationDecisionRequest request) {
        String reason = request == null ? null : request.reason();
        requireRuntimeEnabled();
        return AgentConfirmationResponse.from(coordinator.reject(
                taskId, confirmationId, conversationId, approvalToken, reason));
    }

    /** Agent Runtime 关闭时禁止继续旧任务。 */
    private void requireRuntimeEnabled() {
        if (!agentRuntimeEnabled) {
            throw new IllegalStateException("Agent Runtime 已关闭");
        }
    }
}
