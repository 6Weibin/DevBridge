package com.devbridge.server.ai.agent.compensation;

/**
 * Agent 步骤补偿结果状态。
 *
 * <p>by AI.Coding</p>
 */
public enum AgentCompensationStatus {
    COMPENSATED,
    NO_ACTION,
    SKIPPED_IRREVERSIBLE,
    REQUIRES_CONFIRMATION,
    FAILED
}
