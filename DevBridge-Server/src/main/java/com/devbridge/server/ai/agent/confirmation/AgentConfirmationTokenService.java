package com.devbridge.server.ai.agent.confirmation;

import com.devbridge.server.ai.config.AiConfigCrypto;
import com.devbridge.server.config.DevBridgeProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;

/**
 * Agent 确认授权令牌签发与校验服务，签名绑定会话、任务、步骤、工具和参数摘要。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AgentConfirmationTokenService {

    private final AiConfigCrypto crypto;
    private final Path keyRoot;

    /** 注入持久密钥边界和配置目录。 */
    public AgentConfirmationTokenService(AiConfigCrypto crypto, DevBridgeProperties properties) {
        this.crypto = crypto;
        this.keyRoot = Path.of(properties.getAiConfigRoot());
    }

    /** 为确认记录签发不可伪造的授权令牌。 */
    public String issue(AgentConfirmation confirmation) {
        return crypto.sign(keyRoot, payload(confirmation));
    }

    /** 使用常量时间比较校验授权令牌。 */
    public boolean matches(AgentConfirmation confirmation, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                issue(confirmation).getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    /** 构造稳定签名载荷，任何绑定字段变化都会使旧令牌失效。 */
    private String payload(AgentConfirmation confirmation) {
        AgentConfirmationBinding binding = confirmation.binding();
        return String.join("\0",
                confirmation.taskId(), confirmation.confirmationId(), binding.conversationId(),
                binding.stepId(), binding.toolCallId(), binding.toolId(), binding.argumentDigest(),
                confirmation.expiresAt().toString());
    }
}
