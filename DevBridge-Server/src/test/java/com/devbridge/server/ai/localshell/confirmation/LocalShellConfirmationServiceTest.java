package com.devbridge.server.ai.localshell.confirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.localshell.model.LocalShellCommandMode;
import com.devbridge.server.ai.localshell.model.LocalShellCommandPlan;
import com.devbridge.server.ai.localshell.model.LocalShellConfirmationChallenge;
import com.devbridge.server.ai.localshell.model.LocalShellConfirmationCheck;
import com.devbridge.server.ai.localshell.model.LocalShellConfirmationRequest;
import com.devbridge.server.ai.localshell.model.LocalShellRiskAssessment;
import com.devbridge.server.ai.localshell.security.LocalShellOutputSanitizer;
import com.devbridge.server.ai.mcp.model.AdbOutputLimit;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.model.BusinessException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Local Shell 确认令牌测试，确保令牌绑定命令且只能使用一次。
 *
 * <p>by AI.Coding</p>
 */
class LocalShellConfirmationServiceTest {

    /**
     * 验证确认令牌可消费一次，重复使用会失败。
     */
    @Test
    void tokenShouldBeConsumedOnlyOnce() {
        LocalShellConfirmationService service = service();
        LocalShellCommandPlan plan = plan("rm -rf target/tmp");
        LocalShellConfirmationChallenge challenge = service.create(new LocalShellConfirmationRequest(
                plan,
                LocalShellRiskAssessment.confirmation(com.devbridge.server.ai.mcp.model.AdbRiskLevel.HIGH, List.of("delete"), "delete file"),
                Duration.ofMinutes(1)));

        service.verifyAndConsume(challenge.token(), new LocalShellConfirmationCheck("c1", service.commandHash(plan), service.workingDirectoryHash(plan), challenge.riskLevel()));

        assertThat(challenge.token()).startsWith("local-");
        assertThatThrownBy(() -> service.verifyAndConsume(challenge.token(), new LocalShellConfirmationCheck("c1", service.commandHash(plan), service.workingDirectoryHash(plan), challenge.riskLevel())))
                .isInstanceOf(BusinessException.class)
                .hasMessage("确认令牌已使用");
    }

    /**
     * 验证命令变化时确认令牌不能复用。
     */
    @Test
    void tokenShouldRejectChangedCommand() {
        LocalShellConfirmationService service = service();
        LocalShellCommandPlan plan = plan("rm -rf target/a");
        LocalShellCommandPlan changed = plan("rm -rf target/b");
        LocalShellConfirmationChallenge challenge = service.create(new LocalShellConfirmationRequest(
                plan,
                LocalShellRiskAssessment.confirmation(com.devbridge.server.ai.mcp.model.AdbRiskLevel.HIGH, List.of("delete"), "delete file"),
                Duration.ofMinutes(1)));

        assertThatThrownBy(() -> service.verifyAndConsume(challenge.token(), new LocalShellConfirmationCheck("c1", service.commandHash(changed), service.workingDirectoryHash(changed), challenge.riskLevel())))
                .isInstanceOf(BusinessException.class)
                .hasMessage("确认令牌与本机命令不匹配");
    }

    /**
     * 创建确认服务。
     *
     * @return 确认服务
     */
    private LocalShellConfirmationService service() {
        return new LocalShellConfirmationService(new LocalShellOutputSanitizer(new SensitiveDataMasker()));
    }

    /**
     * 创建测试命令计划。
     *
     * @param commandLine 命令行
     * @return 命令计划
     */
    private LocalShellCommandPlan plan(String commandLine) {
        return new LocalShellCommandPlan(
                "r1",
                "c1",
                "local_shell_exec",
                LocalShellCommandMode.SHELL,
                commandLine,
                commandLine,
                List.of(),
                Path.of(System.getProperty("user.dir")),
                Map.of(),
                Duration.ofSeconds(5),
                AdbOutputLimit.defaults(),
                false);
    }
}
