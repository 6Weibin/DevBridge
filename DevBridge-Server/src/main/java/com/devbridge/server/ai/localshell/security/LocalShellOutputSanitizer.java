package com.devbridge.server.ai.localshell.security;

import com.devbridge.server.ai.mcp.model.AdbOutputLimit;
import com.devbridge.server.ai.mcp.model.AdbSanitizedOutput;
import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.command.CommandResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Local Shell 输出安全处理器，统一执行截断和敏感信息脱敏。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class LocalShellOutputSanitizer {

    private static final int STREAM_LINE_MAX_CHARACTERS = 8_000;
    private static final String STREAM_LINE_TRUNCATED_NOTICE = "\n[单行输出过长，已截断]";

    private final SensitiveDataMasker masker;

    /**
     * 注入现有脱敏工具。
     *
     * @param masker 脱敏工具
     */
    public LocalShellOutputSanitizer(SensitiveDataMasker masker) {
        this.masker = masker;
    }

    /**
     * 对命令结果做输出限制和脱敏。
     *
     * @param result 命令结果
     * @param limit 输出限制
     * @return 安全输出
     */
    public AdbSanitizedOutput sanitize(CommandResult result, AdbOutputLimit limit) {
        LimitedText stdout = limit(result.stdout(), limit.maxStdoutLines(), limit.maxStdoutCharacters());
        LimitedText stderr = limit(result.stderr(), limit.maxStderrLines(), limit.maxStderrCharacters());
        return new AdbSanitizedOutput(
                masker.maskText(stdout.text()),
                masker.maskText(stderr.text()),
                result.outputTruncated() || stdout.truncated() || stderr.truncated());
    }

    /**
     * 对单行增量输出做脱敏。
     *
     * @param line 原始输出行
     * @return 脱敏输出行
     */
    public String sanitizeLine(String line) {
        return masker.maskText(limitLine(line));
    }

    /**
     * 对命令摘要做脱敏，避免审计和确认卡片泄露密钥。
     *
     * @param command 命令摘要
     * @return 脱敏命令摘要
     */
    public String sanitizeCommand(String command) {
        return masker.maskText(command == null ? "" : command);
    }

    /**
     * 限制单行流式输出长度。
     *
     * @param line 原始行
     * @return 限制后的行
     */
    private String limitLine(String line) {
        String value = line == null ? "" : line;
        if (value.length() <= STREAM_LINE_MAX_CHARACTERS) {
            return value;
        }
        int safeLength = Math.max(0, STREAM_LINE_MAX_CHARACTERS - STREAM_LINE_TRUNCATED_NOTICE.length());
        return value.substring(0, safeLength) + STREAM_LINE_TRUNCATED_NOTICE;
    }

    /**
     * 按行数和字符数限制输出。
     *
     * @param lines 原始行
     * @param maxLines 最大行数
     * @param maxCharacters 最大字符数
     * @return 限制后文本
     */
    private LimitedText limit(List<String> lines, int maxLines, int maxCharacters) {
        StringBuilder builder = new StringBuilder();
        boolean truncated = lines.size() > maxLines;
        int count = 0;
        for (String line : lines) {
            if (count >= maxLines) {
                truncated = true;
                break;
            }
            int separatorLength = builder.isEmpty() ? 0 : 1;
            int available = maxCharacters - builder.length() - separatorLength;
            if (available <= 0) {
                truncated = true;
                break;
            }
            if (separatorLength > 0) {
                builder.append('\n');
            }
            // 单行达到上限时保留可容纳前缀，不能把已经有界读取的有效内容整行丢弃。
            if (line.length() > available) {
                builder.append(line, 0, available);
                truncated = true;
                break;
            }
            builder.append(line);
            count++;
        }
        return new LimitedText(builder.toString().trim(), truncated);
    }

    /**
     * 截断处理的内部结果。
     *
     * <p>by AI.Coding</p>
     *
     * @param text 文本
     * @param truncated 是否截断
     */
    private record LimitedText(String text, boolean truncated) {
    }
}
