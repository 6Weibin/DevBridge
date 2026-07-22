package com.devbridge.server.ai.security;

import com.devbridge.server.ai.analysis.AiLogLine;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 敏感信息脱敏工具，统一处理日志、Provider 错误和配置摘要。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class SensitiveDataMasker {

    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?[^\\s,;]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile("(?i)\\b(api[_-]?key|token|access[_-]?token|secret|password)\\s*[:=]\\s*[^\\s,;&]+");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");

    /**
     * 对文本中的常见凭证、邮箱和手机号做脱敏；空文本返回空串，避免下游判空重复。
     *
     * @param text 原始文本
     * @return 脱敏文本
     */
    public String maskText(String text) {
        String result = text == null ? "" : text;
        result = AUTHORIZATION.matcher(result).replaceAll("$1***");
        result = SECRET_ASSIGNMENT.matcher(result).replaceAll("$1=***");
        result = EMAIL.matcher(result).replaceAll("***@***");
        return PHONE.matcher(result).replaceAll("1**********");
    }

    /**
     * 对设备序列号做中间脱敏；短序列号直接整体隐藏，避免误暴露设备标识。
     *
     * @param serial 设备序列号
     * @return 脱敏序列号
     */
    public String maskSerial(String serial) {
        String value = serial == null ? "" : serial.trim();
        if (value.length() <= 6) {
            return "***";
        }
        return value.substring(0, 3) + "***" + value.substring(value.length() - 3);
    }

    /**
     * 对日志列表逐行脱敏，保留时间、级别、PID 和 Tag 以便 AI 分析。
     *
     * @param logs 原始日志列表
     * @return 脱敏日志列表
     */
    public List<AiLogLine> maskLogs(List<AiLogLine> logs) {
        return logs.stream()
                .map(line -> new AiLogLine(
                        line.timestamp(),
                        line.level(),
                        line.pid(),
                        maskText(line.tag()),
                        maskText(line.message())))
                .toList();
    }
}
