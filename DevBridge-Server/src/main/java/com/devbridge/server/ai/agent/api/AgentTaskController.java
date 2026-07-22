package com.devbridge.server.ai.agent.api;

import com.devbridge.server.ai.agent.event.AgentEventStore;
import com.devbridge.server.ai.agent.event.AgentEventSubscriptionService;
import com.devbridge.server.ai.agent.checkpoint.AgentCheckpointService;
import com.devbridge.server.ai.agent.checkpoint.AgentTaskRecovery;
import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.runtime.AgentTaskApplicationService;
import com.devbridge.server.ai.agent.runtime.AgentTaskApplicationService.AgentFailureResult;
import com.devbridge.server.ai.agent.runtime.AgentTaskService;
import com.devbridge.server.ai.agent.runtime.CreateAgentTaskCommand;
import com.devbridge.server.ai.conversation.AiConversationService;
import com.devbridge.server.ai.tool.audit.ToolAuditStore;
import com.devbridge.server.ai.tool.audit.ToolAuditStore.AuditQuery;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.BusinessException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent Task 控制面 API，前端只负责创建、查询、订阅和取消任务。
 *
 * <p>by AI.Coding</p>
 */
@RestController
@RequestMapping("/api/ai/agent/tasks")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class AgentTaskController {

    private final AgentTaskApplicationService applicationService;
    private final AgentTaskService taskService;
    private final AgentEventStore eventStore;
    private final AgentEventSubscriptionService subscriptionService;
    private final AgentCheckpointService checkpointService;
    private final ToolAuditStore auditStore;
    private final DevBridgeProperties properties;
    private final AiConversationService conversationService;

    /**
     * 注入 Agent 控制面依赖。
     *
     * @param applicationService 任务应用服务
     * @param taskService 任务查询服务
     * @param eventStore 事件 Store
     * @param subscriptionService SSE 订阅服务
     * @param checkpointService 受保护任务结果解码服务
     * @param auditStore 工具审计存储
     * @param properties AI 功能开关
     */
    @Autowired
    public AgentTaskController(
            AgentTaskApplicationService applicationService,
            AgentTaskService taskService,
            AgentEventStore eventStore,
            AgentEventSubscriptionService subscriptionService,
            AgentCheckpointService checkpointService,
            ToolAuditStore auditStore,
            DevBridgeProperties properties,
            AiConversationService conversationService) {
        this.applicationService = applicationService;
        this.taskService = taskService;
        this.eventStore = eventStore;
        this.subscriptionService = subscriptionService;
        this.checkpointService = checkpointService;
        this.auditStore = auditStore;
        this.properties = properties;
        this.conversationService = conversationService;
    }

    /**
     * 创建兼容既有控制器测试的实例。
     *
     * @param applicationService 任务应用服务
     * @param taskService 任务查询服务
     * @param eventStore 事件 Store
     * @param subscriptionService 事件订阅服务
     */
    public AgentTaskController(
            AgentTaskApplicationService applicationService,
            AgentTaskService taskService,
            AgentEventStore eventStore,
            AgentEventSubscriptionService subscriptionService) {
        this(applicationService, taskService, eventStore, subscriptionService, null, null, null, null);
    }

    /**
     * 创建新的后端 Agent Task。
     *
     * @param request 创建请求
     * @return 任务响应
     */
    @PostMapping
    public AgentTaskResponse create(@RequestBody AgentTaskCreateRequest request) {
        requireRuntimeEnabled();
        AgentTask task = applicationService.createTask(
                new CreateAgentTaskCommand(
                        request.conversationId(), request.goal(), request.idempotencyKey()));
        return AgentTaskResponse.from(task);
    }

    /**
     * 查询任务当前状态。
     *
     * @param taskId 任务标识
     * @return 任务响应
     */
    @GetMapping("/{taskId}")
    public AgentTaskResponse task(@PathVariable String taskId) {
        applicationService.reconcileTerminalEvent(taskId);
        return AgentTaskResponse.from(requireTask(taskId));
    }

    /**
     * 查询任务最终回复，供 SSE 断开后恢复业务结果。
     *
     * @param taskId 任务标识
     * @return 当前状态和可用最终回复
     */
    @GetMapping("/{taskId}/result")
    public AgentTaskResultResponse result(@PathVariable String taskId) {
        applicationService.reconcileTerminalEvent(taskId);
        AgentTask task = requireTask(taskId);
        String answer = "";
        AgentFailureResult failure = null;
        if (checkpointService != null && task.protectedResult() != null) {
            if (task.state() == com.devbridge.server.ai.agent.model.AgentTaskState.FAILED) {
                failure = checkpointService.restore(task.protectedResult(), AgentFailureResult.class);
            } else if (task.state() == com.devbridge.server.ai.agent.model.AgentTaskState.COMPLETED) {
                answer = checkpointService.restore(task.protectedResult(), String.class);
            }
        }
        return new AgentTaskResultResponse(
                task.taskId(), task.state(), answer == null ? "" : answer, failure);
    }

    /**
     * 分页查询 Agent Task。
     *
     * @param page 从零开始的页码
     * @param size 每页数量
     * @return 任务分页
     */
    @GetMapping
    public AgentTaskPageResponse tasks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireRuntimeEnabled();
        return AgentTaskPageResponse.from(taskService.listTasks(page, size));
    }

    /**
     * 幂等取消任务。
     *
     * @param taskId 任务标识
     * @return 取消后的任务
     */
    @PostMapping("/{taskId}/cancel")
    public AgentTaskResponse cancel(@PathVariable String taskId) {
        requireTask(taskId);
        return AgentTaskResponse.from(applicationService.cancelTask(taskId));
    }

    /** 暂停运行中的 Agent Task，并停止当前模型、工具和进程。 */
    @PostMapping("/{taskId}/pause")
    public AgentTaskResponse pause(@PathVariable String taskId) {
        requireTask(taskId);
        return AgentTaskResponse.from(applicationService.pauseTask(taskId));
    }

    /** 恢复用户主动暂停的 Agent Task，并直接消费后端续跑流。 */
    @PostMapping(value = "/{taskId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(
            @PathVariable String taskId,
            @RequestBody AgentTaskResumeRequest request) {
        requireTask(taskId);
        if (checkpointService == null || conversationService == null) {
            throw new IllegalStateException("Agent 暂停续跑服务不可用");
        }
        AgentTaskApplicationService.TaskOperationResult resumed =
                applicationService.resumePausedTaskResult(taskId, request.conversationId());
        if (!resumed.changed()) {
            // 重复恢复只跟随已有执行流，终态后重放持久结果，不启动第二个 Provider。
            return conversationService.followTaskResult(taskId, () -> requireTask(taskId));
        }
        try {
            AgentTaskRecovery recovery = checkpointService.loadRecovery(taskId)
                    .orElseThrow(() -> new IllegalStateException("暂停任务缺少 Checkpoint"));
            return conversationService.continueAfterPause(recovery);
        } catch (RuntimeException ex) {
            // 恢复准备失败后不能遗留一个没有执行器的 RUNNING 任务。
            applicationService.failTask(taskId, "暂停任务续跑准备失败");
            throw ex;
        }
    }

    /** 提交与等待项绑定的补充输入，并由后端自动继续原任务。 */
    @PostMapping(value = "/{taskId}/input", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter submitInput(
            @PathVariable String taskId,
            @RequestBody AgentTaskInputRequest request) {
        requireTask(taskId);
        if (conversationService == null) {
            throw new IllegalStateException("Agent 输入续跑服务不可用");
        }
        AgentTaskApplicationService.AgentInputAcceptance accepted = applicationService.acceptInput(
                taskId, request.conversationId(), request.inputKey(), request.value());
        return conversationService.continueAfterInput(accepted);
    }

    /**
     * 按游标读取任务事件，供重连补发和调试使用。
     *
     * @param taskId 任务标识
     * @param after 最后已处理序号
     * @param limit 最大返回数量
     * @return 有序事件
     */
    @GetMapping("/{taskId}/events")
    public List<AgentEventResponse> events(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "200") int limit) {
        requireTask(taskId);
        applicationService.reconcileTerminalEvent(taskId);
        return eventStore.readAfter(taskId, after, limit).stream().map(AgentEventResponse::from).toList();
    }

    /**
     * 聚合任务事件和脱敏工具审计，供前端定位模型、RAG、工具、重试和错误。
     *
     * @param taskId 任务标识
     * @return 有界任务 Trace
     */
    @GetMapping("/{taskId}/trace")
    public AgentTraceResponse trace(
            @PathVariable String taskId,
            @RequestParam(required = false) Long after,
            @RequestParam(defaultValue = "1000") int limit) {
        AgentTask task = requireTask(taskId);
        if (properties != null && !properties.getAiFeatures().isTraceEnabled()) {
            throw new BusinessException(
                    "AI_TRACE_DISABLED", "Agent Trace 已关闭", HttpStatus.NOT_FOUND, "trace feature disabled");
        }
        int effectiveLimit = Math.max(1, Math.min(limit, 1000));
        long cursor = after == null
                ? Math.max(0L, eventStore.lastSequence(taskId) - effectiveLimit)
                : Math.max(0L, after);
        List<AgentEventResponse> events = eventStore.readAfter(taskId, cursor, effectiveLimit).stream()
                .map(AgentEventResponse::from).toList();
        // 审计记录没有事件序号游标，只在首个 Trace 页面返回，避免后续事件翻页重复整批审计。
        boolean firstPage = after == null || after <= 0L;
        List<ToolAuditStore.AuditEvent> audits = auditStore == null || !firstPage ? List.of()
                : auditStore.query(new AuditQuery(taskId, "", "", null, null, effectiveLimit));
        long modelCalls = events.stream().filter(value -> value.eventType().name().equals("MODEL_CALL_STARTED")).count();
        long toolCalls = events.stream().filter(value -> value.eventType().name().equals("TOOL_STARTED")).count();
        long errors = events.stream().filter(value -> value.eventType().name().contains("FAILED")
                || value.eventType().name().equals("ERROR_REPORTED")).count();
        Map<String, Object> model = events.stream()
                .filter(value -> value.eventType().name().equals("MODEL_CALL_COMPLETED"))
                .reduce((first, second) -> second).map(AgentEventResponse::payload).orElse(Map.of());
        Map<String, Object> rag = events.stream()
                .filter(value -> value.eventType().name().equals("MODEL_CALL_STARTED"))
                .findFirst().map(AgentEventResponse::payload).orElse(Map.of());
        return new AgentTraceResponse(
                task.taskId(), task.conversationId(), task.state().name(), task.createdAt(), task.updatedAt(),
                new TraceMetrics(modelCalls, toolCalls, errors, events.size(), audits.size()),
                model, rag, events, audits);
    }

    /**
     * 订阅任务事件流，支持查询参数和标准 Last-Event-ID 游标。
     *
     * @param taskId 任务标识
     * @param after 查询参数游标，可空
     * @param lastEventId SSE 最后事件标识，可空
     * @return SSE Emitter
     */
    @GetMapping(value = "/{taskId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable String taskId,
            @RequestParam(required = false) Long after,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        requireTask(taskId);
        applicationService.reconcileTerminalEvent(taskId);
        return subscriptionService.subscribe(taskId, resolveCursor(taskId, after, lastEventId));
    }

    /**
     * 查询任务并映射稳定 404 业务错误。
     *
     * @param taskId 任务标识
     * @return 任务快照
     */
    private AgentTask requireTask(String taskId) {
        requireRuntimeEnabled();
        return taskService.findTask(taskId).orElseThrow(() -> new BusinessException(
                "AGENT_TASK_NOT_FOUND", "Agent 任务不存在", HttpStatus.NOT_FOUND, "taskId=" + taskId));
    }

    /** Agent Runtime 总开关关闭时拒绝控制面请求，普通聊天由旧链路继续提供。 */
    private void requireRuntimeEnabled() {
        if (properties != null && !properties.getAiFeatures().isAgentRuntimeEnabled()) {
            throw new BusinessException(
                    "AI_AGENT_RUNTIME_DISABLED", "Agent Runtime 已关闭",
                    HttpStatus.SERVICE_UNAVAILABLE, "agent runtime feature disabled");
        }
    }

    /**
     * 合并查询参数和 Last-Event-ID 游标并校验一致性。
     *
     * @param taskId 任务标识
     * @param after 查询参数游标
     * @param lastEventId SSE 最后事件标识
     * @return 有效游标
     */
    private long resolveCursor(String taskId, Long after, String lastEventId) {
        Long headerCursor = parseLastEventId(taskId, lastEventId);
        if (after != null && headerCursor != null && !after.equals(headerCursor)) {
            throw new IllegalArgumentException("事件游标参数与 Last-Event-ID 不一致");
        }
        long cursor = after != null ? after : headerCursor == null ? 0L : headerCursor;
        if (cursor < 0) {
            throw new IllegalArgumentException("事件游标不能小于零");
        }
        return cursor;
    }

    /**
     * 解析 `{taskId}:{eventSequence}` 格式的 SSE 事件标识。
     *
     * @param taskId 当前任务标识
     * @param lastEventId SSE 事件标识
     * @return 事件序号，无标识时返回 null
     */
    private Long parseLastEventId(String taskId, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return null;
        }
        int separator = lastEventId.lastIndexOf(':');
        if (separator < 1 || !taskId.equals(lastEventId.substring(0, separator))) {
            throw new IllegalArgumentException("Last-Event-ID 与任务不匹配");
        }
        try {
            return Long.parseLong(lastEventId.substring(separator + 1));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Last-Event-ID 序号不合法");
        }
    }

    /**
     * Agent Task 最终回复查询响应。
     *
     * <p>by AI.Coding</p>
     *
     * @param taskId 任务标识
     * @param state 任务状态
     * @param answer 最终回复，尚未完成时为空
     */
    public record AgentTaskResultResponse(
            String taskId,
            com.devbridge.server.ai.agent.model.AgentTaskState state,
            String answer,
            AgentFailureResult failure) {
    }

    /** Agent Trace 聚合响应，不包含 Prompt、密钥和完整工具正文。by AI.Coding */
    public record AgentTraceResponse(
            String taskId,
            String conversationId,
            String state,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            TraceMetrics metrics,
            Map<String, Object> model,
            Map<String, Object> rag,
            List<AgentEventResponse> events,
            List<ToolAuditStore.AuditEvent> toolAudits) {
    }

    /** Agent Trace 核心计数。by AI.Coding */
    public record TraceMetrics(long modelCalls, long toolCalls, long errors, long events, long auditRecords) {
    }

    /** Agent Task 暂停恢复请求。by AI.Coding */
    public record AgentTaskResumeRequest(String conversationId) {
    }

    /** Agent Task 补充输入请求。by AI.Coding */
    public record AgentTaskInputRequest(String conversationId, String inputKey, String value) {
    }
}
