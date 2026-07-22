package com.devbridge.server.ai.localshell.confirmation;

import com.devbridge.server.ai.localshell.model.LocalShellCommandPlan;
import com.devbridge.server.ai.localshell.model.LocalShellConfirmationChallenge;
import com.devbridge.server.ai.localshell.model.LocalShellConfirmationCheck;
import com.devbridge.server.ai.localshell.model.LocalShellConfirmationEntry;
import com.devbridge.server.ai.localshell.model.LocalShellConfirmationRequest;
import com.devbridge.server.ai.localshell.security.LocalShellOutputSanitizer;
import com.devbridge.server.ai.mcp.model.AdbConfirmationStatus;
import com.devbridge.server.model.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Local Shell 敏感命令确认服务，负责一次性令牌生成、绑定校验、消费和取消。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class LocalShellConfirmationService {

    private static final String TOKEN_PREFIX = "local-";

    private final Map<String, PendingConfirmation> confirmations = new ConcurrentHashMap<>();
    private final LocalShellOutputSanitizer sanitizer;

    /**
     * 注入脱敏工具，确认挑战中不展示未脱敏命令。
     *
     * @param sanitizer 脱敏工具
     */
    public LocalShellConfirmationService(LocalShellOutputSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    /**
     * 创建确认挑战并保存令牌绑定信息。
     *
     * @param request 确认请求
     * @return 确认挑战
     */
    public LocalShellConfirmationChallenge create(LocalShellConfirmationRequest request) {
        String token = TOKEN_PREFIX + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(request.ttl());
        LocalShellCommandPlan plan = request.plan();
        LocalShellConfirmationEntry entry = new LocalShellConfirmationEntry(
                hash(token),
                plan.conversationId(),
                commandHash(plan),
                workingDirectoryHash(plan),
                request.assessment().riskLevel(),
                expiresAt,
                AdbConfirmationStatus.PENDING);
        confirmations.put(entry.tokenHash(), new PendingConfirmation(entry, plan));
        return new LocalShellConfirmationChallenge(
                token,
                sanitizer.sanitizeCommand(plan.commandSummary()),
                plan.workingDirectory().toString(),
                request.assessment().riskLevel(),
                String.join("；", request.assessment().reasons()),
                request.assessment().impact(),
                expiresAt);
    }

    /**
     * 根据确认令牌消费并返回原始命令计划。
     *
     * @param token 确认令牌
     * @param conversationId 对话 ID
     * @return 令牌绑定的命令计划
     */
    public LocalShellCommandPlan consumePlan(String token, String conversationId) {
        PendingConfirmation pending = pending(token);
        validateEntry(pending.entry(), new LocalShellConfirmationCheck(
                conversationId,
                commandHash(pending.plan()),
                workingDirectoryHash(pending.plan()),
                pending.entry().riskLevel()));
        confirmations.put(hash(token), new PendingConfirmation(used(pending.entry()), pending.plan()));
        return pending.plan();
    }

    /**
     * 校验并消费确认令牌，供模型携带原命令二次调用时使用。
     *
     * @param token 确认令牌
     * @param check 绑定信息
     */
    public void verifyAndConsume(String token, LocalShellConfirmationCheck check) {
        PendingConfirmation pending = pending(token);
        validateEntry(pending.entry(), check);
        confirmations.put(hash(token), new PendingConfirmation(used(pending.entry()), pending.plan()));
    }

    /**
     * 取消确认令牌；取消后不能再执行命令。
     *
     * @param token 确认令牌
     * @param conversationId 对话 ID
     */
    public void cancel(String token, String conversationId) {
        PendingConfirmation pending = pending(token);
        if (!pending.entry().conversationId().equals(conversationId)) {
            throw new BusinessException("LOCAL_SHELL_CONFIRMATION_MISMATCH", "确认令牌与当前对话不匹配", HttpStatus.CONFLICT, "");
        }
        confirmations.put(hash(token), new PendingConfirmation(canceled(pending.entry()), pending.plan()));
    }

    /**
     * 计算命令计划哈希。
     *
     * @param plan 命令计划
     * @return 命令哈希
     */
    public String commandHash(LocalShellCommandPlan plan) {
        String value = plan.mode() + "\u0000" + plan.commandSummary() + "\u0000" + plan.environment();
        return hash(value);
    }

    /**
     * 计算工作目录哈希。
     *
     * @param plan 命令计划
     * @return 工作目录哈希
     */
    public String workingDirectoryHash(LocalShellCommandPlan plan) {
        return hash(plan.workingDirectory().toString());
    }

    /**
     * 获取待处理确认记录并做基础状态检查。
     *
     * @param token 确认令牌
     * @return 待处理记录
     */
    private PendingConfirmation pending(String token) {
        PendingConfirmation pending = confirmations.get(hash(token));
        if (pending == null) {
            throw new BusinessException("LOCAL_SHELL_CONFIRMATION_EXPIRED", "确认令牌不存在或已过期", HttpStatus.CONFLICT, "");
        }
        if (pending.entry().status() == AdbConfirmationStatus.USED) {
            throw new BusinessException("LOCAL_SHELL_CONFIRMATION_USED", "确认令牌已使用", HttpStatus.CONFLICT, "");
        }
        if (pending.entry().status() == AdbConfirmationStatus.CANCELED) {
            throw new BusinessException("LOCAL_SHELL_CONFIRMATION_CANCELED", "确认令牌已取消", HttpStatus.CONFLICT, "");
        }
        if (Instant.now().isAfter(pending.entry().expiresAt())) {
            throw new BusinessException("LOCAL_SHELL_CONFIRMATION_EXPIRED", "确认令牌已过期", HttpStatus.CONFLICT, "");
        }
        return pending;
    }

    /**
     * 校验令牌绑定信息，防止模型修改命令后复用旧令牌。
     *
     * @param entry 令牌记录
     * @param check 校验信息
     */
    private void validateEntry(LocalShellConfirmationEntry entry, LocalShellConfirmationCheck check) {
        boolean matched = entry.conversationId().equals(check.conversationId())
                && entry.commandHash().equals(check.commandHash())
                && entry.workingDirectoryHash().equals(check.workingDirectoryHash())
                && entry.riskLevel() == check.riskLevel();
        if (!matched) {
            throw new BusinessException("LOCAL_SHELL_CONFIRMATION_MISMATCH", "确认令牌与本机命令不匹配", HttpStatus.CONFLICT, "");
        }
    }

    /**
     * 生成已使用状态的新记录，保持 record 不可变。
     *
     * @param entry 原记录
     * @return 已使用记录
     */
    private LocalShellConfirmationEntry used(LocalShellConfirmationEntry entry) {
        return withStatus(entry, AdbConfirmationStatus.USED);
    }

    /**
     * 生成已取消状态的新记录，保持 record 不可变。
     *
     * @param entry 原记录
     * @return 已取消记录
     */
    private LocalShellConfirmationEntry canceled(LocalShellConfirmationEntry entry) {
        return withStatus(entry, AdbConfirmationStatus.CANCELED);
    }

    /**
     * 复制确认记录并替换状态。
     *
     * @param entry 原记录
     * @param status 新状态
     * @return 新记录
     */
    private LocalShellConfirmationEntry withStatus(LocalShellConfirmationEntry entry, AdbConfirmationStatus status) {
        return new LocalShellConfirmationEntry(
                entry.tokenHash(),
                entry.conversationId(),
                entry.commandHash(),
                entry.workingDirectoryHash(),
                entry.riskLevel(),
                entry.expiresAt(),
                status);
    }

    /**
     * 使用 SHA-256 计算绑定摘要，避免内存结构暴露令牌明文。
     *
     * @param value 原始值
     * @return 十六进制 hash
     */
    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * 内部待确认记录，保存令牌绑定的命令计划供前端确认后执行。
     *
     * <p>by AI.Coding</p>
     *
     * @param entry 令牌记录
     * @param plan 命令计划
     */
    private record PendingConfirmation(LocalShellConfirmationEntry entry, LocalShellCommandPlan plan) {
    }
}
