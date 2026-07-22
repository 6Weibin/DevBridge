package com.devbridge.server.ai.mcp.risk;

import com.devbridge.server.ai.mcp.model.AdbCommandPlan;
import com.devbridge.server.ai.mcp.model.AdbRiskAssessment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ADB MCP 风险分类器，识别需要用户对话确认的敏感操作。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AdbRiskClassifier {

    private static final Set<String> HIGH_RISK_TOP_LEVEL = Set.of(
            "install", "install-multiple", "install-multi-package", "uninstall", "push", "sync",
            "reboot", "sideload", "root", "unroot", "remount", "disable-verity", "enable-verity",
            "kill-server", "tcpip", "usb");
    private static final List<String> SENSITIVE_PATHS = List.of(
            "/system", "/vendor", "/product", "/data/data", "/data/user", "/sdcard/android/data");

    /**
     * 评估 ADB 命令计划风险。
     *
     * @param plan 命令计划
     * @return 风险评估
     */
    public AdbRiskAssessment assess(AdbCommandPlan plan) {
        List<String> args = plan.adbArguments();
        List<String> reasons = new ArrayList<>();
        String topLevel = topLevel(args);
        collectTopLevelRisk(args, topLevel, reasons);
        if ("shell".equals(topLevel)) {
            collectShellRisk(shellText(args), reasons);
        }
        if (reasons.isEmpty()) {
            return AdbRiskAssessment.low();
        }
        return AdbRiskAssessment.high(List.copyOf(reasons), impact(topLevel, reasons));
    }

    /**
     * 收集顶层 ADB 命令风险。
     *
     * @param args ADB 参数
     * @param topLevel 顶层命令
     * @param reasons 风险原因
     */
    private void collectTopLevelRisk(List<String> args, String topLevel, List<String> reasons) {
        if (HIGH_RISK_TOP_LEVEL.contains(topLevel)) {
            reasons.add("ADB 顶层命令会修改设备或连接状态：" + topLevel);
        }
        if ("forward".equals(topLevel) && containsAny(args, "--remove", "--remove-all")) {
            reasons.add("删除 adb forward 端口规则");
        }
        if ("reverse".equals(topLevel) && containsAny(args, "--remove", "--remove-all")) {
            reasons.add("删除 adb reverse 端口规则");
        }
        if ("disconnect".equals(topLevel) && args.size() <= 1) {
            reasons.add("断开全部 ADB 网络连接");
        }
        if ("reconnect".equals(topLevel)) {
            reasons.add("重连 ADB server 或设备，可能影响当前设备会话");
        }
    }

    /**
     * 收集 adb shell 命令风险。
     *
     * @param shell shell 命令文本
     * @param reasons 风险原因
     */
    private void collectShellRisk(String shell, List<String> reasons) {
        String value = shell.toLowerCase(Locale.ROOT);
        if (matchesDelete(value)) {
            reasons.add("shell 命令包含删除或覆盖写操作");
        }
        if (containsAny(value, "pm uninstall", "cmd package uninstall", "pm clear")) {
            reasons.add("shell 命令包含卸载应用或清除应用数据");
        }
        if (containsAny(value, " su", "su ", " mount ", "setenforce")) {
            reasons.add("shell 命令包含 root、挂载或 SELinux 修改操作");
        }
        if (writesSensitivePath(value)) {
            reasons.add("shell 命令写入或删除系统路径、应用私有路径或 Android/data");
        }
    }

    /**
     * 判断 shell 是否包含删除或覆盖写。
     *
     * @param value 小写命令文本
     * @return 命中返回 true
     */
    private boolean matchesDelete(String value) {
        return containsAny(value, "rm ", "rm\t", "unlink ", "rmdir ", "find ")
                && (value.contains(" rm") || value.startsWith("rm") || value.contains("-delete") || value.contains("unlink") || value.contains("rmdir"))
                || value.contains("dd ") && value.contains(" of=");
    }

    /**
     * 判断 shell 是否对敏感路径执行写、删、改权限。
     *
     * @param value 小写命令文本
     * @return 命中返回 true
     */
    private boolean writesSensitivePath(String value) {
        boolean writeCommand = containsAny(value, "rm ", "mv ", "cp ", "chmod ", "chown ", "dd ", "tee ", "echo ");
        return writeCommand && SENSITIVE_PATHS.stream().anyMatch(value::contains);
    }

    /**
     * 查找跳过 global options 后的顶层命令。
     *
     * @param args ADB 参数
     * @return 顶层命令
     */
    private String topLevel(List<String> args) {
        for (int index = 0; index < args.size(); index++) {
            String arg = args.get(index);
            if (Set.of("-s", "-t", "-H", "-P", "-L", "--one-device").contains(arg)) {
                index++;
                continue;
            }
            if (Set.of("-a", "-d", "-e", "--exit-on-write-error").contains(arg)) {
                continue;
            }
            return arg;
        }
        return "";
    }

    /**
     * 拼接 shell 命令文本，供风险规则做保守识别。
     *
     * @param args ADB 参数
     * @return shell 文本
     */
    private String shellText(List<String> args) {
        int shellIndex = args.indexOf("shell");
        if (shellIndex < 0 || shellIndex + 1 >= args.size()) {
            return "";
        }
        return String.join(" ", args.subList(shellIndex + 1, args.size()));
    }

    /**
     * 判断列表是否包含任一目标值。
     *
     * @param args 参数列表
     * @param values 目标值
     * @return 命中返回 true
     */
    private boolean containsAny(List<String> args, String... values) {
        for (String value : values) {
            if (args.contains(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断文本是否包含任一片段。
     *
     * @param source 文本
     * @param values 片段
     * @return 命中返回 true
     */
    private boolean containsAny(String source, String... values) {
        for (String value : values) {
            if (source.contains(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成风险影响说明。
     *
     * @param topLevel 顶层命令
     * @param reasons 风险原因
     * @return 影响说明
     */
    private String impact(String topLevel, List<String> reasons) {
        if ("shell".equals(topLevel)) {
            return "该 shell 命令可能修改设备文件、应用数据或系统状态，确认后才会执行。";
        }
        return "该 ADB 命令可能修改设备、应用、文件或连接状态，确认后才会执行：" + String.join("；", reasons);
    }
}
