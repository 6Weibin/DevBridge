package com.devbridge.server.ai.config;

import com.devbridge.server.model.BusinessException;
import java.util.Arrays;
import org.springframework.http.HttpStatus;

/**
 * AI Provider 类型枚举，统一前端配置值和后端模型调用分支。
 *
 * <p>by AI.Coding</p>
 */
public enum AiProviderType {

    OPENAI("openai"),
    DEEPSEEK("deepseek"),
    QWEN("qwen"),
    GLM("glm"),
    ERNIE("ernie"),
    CUSTOM_OPENAI_COMPATIBLE("custom-openai-compatible");

    private final String value;

    /**
     * 创建 Provider 类型。
     *
     * @param value 前端配置值
     */
    AiProviderType(String value) {
        this.value = value;
    }

    /**
     * 获取前端配置值。
     *
     * @return Provider 配置值
     */
    public String getValue() {
        return value;
    }

    /**
     * 将前端字符串转换成 Provider 类型，保证错误码稳定。
     *
     * @param value 前端提交的 Provider 值
     * @return Provider 类型
     */
    public static AiProviderType fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equalsIgnoreCase(value == null ? "" : value.trim()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "AI_CONFIG_INVALID",
                        "Provider 类型不支持",
                        HttpStatus.BAD_REQUEST,
                        value));
    }
}
