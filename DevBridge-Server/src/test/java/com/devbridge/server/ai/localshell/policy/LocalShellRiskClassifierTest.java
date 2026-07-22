package com.devbridge.server.ai.localshell.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.config.AiCommandAuthorizationRule;
import com.devbridge.server.ai.config.AiConfigCrypto;
import com.devbridge.server.ai.config.AiConfigRequest;
import com.devbridge.server.ai.config.AiConfigService;
import com.devbridge.server.ai.localshell.model.LocalShellCommandMode;
import com.devbridge.server.ai.localshell.model.LocalShellCommandPlan;
import com.devbridge.server.ai.localshell.model.LocalShellRiskAssessment;
import com.devbridge.server.ai.mcp.model.AdbOutputLimit;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Local Shell 风险分类测试，覆盖只读、删除和提权命令。
 *
 * <p>by AI.Coding</p>
 */
class LocalShellRiskClassifierTest {

    /**
     * 验证删除本机文件命令需要用户确认。
     */
    @Test
    void assessShouldRequireConfirmationForDeleteCommand(@TempDir Path tempDir) {
        LocalShellRiskClassifier classifier = classifier(tempDir);

        LocalShellRiskAssessment assessment = classifier.assess(plan(LocalShellCommandMode.ARGV, List.of("rm", "-rf", "target/tmp"), ""));

        assertThat(assessment.confirmationRequired()).isTrue();
        assertThat(assessment.riskLevel().name()).isEqualTo("HIGH");
        assertThat(assessment.reasons()).anyMatch(reason -> reason.contains("删除"));
    }

    /**
     * 验证普通只读命令可以直接执行。
     */
    @Test
    void assessShouldAllowReadOnlyCommand(@TempDir Path tempDir) {
        LocalShellRiskClassifier classifier = classifier(tempDir);

        LocalShellRiskAssessment assessment = classifier.assess(plan(LocalShellCommandMode.ARGV, List.of("git", "status", "--short"), ""));

        assertThat(assessment.confirmationRequired()).isFalse();
        assertThat(assessment.denied()).isFalse();
    }

    /**
     * 验证提权命令会被直接拒绝。
     */
    @Test
    void assessShouldDenySudoCommand(@TempDir Path tempDir) {
        LocalShellRiskClassifier classifier = classifier(tempDir);

        LocalShellRiskAssessment assessment = classifier.assess(plan(LocalShellCommandMode.SHELL, List.of(), "sudo rm -rf /tmp/a"));

        assertThat(assessment.denied()).isTrue();
        assertThat(assessment.riskLevel().name()).isEqualTo("CRITICAL");
    }

    /**
     * 验证用户可将普通高风险命令授权为低风险直接执行。
     *
     * @param tempDir 临时配置目录
     */
    @Test
    void assessShouldAllowCommandWhenUserAuthorizationIsLow(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        service.save(request(List.of(new AiCommandAuthorizationRule("npm", "LOW"))));
        LocalShellRiskClassifier classifier = new LocalShellRiskClassifier(new DevBridgeProperties(), service);

        LocalShellRiskAssessment assessment = classifier.assess(plan(LocalShellCommandMode.ARGV, List.of("npm", "install"), ""));

        assertThat(assessment.confirmationRequired()).isFalse();
        assertThat(assessment.denied()).isFalse();
    }

    /**
     * 验证用户可将只读命令配置为中风险确认。
     *
     * @param tempDir 临时配置目录
     */
    @Test
    void assessShouldRequireConfirmationWhenUserAuthorizationIsMedium(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        service.save(request(List.of(new AiCommandAuthorizationRule("pwd", "MEDIUM"))));
        LocalShellRiskClassifier classifier = new LocalShellRiskClassifier(new DevBridgeProperties(), service);

        LocalShellRiskAssessment assessment = classifier.assess(plan(LocalShellCommandMode.ARGV, List.of("pwd"), ""));

        assertThat(assessment.confirmationRequired()).isTrue();
        assertThat(assessment.riskLevel().name()).isEqualTo("MEDIUM");
    }

    /**
     * 验证用户可将命令配置为高风险阻断。
     *
     * @param tempDir 临时配置目录
     */
    @Test
    void assessShouldDenyCommandWhenUserAuthorizationIsHigh(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        service.save(request(List.of(new AiCommandAuthorizationRule("pwd", "HIGH"))));
        LocalShellRiskClassifier classifier = new LocalShellRiskClassifier(new DevBridgeProperties(), service);

        LocalShellRiskAssessment assessment = classifier.assess(plan(LocalShellCommandMode.ARGV, List.of("pwd"), ""));

        assertThat(assessment.denied()).isTrue();
    }

    /**
     * 验证内置极高危规则优先于用户低风险授权，避免基础安全红线被绕过。
     *
     * @param tempDir 临时配置目录
     */
    @Test
    void assessShouldKeepCriticalCommandDeniedEvenWhenUserAuthorizationIsLow(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        service.save(request(List.of(new AiCommandAuthorizationRule("sudo rm -rf /tmp/a", "LOW"))));
        LocalShellRiskClassifier classifier = new LocalShellRiskClassifier(new DevBridgeProperties(), service);

        LocalShellRiskAssessment assessment = classifier.assess(plan(LocalShellCommandMode.SHELL, List.of(), "sudo rm -rf /tmp/a"));

        assertThat(assessment.denied()).isTrue();
        assertThat(assessment.riskLevel().name()).isEqualTo("CRITICAL");
    }

    /**
     * 验证复合 shell 命令不会被短前缀低风险授权绕过。
     *
     * @param tempDir 临时配置目录
     */
    @Test
    void assessShouldNotAllowCompoundCommandByPrefixAuthorization(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        service.save(request(List.of(new AiCommandAuthorizationRule("pwd", "LOW"))));
        LocalShellRiskClassifier classifier = new LocalShellRiskClassifier(new DevBridgeProperties(), service);

        LocalShellRiskAssessment assessment = classifier.assess(plan(LocalShellCommandMode.SHELL, List.of(), "pwd && rm -rf target/tmp"));

        assertThat(assessment.confirmationRequired()).isTrue();
        assertThat(assessment.riskLevel().name()).isEqualTo("HIGH");
    }

    /** 换行和命令替换同样属于复合 Shell 语义，不能命中低风险前缀授权。 */
    @Test
    void assessShouldRejectMultilineAndSubstitutionAuthorizationBypass(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        service.save(request(List.of(new AiCommandAuthorizationRule("pwd", "LOW"))));
        LocalShellRiskClassifier classifier = new LocalShellRiskClassifier(new DevBridgeProperties(), service);

        assertThat(classifier.assess(plan(
                LocalShellCommandMode.SHELL, List.of(), "pwd\nrm -rf target/tmp")).confirmationRequired()).isTrue();
        assertThat(classifier.assess(plan(
                LocalShellCommandMode.SHELL, List.of(), "pwd `rm -rf target/tmp`")).confirmationRequired()).isTrue();
    }

    /**
     * 创建测试命令计划。
     *
     * @param mode 命令模式
     * @param argv 参数数组
     * @param commandLine shell 命令
     * @return 命令计划
     */
    private LocalShellCommandPlan plan(LocalShellCommandMode mode, List<String> argv, String commandLine) {
        return new LocalShellCommandPlan(
                "r1",
                "c1",
                "local_shell_exec",
                mode,
                mode == LocalShellCommandMode.ARGV ? String.join(" ", argv) : commandLine,
                commandLine,
                argv,
                Path.of(System.getProperty("user.dir")),
                Map.of(),
                Duration.ofSeconds(5),
                AdbOutputLimit.defaults(),
                false);
    }

    /**
     * 创建默认风险分类器。
     *
     * @param root 配置根目录
     * @return 风险分类器
     */
    private LocalShellRiskClassifier classifier(Path root) {
        return new LocalShellRiskClassifier(new DevBridgeProperties(), service(root));
    }

    /**
     * 创建带授权规则的配置请求。
     *
     * @param rules 授权规则
     * @return 配置请求
     */
    private AiConfigRequest request(List<AiCommandAuthorizationRule> rules) {
        return new AiConfigRequest("openai", "https://api.openai.com", "sk-test", "gpt-test", "提示词", rules);
    }

    /**
     * 创建测试用 AI 配置服务。
     *
     * @param root 配置根目录
     * @return AI 配置服务
     */
    private AiConfigService service(Path root) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiConfigRoot(root.toString());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new AiConfigService(properties, new AiConfigCrypto(), mapper);
    }
}
