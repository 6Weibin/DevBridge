package com.devbridge.server.ai.mcp.confirmation;

import com.devbridge.server.ai.mcp.model.AdbCommandPlan;
import com.devbridge.server.ai.mcp.model.AdbConfirmationChallenge;
import com.devbridge.server.ai.mcp.model.AdbConfirmationCheck;
import com.devbridge.server.ai.mcp.model.AdbConfirmationEntry;
import com.devbridge.server.ai.mcp.model.AdbConfirmationRequest;
import com.devbridge.server.ai.mcp.model.AdbConfirmationStatus;
import com.devbridge.server.ai.security.SensitiveDataMasker;
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
 * ADB 敏感操作确认服务，负责一次性令牌生成、绑定校验、消费和取消。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AdbConfirmationService {

    private final Map<String, PendingConfirmation> confirmations = new ConcurrentHashMap<>();
    private final SensitiveDataMasker masker;

    /**
     * 注入脱敏工具，确认挑战中不展示完整设备序列号。
     *
     * @param masker 脱敏工具
     */
    public AdbConfirmationService(SensitiveDataMasker masker) {
        this.masker = masker;
    }

    /**
     * 创建确认挑战并保存令牌绑定信息。
     *
     * @param request 确认请求
     * @return 确认挑战
     */
    public AdbConfirmationChallenge create(AdbConfirmationRequest request) {
        String token = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(request.ttl());
        AdbCommandPlan plan = request.plan();
        AdbConfirmationEntry entry = new AdbConfirmationEntry(
                hash(token),
                plan.conversationId(),
                hash(plan.deviceSerial()),
                argsHash(plan),
                request.assessment().riskLevel(),
                expiresAt,
                AdbConfirmationStatus.PENDING);
        confirmations.put(entry.tokenHash(), new PendingConfirmation(entry, plan));
        return new AdbConfirmationChallenge(
                token,
                plan.argumentSummary(),
                masker.maskSerial(plan.deviceSerial()),
                request.assessment().riskLevel(),
                String.join("；", request.assessment().reasons()),
                request.assessment().impact(),
                expiresAt);
    }

    /**
     * 校验并消费确认令牌，供模型携带原命令二次调用时使用。
     *
     * @param token 确认令牌
     * @param check 绑定信息
     */
    public void verifyAndConsume(String token, AdbConfirmationCheck check) {
        PendingConfirmation pending = pending(token);
        validateEntry(pending.entry(), check);
        confirmations.put(hash(token), new PendingConfirmation(used(pending.entry()), pending.plan()));
    }

    /**
     * 根据确认令牌消费并返回原始命令计划，供前端确认卡片直接执行。
     *
     * @param token 确认令牌
     * @param conversationId 对话 ID
     * @return 令牌绑定的命令计划
     */
    public AdbCommandPlan consumePlan(String token, String conversationId) {
        PendingConfirmation pending = pending(token);
        AdbConfirmationCheck check = new AdbConfirmationCheck(
                conversationId,
                pending.plan().deviceSerial(),
                argsHash(pending.plan()),
                pending.entry().riskLevel());
        validateEntry(pending.entry(), check);
        confirmations.put(hash(token), new PendingConfirmation(used(pending.entry()), pending.plan()));
        return pending.plan();
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
            throw new BusinessException("ADB_CONFIRMATION_MISMATCH", "确认令牌与当前对话不匹配", HttpStatus.CONFLICT, "");
        }
        confirmations.put(hash(token), new PendingConfirmation(canceled(pending.entry()), pending.plan()));
    }

    /**
     * 计算命令计划的参数 hash。
     *
     * @param plan 命令计划
     * @return 参数 hash
     */
    public String argsHash(AdbCommandPlan plan) {
        return hash(String.join("\u0000", plan.adbArguments()));
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
            throw new BusinessException("ADB_CONFIRMATION_EXPIRED", "确认令牌不存在或已过期", HttpStatus.CONFLICT, "");
        }
        if (pending.entry().status() == AdbConfirmationStatus.USED) {
            throw new BusinessException("ADB_CONFIRMATION_USED", "确认令牌已使用", HttpStatus.CONFLICT, "");
        }
        if (pending.entry().status() == AdbConfirmationStatus.CANCELED) {
            throw new BusinessException("ADB_CONFIRMATION_CANCELED", "确认令牌已取消", HttpStatus.CONFLICT, "");
        }
        if (Instant.now().isAfter(pending.entry().expiresAt())) {
            throw new BusinessException("ADB_CONFIRMATION_EXPIRED", "确认令牌已过期", HttpStatus.CONFLICT, "");
        }
        return pending;
    }

    /**
     * 校验令牌绑定信息，防止模型修改命令后复用旧令牌。
     *
     * @param entry 令牌记录
     * @param check 校验信息
     */
    private void validateEntry(AdbConfirmationEntry entry, AdbConfirmationCheck check) {
        boolean matched = entry.conversationId().equals(check.conversationId())
                && entry.deviceSerialHash().equals(hash(check.deviceSerial()))
                && entry.adbArgsHash().equals(check.adbArgsHash())
                && entry.riskLevel() == check.riskLevel();
        if (!matched) {
            throw new BusinessException("ADB_CONFIRMATION_MISMATCH", "确认令牌与命令不匹配", HttpStatus.CONFLICT, "");
        }
    }

    /**
     * 生成已使用状态的新记录，保持 record 不可变。
     *
     * @param entry 原记录
     * @return 已使用记录
     */
    private AdbConfirmationEntry used(AdbConfirmationEntry entry) {
        return new AdbConfirmationEntry(
                entry.tokenHash(),
                entry.conversationId(),
                entry.deviceSerialHash(),
                entry.adbArgsHash(),
                entry.riskLevel(),
                entry.expiresAt(),
                AdbConfirmationStatus.USED);
    }

    /**
     * 生成已取消状态的新记录，保持 record 不可变。
     *
     * @param entry 原记录
     * @return 已取消记录
     */
    private AdbConfirmationEntry canceled(AdbConfirmationEntry entry) {
        return new AdbConfirmationEntry(
                entry.tokenHash(),
                entry.conversationId(),
                entry.deviceSerialHash(),
                entry.adbArgsHash(),
                entry.riskLevel(),
                entry.expiresAt(),
                AdbConfirmationStatus.CANCELED);
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
    private record PendingConfirmation(AdbConfirmationEntry entry, AdbCommandPlan plan) {
    }
}
