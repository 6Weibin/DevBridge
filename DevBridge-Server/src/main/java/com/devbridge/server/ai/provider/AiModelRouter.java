package com.devbridge.server.ai.provider;

import com.devbridge.server.ai.config.AiRuntimeConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 已配置模型的轻量路由器，按能力、成本和短期可用性生成安全尝试顺序。
 *
 * <p>当前模型保持首选；只有出现可重试 Provider 故障后，备用模型才会进入短期优先级。
 * 不保存密钥、Prompt 或业务正文。</p>
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AiModelRouter {

    private static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(30);
    private final Map<String, Instant> unavailableUntil = new ConcurrentHashMap<>();

    /**
     * 对当前模型和能力兼容备用模型排序。
     *
     * @param active 用户当前选择模型
     * @param fallbacks 已配置兼容备用模型
     * @return 带选型原因的稳定路由顺序
     */
    public List<RouteCandidate> route(AiRuntimeConfig active, List<AiRuntimeConfig> fallbacks) {
        List<AiRuntimeConfig> alternatives = new ArrayList<>(fallbacks == null ? List.of() : fallbacks);
        alternatives.sort(Comparator
                .<AiRuntimeConfig>comparingInt(value -> available(value) ? 1 : 0).reversed()
                .thenComparing(Comparator.comparingInt(this::qualityScore).reversed())
                .thenComparing(Comparator.comparingInt(this::costScore).reversed())
                .thenComparing(value -> key(value)));
        List<RouteCandidate> result = new ArrayList<>();
        if (available(active) || alternatives.stream().noneMatch(this::available)) {
            result.add(new RouteCandidate(active, "用户当前模型；能力匹配且可用"));
        }
        for (AiRuntimeConfig alternative : alternatives) {
            result.add(new RouteCandidate(alternative, routeReason(alternative)));
        }
        if (result.stream().noneMatch(value -> key(value.config()).equals(key(active)))) {
            result.add(new RouteCandidate(active, "当前模型处于短期故障冷却，保留为末位重试"));
        }
        return List.copyOf(result);
    }

    /** 记录一次模型调用成功并清除短期故障状态。 */
    public void recordSuccess(AiRuntimeConfig config) {
        unavailableUntil.remove(key(config));
    }

    /** 记录可重试故障，短时间内优先使用健康备用模型。 */
    public void recordRetryableFailure(AiRuntimeConfig config) {
        unavailableUntil.put(key(config), Instant.now().plus(FAILURE_COOLDOWN));
    }

    /** 判断模型是否未处于故障冷却。 */
    private boolean available(AiRuntimeConfig config) {
        Instant until = unavailableUntil.get(key(config));
        if (until == null || !until.isAfter(Instant.now())) {
            unavailableUntil.remove(key(config));
            return true;
        }
        return false;
    }

    /** 使用能力上限表达质量，避免维护厂商专属硬编码排名。 */
    private int qualityScore(AiRuntimeConfig config) {
        var capability = config.capability();
        int score = capability.limits().contextWindowTokens() / 16_000;
        score += capability.limits().maxOutputTokens() / 4_000;
        score += capability.toolCalling() ? 8 : 0;
        score += capability.multimodal() ? 4 : 0;
        return score;
    }

    /** 对常见轻量模型给出成本偏好；未知模型保持中性。 */
    private int costScore(AiRuntimeConfig config) {
        String model = config.model().toLowerCase(Locale.ROOT);
        if (containsAny(model, "mini", "flash", "lite", "turbo", "air")) {
            return 3;
        }
        if (containsAny(model, "max", "plus", "pro", "reasoner", "r1")) {
            return 1;
        }
        return 2;
    }

    /** 生成不含地址和密钥的选型原因。 */
    private String routeReason(AiRuntimeConfig config) {
        return available(config)
                ? "能力兼容备用模型；质量=" + qualityScore(config) + "，成本偏好=" + costScore(config)
                : "能力兼容备用模型，但处于短期故障冷却";
    }

    /** 生成模型健康状态键。 */
    private String key(AiRuntimeConfig config) {
        return config.provider().getValue() + ":" + config.model();
    }

    /** 判断模型名是否包含任一成本特征。 */
    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** 模型路由候选及可审计选型原因。by AI.Coding */
    public record RouteCandidate(AiRuntimeConfig config, String reason) {
    }
}
