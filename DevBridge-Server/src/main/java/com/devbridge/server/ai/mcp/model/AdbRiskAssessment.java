package com.devbridge.server.ai.mcp.model;

import java.util.List;

/**
 * ADB 命令风险识别结果，说明是否需要确认以及影响范围。
 *
 * <p>by AI.Coding</p>
 *
 * @param riskLevel 风险级别
 * @param confirmationRequired 是否需要确认
 * @param reasons 风险原因
 * @param impact 影响范围
 */
public record AdbRiskAssessment(
        AdbRiskLevel riskLevel,
        boolean confirmationRequired,
        List<String> reasons,
        String impact) {

    /**
     * 创建低风险评估，表示可直接执行。
     *
     * @return 低风险评估
     */
    public static AdbRiskAssessment low() {
        return new AdbRiskAssessment(AdbRiskLevel.LOW, false, List.of(), "只读或低影响 ADB 操作");
    }

    /**
     * 创建高风险评估，默认需要用户确认。
     *
     * @param reasons 风险原因
     * @param impact 影响范围
     * @return 高风险评估
     */
    public static AdbRiskAssessment high(List<String> reasons, String impact) {
        return new AdbRiskAssessment(AdbRiskLevel.HIGH, true, reasons, impact);
    }
}
