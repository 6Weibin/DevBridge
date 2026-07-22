package com.devbridge.server.ai.mcp.audit;

import com.devbridge.server.ai.mcp.model.AdbToolAuditEvent;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ADB MCP 工具审计记录器，只记录脱敏摘要，避免输出完整日志和凭证。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AdbToolAuditRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdbToolAuditRecorder.class);

    private final SensitiveDataMasker masker;

    /**
     * 注入脱敏工具，确保审计日志不记录完整设备序列号和敏感文本。
     *
     * @param masker 脱敏工具
     */
    public AdbToolAuditRecorder(SensitiveDataMasker masker) {
        this.masker = masker;
    }

    /**
     * 记录工具调用摘要。
     *
     * @param event 审计事件
     */
    public void record(AdbToolAuditEvent event) {
        if (event.success()) {
            LOGGER.info(
                    "ADB MCP 工具执行成功 tool={} device={} risk={} confirmation={} elapsed={}ms exitCode={} args={}",
                    event.toolName(),
                    masker.maskSerial(event.deviceSerialMasked()),
                    event.riskLevel(),
                    event.confirmationStatus(),
                    event.durationMillis(),
                    event.exitCode(),
                    masker.maskText(event.adbArgsSummary()));
            return;
        }
        LOGGER.warn(
                "ADB MCP 工具执行失败 tool={} device={} risk={} confirmation={} elapsed={}ms exitCode={} error={} args={}",
                event.toolName(),
                masker.maskSerial(event.deviceSerialMasked()),
                event.riskLevel(),
                event.confirmationStatus(),
                event.durationMillis(),
                event.exitCode(),
                masker.maskText(event.errorSummary()),
                masker.maskText(event.adbArgsSummary()));
    }
}
