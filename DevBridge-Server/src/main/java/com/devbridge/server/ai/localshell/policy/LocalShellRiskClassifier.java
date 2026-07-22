package com.devbridge.server.ai.localshell.policy;

import com.devbridge.server.ai.config.AiCommandAuthorizationRule;
import com.devbridge.server.ai.config.AiConfigService;
import com.devbridge.server.ai.localshell.model.LocalShellCommandMode;
import com.devbridge.server.ai.localshell.model.LocalShellCommandPlan;
import com.devbridge.server.ai.localshell.model.LocalShellRiskAssessment;
import com.devbridge.server.ai.mcp.model.AdbRiskLevel;
import com.devbridge.server.config.DevBridgeProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Local Shell 风险识别器，保守识别高风险本机命令并要求用户确认。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class LocalShellRiskClassifier {

    private static final List<String> DESTRUCTIVE_TOKENS = List.of(
            "rm", "rmdir", "del", "remove-item", "unlink", "trash", "shred");
    private static final List<String> WRITE_TOKENS = List.of(
            ">", ">>", "tee", "cp", "mv", "touch", "mkdir", "set-content", "out-file");
    private static final List<String> PERMISSION_TOKENS = List.of(
            "chmod", "chown", "chgrp", "icacls", "attrib");
    private static final List<String> PROCESS_TOKENS = List.of(
            "kill", "pkill", "killall", "taskkill", "launchctl", "systemctl", "service");
    private static final List<String> PACKAGE_TOKENS = List.of(
            "brew", "apt", "yum", "dnf", "pacman", "winget", "npm", "pnpm", "yarn", "pip", "pip3");
    private static final List<String> NETWORK_TOKENS = List.of(
            "curl", "wget", "invoke-webrequest", "irm", "iwr");
    private static final List<String> SECRET_PATH_TOKENS = List.of(
            ".env", ".ssh", "id_rsa", "id_ed25519", "credentials", "keychain", "secrets");

    private final DevBridgeProperties properties;
    private final AiConfigService aiConfigService;

    /**
     * 注入配置。
     *
     * @param properties DevBridge 配置
     * @param aiConfigService AI 配置服务
     */
    public LocalShellRiskClassifier(DevBridgeProperties properties, AiConfigService aiConfigService) {
        this.properties = properties;
        this.aiConfigService = aiConfigService;
    }

    /**
     * 评估命令风险。
     *
     * @param plan 命令计划
     * @return 风险评估
     */
    public LocalShellRiskAssessment assess(LocalShellCommandPlan plan) {
        String rawCommand = plan.commandSummary().trim();
        String command = rawCommand.toLowerCase(Locale.ROOT);
        List<String> reasons = new ArrayList<>();
        collectCriticalRisk(command, reasons);
        if (!reasons.isEmpty()) {
            return LocalShellRiskAssessment.denied(reasons, "该命令可能直接破坏系统或绕过安全边界，已拒绝由 AI 执行。");
        }
        LocalShellRiskAssessment authorized = assessUserAuthorization(rawCommand, command);
        if (authorized != null) {
            return authorized;
        }
        collectHighRisk(plan, command, reasons);
        if (!reasons.isEmpty()) {
            return LocalShellRiskAssessment.confirmation(AdbRiskLevel.HIGH, reasons, "该命令会修改本机文件、进程、依赖或系统状态。");
        }
        collectMediumRisk(plan, command, reasons);
        if (!reasons.isEmpty()) {
            return LocalShellRiskAssessment.confirmation(AdbRiskLevel.MEDIUM, reasons, "该命令会读取较多本机信息或使用 shell 复合语义。");
        }
        return LocalShellRiskAssessment.low();
    }

    /**
     * 按用户授权规则覆盖普通风险等级；内置极高危规则已提前阻断，不能被用户配置降级。
     *
     * @param rawCommand 原始命令
     * @param command 小写命令
     * @return 命中授权时返回风险评估，未命中返回 null
     */
    private LocalShellRiskAssessment assessUserAuthorization(String rawCommand, String command) {
        for (AiCommandAuthorizationRule rule : aiConfigService.localShellAuthorizations()) {
            if (matchesAuthorizationRule(rawCommand, command, rule.command())) {
                return assessmentFromAuthorization(rule);
            }
        }
        return null;
    }

    /**
     * 将用户授权等级转换为执行策略。
     *
     * @param rule 命中的授权规则
     * @return 风险评估
     */
    private LocalShellRiskAssessment assessmentFromAuthorization(AiCommandAuthorizationRule rule) {
        String reason = "命中用户授权规则：" + rule.command();
        if ("LOW".equals(rule.level())) {
            return LocalShellRiskAssessment.low();
        }
        if ("MEDIUM".equals(rule.level())) {
            return LocalShellRiskAssessment.confirmation(
                    AdbRiskLevel.MEDIUM,
                    List.of(reason),
                    "该命令按用户授权配置需要确认后执行。");
        }
        return LocalShellRiskAssessment.denied(List.of(reason), "该命令已被用户授权配置阻断。");
    }

    /**
     * 判断命令是否命中授权规则；复合 shell 命令只允许精确匹配，避免前缀授权绕过后续危险操作。
     *
     * @param rawCommand 原始命令
     * @param command 小写命令
     * @param ruleCommand 授权规则命令
     * @return 命中返回 true
     */
    private boolean matchesAuthorizationRule(String rawCommand, String command, String ruleCommand) {
        String rawRule = ruleCommand.trim();
        String rule = rawRule.toLowerCase(Locale.ROOT);
        if (rawCommand.equals(rawRule) || command.equals(rule)) {
            return true;
        }
        if (hasCompoundShellSyntax(command)) {
            return false;
        }
        return command.startsWith(rule + " ");
    }

    /**
     * 识别 shell 复合语义；这类命令必须整体授权，不能只靠前缀命中。
     *
     * @param command 小写命令
     * @return 存在复合控制符返回 true
     */
    private boolean hasCompoundShellSyntax(String command) {
        return command.contains("|")
                || command.contains(";")
                || command.contains("&&")
                || command.contains("||")
                || command.contains("$(")
                || command.contains(">")
                || command.contains("<")
                || command.contains("\n")
                || command.contains("\r")
                || command.contains("`")
                || command.contains("&");
    }

    /**
     * 收集直接拒绝的高危命令。
     *
     * @param command 小写命令
     * @param reasons 风险原因
     */
    private void collectCriticalRisk(String command, List<String> reasons) {
        if (containsWord(command, "sudo") || containsWord(command, "su")) {
            reasons.add("包含提权命令");
        }
        if (command.contains("rm -rf /") || command.contains("format ") || command.contains("mkfs")) {
            reasons.add("包含系统破坏性命令");
        }
        if ((command.contains("curl") || command.contains("wget")) && (command.contains("| sh") || command.contains("| bash"))) {
            reasons.add("包含下载后直接执行脚本");
        }
    }

    /**
     * 收集必须确认的高风险命令。
     *
     * @param plan 命令计划
     * @param command 小写命令
     * @param reasons 风险原因
     */
    private void collectHighRisk(LocalShellCommandPlan plan, String command, List<String> reasons) {
        collectContains(command, DESTRUCTIVE_TOKENS, "包含删除命令", reasons);
        collectContains(command, WRITE_TOKENS, "包含写入或覆盖命令", reasons);
        collectContains(command, PERMISSION_TOKENS, "包含权限或所有权变更命令", reasons);
        collectContains(command, PROCESS_TOKENS, "包含进程或服务控制命令", reasons);
        collectContains(command, PACKAGE_TOKENS, "包含依赖或包管理命令", reasons);
        if (plan.workingDirectory().toString().contains("/System") || plan.workingDirectory().toString().contains("\\Windows")) {
            reasons.add("工作目录位于系统敏感区域");
        }
    }

    /**
     * 收集中风险命令。
     *
     * @param plan 命令计划
     * @param command 小写命令
     * @param reasons 风险原因
     */
    private void collectMediumRisk(LocalShellCommandPlan plan, String command, List<String> reasons) {
        collectContains(command, NETWORK_TOKENS, "包含网络下载或请求命令", reasons);
        collectContains(command, SECRET_PATH_TOKENS, "可能读取密钥或凭据文件", reasons);
        if (plan.mode() == LocalShellCommandMode.SHELL && properties.getAiMcpLocalShell().isRequireConfirmationForShellMode()) {
            reasons.add("使用 Shell 模式执行复合命令");
        }
        if (command.contains("|") || command.contains(";") || command.contains("&&") || command.contains("||") || command.contains("$(")) {
            reasons.add("包含 shell 复合控制符");
        }
    }

    /**
     * 批量判断命令是否包含敏感词。
     *
     * @param command 命令
     * @param tokens 敏感词
     * @param reason 命中原因
     * @param reasons 风险原因
     */
    private void collectContains(String command, List<String> tokens, String reason, List<String> reasons) {
        for (String token : tokens) {
            if (containsWord(command, token) || command.contains(token)) {
                reasons.add(reason + "：" + token);
                return;
            }
        }
    }

    /**
     * 判断是否命中单词级 token，避免 `system` 误判成 `rm`。
     *
     * @param command 命令
     * @param token token
     * @return 命中返回 true
     */
    private boolean containsWord(String command, String token) {
        return java.util.regex.Pattern.compile("(^|[^a-zA-Z0-9_./-])" + java.util.regex.Pattern.quote(token) + "($|[^a-zA-Z0-9_./-])")
                .matcher(command)
                .find();
    }
}
