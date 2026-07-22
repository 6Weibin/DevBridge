package com.devbridge.server.ai.agent.runtime;

import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.config.DevBridgeProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Agent 等待输入超时扫描器，避免任务长期占用恢复状态和本地磁盘。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AgentWaitingInputTimeoutService {

    private final AgentTaskService taskService;
    private final AgentTaskApplicationService applicationService;
    private final DevBridgeProperties properties;

    /** 注入任务服务和超时配置。 */
    public AgentWaitingInputTimeoutService(
            AgentTaskService taskService,
            AgentTaskApplicationService applicationService,
            DevBridgeProperties properties) {
        this.taskService = taskService;
        this.applicationService = applicationService;
        this.properties = properties;
    }

    /** 每分钟扫描等待输入任务，达到配置时长后明确失败。 */
    @Scheduled(fixedDelayString = "${devbridge.ai-agent-input-timeout-scan-delay:60000}")
    public void expireWaitingTasks() {
        Instant deadline = Instant.now().minus(properties.getAiAgentInputTimeout());
        List<ExpiredWaitingTask> expiredTasks = new ArrayList<>();
        for (int page = 0; ; page++) {
            var tasks = taskService.listTasks(page, 100);
            tasks.items().stream()
                    .filter(task -> task.state() == AgentTaskState.WAITING_INPUT)
                    .filter(task -> task.updatedAt().isBefore(deadline))
                    .map(task -> new ExpiredWaitingTask(task.taskId(), task.version()))
                    .forEach(expiredTasks::add);
            if (tasks.items().size() < 100) {
                break;
            }
        }
        // 扫描结束后再修改任务，避免更新时间重排分页结果而漏掉后续等待项。
        expiredTasks.forEach(task -> applicationService.expireWaitingInput(
                task.taskId(), task.version()));
    }

    /** 等待输入扫描快照，版本用于阻止过期扫描覆盖用户刚提交的输入。 */
    private record ExpiredWaitingTask(String taskId, long version) {
    }
}
