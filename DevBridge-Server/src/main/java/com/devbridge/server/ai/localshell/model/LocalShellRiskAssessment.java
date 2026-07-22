package com.devbridge.server.ai.localshell.model;

import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import java.util.List;

/**
 * Local Shell 命令风险评估结果。
 *
 * <p>by AI.Coding</p>
 *
 * @param riskLevel 风险级别
 * @param confirmationRequired 是否需要确认
 * @param denied 是否直接拒绝
 * @param reasons 风险原因
 * @param impact 影响范围
 */
public record LocalShellRiskAssessment(
        AdbRiskLevel riskLevel,
        boolean confirmationRequired,
        boolean denied,
        List<String> reasons,
        String impact) {

    /**
     * 创建低风险评估。
     *
     * @return 低风险评估
     */
    public static LocalShellRiskAssessment low() {
        return new LocalShellRiskAssessment(AdbRiskLevel.LOW, false, false, List.of(), "只读或低影响本机命令");
    }

    /**
     * 创建需要确认的风险评估。
     *
     * @param riskLevel 风险级别
     * @param reasons 风险原因
     * @param impact 影响范围
     * @return 风险评估
     */
    public static LocalShellRiskAssessment confirmation(AdbRiskLevel riskLevel, List<String> reasons, String impact) {
        return new LocalShellRiskAssessment(riskLevel, true, false, List.copyOf(reasons), impact);
    }

    /**
     * 创建拒绝执行的风险评估。
     *
     * @param reasons 风险原因
     * @param impact 影响范围
     * @return 风险评估
     */
    public static LocalShellRiskAssessment denied(List<String> reasons, String impact) {
        return new LocalShellRiskAssessment(AdbRiskLevel.CRITICAL, false, true, List.copyOf(reasons), impact);
    }
}
