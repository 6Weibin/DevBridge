package com.devbridge.server.ai.mcp.confirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.mcp.model.AdbCommandPlan;
import com.devbridge.server.ai.mcp.model.AdbConfirmationCheck;
import com.devbridge.server.ai.mcp.model.AdbConfirmationChallenge;
import com.devbridge.server.ai.mcp.model.AdbConfirmationRequest;
import com.devbridge.server.ai.mcp.model.AdbOutputLimit;
import com.devbridge.server.ai.mcp.model.AdbRiskAssessment;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.model.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADB 确认令牌测试，确保令牌绑定命令且只能使用一次。
 *
 * <p>by AI.Coding</p>
 */
class AdbConfirmationServiceTest {

    /**
     * 验证确认令牌可消费一次，重复使用会失败。
     */
    @Test
    void tokenShouldBeConsumedOnlyOnce() {
        AdbConfirmationService service = new AdbConfirmationService(new SensitiveDataMasker());
        AdbCommandPlan plan = plan(List.of("-s", "serial-1", "shell", "rm /sdcard/a.log"));
        AdbConfirmationChallenge challenge = service.create(new AdbConfirmationRequest(
                plan,
                AdbRiskAssessment.high(List.of("delete"), "delete file"),
                Duration.ofMinutes(1)));

        service.verifyAndConsume(challenge.token(), new AdbConfirmationCheck("c1", "serial-1", service.argsHash(plan), challenge.riskLevel()));

        assertThatThrownBy(() -> service.verifyAndConsume(challenge.token(), new AdbConfirmationCheck("c1", "serial-1", service.argsHash(plan), challenge.riskLevel())))
                .isInstanceOf(BusinessException.class)
                .hasMessage("确认令牌已使用");
    }

    /**
     * 验证命令参数变化时确认令牌不能复用。
     */
    @Test
    void tokenShouldRejectChangedCommand() {
        AdbConfirmationService service = new AdbConfirmationService(new SensitiveDataMasker());
        AdbCommandPlan plan = plan(List.of("-s", "serial-1", "shell", "rm /sdcard/a.log"));
        AdbCommandPlan changed = plan(List.of("-s", "serial-1", "shell", "rm /sdcard/b.log"));
        AdbConfirmationChallenge challenge = service.create(new AdbConfirmationRequest(
                plan,
                AdbRiskAssessment.high(List.of("delete"), "delete file"),
                Duration.ofMinutes(1)));

        assertThat(challenge.deviceSerialMasked()).isEqualTo("ser***l-1");
        assertThatThrownBy(() -> service.verifyAndConsume(challenge.token(), new AdbConfirmationCheck("c1", "serial-1", service.argsHash(changed), challenge.riskLevel())))
                .isInstanceOf(BusinessException.class)
                .hasMessage("确认令牌与命令不匹配");
    }

    /**
     * 创建测试命令计划。
     */
    private AdbCommandPlan plan(List<String> args) {
        return new AdbCommandPlan("r1", "c1", "adb_shell", args, "serial-1", true, Map.of(), Duration.ofSeconds(5), AdbOutputLimit.defaults(), false);
    }
}
