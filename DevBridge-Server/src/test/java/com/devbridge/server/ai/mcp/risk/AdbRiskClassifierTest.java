package com.devbridge.server.ai.mcp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.mcp.model.AdbCommandPlan;
import com.devbridge.server.ai.mcp.model.AdbOutputLimit;
import com.devbridge.server.ai.mcp.model.AdbRiskAssessment;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADB 风险分类测试，覆盖 shell 和顶层敏感操作。
 *
 * <p>by AI.Coding</p>
 */
class AdbRiskClassifierTest {

    private final AdbRiskClassifier classifier = new AdbRiskClassifier();

    /**
     * 验证删除 shell 命令需要确认。
     */
    @Test
    void assessShouldRequireConfirmationForShellDelete() {
        AdbRiskAssessment assessment = classifier.assess(plan(List.of("-s", "s1", "shell", "rm -rf /sdcard/test")));

        assertThat(assessment.confirmationRequired()).isTrue();
        assertThat(assessment.reasons()).anyMatch(reason -> reason.contains("删除"));
    }

    /**
     * 验证只读 getprop 命令可以直接执行。
     */
    @Test
    void assessShouldAllowReadOnlyShell() {
        AdbRiskAssessment assessment = classifier.assess(plan(List.of("-s", "s1", "shell", "getprop ro.product.model")));

        assertThat(assessment.confirmationRequired()).isFalse();
    }

    /**
     * 创建测试命令计划。
     */
    private AdbCommandPlan plan(List<String> args) {
        return new AdbCommandPlan("r1", "c1", "adb_shell", args, "s1", true, Map.of(), Duration.ofSeconds(5), AdbOutputLimit.defaults(), false);
    }
}
