package com.devbridge.server.ai.security.egress;

import com.devbridge.server.ai.config.AiRuntimeConfig;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Assessment;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Classification;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Context;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Decision;
import com.devbridge.server.ai.security.egress.AiDataEgressPolicy.Item;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 模型数据外发分类策略，所有 Provider 调用在网络请求前必须经过该服务。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AiDataEgressPolicyService {

    /**
     * 根据数据分类和 Provider 位置计算外发决策。
     *
     * @param config Provider 运行时配置
     * @param context 外发上下文
     * @return 外发评估结果
     */
    public Assessment assess(AiRuntimeConfig config, Context context) {
        if (config == null || context == null) {
            throw new IllegalArgumentException("模型外发配置和上下文不能为空");
        }
        List<Item> items = context.items();
        boolean localModel = isLoopback(config.apiUrl());
        Decision decision = decision(items, localModel);
        List<String> dataTypes = items.stream()
                .map(item -> item.dataType().name())
                .distinct()
                .sorted()
                .toList();
        List<String> maskedTypes = items.stream()
                .filter(Item::masked)
                .map(item -> item.dataType().name())
                .distinct()
                .sorted()
                .toList();
        long totalBytes = items.stream().mapToLong(Item::byteCount).sum();
        String digest = digest(config, context, items);
        return new Assessment(
                decision,
                config.provider().getValue(),
                config.model(),
                dataTypes,
                totalBytes,
                maskedTypes,
                digest,
                reason(decision, localModel));
    }

    /**
     * 按最严格分类计算决策；禁止类对本地模型同样生效。
     *
     * @param items 数据项
     * @param localModel 是否本地模型
     * @return 最终决策
     */
    private Decision decision(List<Item> items, boolean localModel) {
        if (items.stream().anyMatch(item -> item.classification() == Classification.PROHIBITED)) {
            return Decision.BLOCK;
        }
        if (items.stream().anyMatch(item -> item.classification() == Classification.LOCAL_MODEL_ONLY)) {
            return localModel ? Decision.ALLOW : Decision.BLOCK;
        }
        // 用户已明确选择取消二次确认，普通敏感数据可直接发往已配置 Provider。
        return Decision.ALLOW;
    }

    /**
     * 判断 Provider 是否真正位于当前进程可见的回环地址，私网服务不自动视为本地模型。
     *
     * @param apiUrl Provider API URL
     * @return 回环地址返回 true
     */
    private boolean isLoopback(String apiUrl) {
        try {
            String host = new URI(apiUrl).getHost();
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    /**
     * 计算与 Provider、模型、用途、类型、大小和正文摘要绑定的稳定确认摘要。
     *
     * @param config Provider 配置
     * @param context 外发上下文
     * @param items 数据项
     * @return SHA-256 十六进制摘要
     */
    private String digest(AiRuntimeConfig config, Context context, List<Item> items) {
        StringBuilder value = new StringBuilder()
                .append(config.provider().getValue()).append('\n')
                .append(config.model()).append('\n')
                .append(context.purpose()).append('\n');
        items.stream().sorted(Comparator.comparing(item -> item.dataType().name()))
                .forEach(item -> value.append(item.dataType()).append('|')
                        .append(item.classification()).append('|')
                        .append(item.byteCount()).append('|')
                        .append(item.contentDigest()).append('\n'));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    /**
     * 生成不含正文的策略原因，供前端和事件流展示。
     *
     * @param decision 决策
     * @param localModel 是否本地模型
     * @return 决策原因
     */
    private String reason(Decision decision, boolean localModel) {
        return switch (decision) {
            case ALLOW -> localModel ? "数据仅发送到本地回环模型" : "数据分类允许发送到当前 Provider";
            case CONFIRM -> "敏感数据发送到外部 Provider 前需要用户确认";
            case BLOCK -> "数据包含禁止外发或仅允许本地模型处理的内容";
        };
    }
}
