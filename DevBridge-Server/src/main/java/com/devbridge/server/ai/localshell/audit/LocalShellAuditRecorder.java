package com.devbridge.server.ai.localshell.audit;

import com.devbridge.server.ai.localshell.model.LocalShellAuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Local Shell 工具审计记录器，只记录命令摘要和执行结果，不记录完整输出。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class LocalShellAuditRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalShellAuditRecorder.class);

    /**
     * 记录 Local Shell 工具调用审计摘要。
     *
     * @param event 审计事件
     */
    public void record(LocalShellAuditEvent event) {
        LOGGER.info(
                "Local Shell MCP audit tool={}, workdir={}, command={}, risk={}, confirmation={}, durationMs={}, exitCode={}, success={}, error={}",
                event.toolName(),
                event.workingDirectory(),
                event.commandSummary(),
                event.riskLevel(),
                event.confirmationStatus(),
                event.durationMillis(),
                event.exitCode(),
                event.success(),
                event.error());
    }
}
