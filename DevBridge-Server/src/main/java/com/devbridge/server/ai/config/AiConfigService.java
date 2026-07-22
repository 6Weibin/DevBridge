package com.devbridge.server.ai.config;

import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.ai.storage.AiDataMaintenanceLock;
import com.devbridge.server.model.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 配置服务，负责配置校验、密钥加密存储和状态脱敏。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AiConfigService {

    private static final String CONFIG_FILENAME = "ai-config.json";
    private static final int MAX_AUTHORIZATION_RULES = 100;
    private static final int MAX_AUTHORIZATION_COMMAND_LENGTH = 300;
    private static final int MAX_USER_PREFERENCE_PROMPT_LENGTH = 12_000;

    private final DevBridgeProperties properties;
    private final AiConfigCrypto crypto;
    private final ObjectMapper objectMapper;
    private final AiModelCapabilityRegistry capabilityRegistry;
    private final AiDataMaintenanceLock maintenanceLock;

    /**
     * 注入 AI 配置依赖。
     *
     * @param properties DevBridge 配置
     * @param crypto API Key 加解密工具
     * @param objectMapper JSON 序列化工具
     * @param capabilityRegistry 模型能力注册表
     */
    @Autowired
    public AiConfigService(
            DevBridgeProperties properties,
            AiConfigCrypto crypto,
            ObjectMapper objectMapper,
            AiModelCapabilityRegistry capabilityRegistry,
            AiDataMaintenanceLock maintenanceLock) {
        this.properties = properties;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
        this.capabilityRegistry = capabilityRegistry;
        this.maintenanceLock = maintenanceLock;
    }

    /** 兼容现有显式装配方式，并使用独立维护锁。 */
    public AiConfigService(
            DevBridgeProperties properties,
            AiConfigCrypto crypto,
            ObjectMapper objectMapper,
            AiModelCapabilityRegistry capabilityRegistry) {
        this(properties, crypto, objectMapper, capabilityRegistry, new AiDataMaintenanceLock());
    }

    /**
     * 兼容测试和显式创建方式。
     *
     * @param properties DevBridge 配置
     * @param crypto API Key 加解密工具
     * @param objectMapper JSON 序列化工具
     */
    public AiConfigService(DevBridgeProperties properties, AiConfigCrypto crypto, ObjectMapper objectMapper) {
        this(properties, crypto, objectMapper, new AiModelCapabilityRegistry());
    }

    /**
     * 获取脱敏配置状态；未配置时返回空状态，前端据此打开配置弹窗。
     *
     * @return AI 配置状态
     */
    public AiConfigStatus status() {
        return maintenanceLock.read(this::statusUnlocked);
    }

    /** 读取当前配置状态，调用方已持有维护读锁。 */
    private AiConfigStatus statusUnlocked() {
        StoredAiConfig config = readStoredConfig();
        StoredProviderConfig providerConfig = activeProviderConfig(config);
        if (config == null || providerConfig == null) {
            return AiConfigStatus.empty();
        }
        return new AiConfigStatus(true, config.activeProvider(), providerConfig.model(), host(providerConfig.apiUrl()), providerConfig.updatedAt());
    }

    /**
     * 获取配置详情；该方法会解密 API Key，只供本机配置页面回填使用。
     *
     * @return AI 配置详情
     */
    public AiConfigDetail detail() {
        return maintenanceLock.read(() -> detailUnlocked("", true));
    }

    /** 读取配置详情，调用方已持有维护读锁。 */
    private AiConfigDetail detailUnlocked(String providerValue, boolean revealApiKey) {
        if (StringUtils.hasText(providerValue)) {
            return providerDetailUnlocked(providerValue, revealApiKey);
        }
        StoredAiConfig config = readStoredConfig();
        if (config == null) {
            return AiConfigDetail.empty();
        }
        return providerDetailUnlocked(config.activeProvider(), revealApiKey);
    }

    /**
     * 获取指定 Provider 的配置详情；切换 Provider 时用于回填对应配置。
     *
     * @param providerValue Provider 配置值
     * @return AI 配置详情
     */
    public AiConfigDetail detail(String providerValue) {
        return detail(providerValue, true);
    }

    /**
     * 获取指定 Provider 配置；API Key 只有设置页面显式请求时才解密返回。
     *
     * @param providerValue Provider 配置值
     * @param revealApiKey 是否显式揭示 API Key
     * @return AI 配置详情
     */
    public AiConfigDetail detail(String providerValue, boolean revealApiKey) {
        return maintenanceLock.read(() -> detailUnlocked(providerValue, revealApiKey));
    }

    /** 读取指定 Provider 配置，调用方已持有维护读锁。 */
    private AiConfigDetail providerDetailUnlocked(String providerValue, boolean revealApiKey) {
        if (!StringUtils.hasText(providerValue)) {
            StoredAiConfig current = readStoredConfig();
            return current == null ? AiConfigDetail.empty()
                    : providerDetailUnlocked(current.activeProvider(), revealApiKey);
        }
        AiProviderType provider = AiProviderType.fromValue(providerValue);
        StoredAiConfig config = readStoredConfig();
        StoredProviderConfig providerConfig = config == null ? null : config.providers().get(provider.getValue());
        if (providerConfig == null) {
            return new AiConfigDetail(
                    false,
                    provider.getValue(),
                    "",
                    "",
                    "",
                    userPreferencePrompt(config),
                    authorizationRules(config),
                    null);
        }
        // 仅在配置详情接口中解密，避免普通状态接口暴露密钥。
        String plainApiKey = crypto.decrypt(configRoot(), providerConfig.encryptedApiKey());
        String apiKey = revealApiKey ? plainApiKey : maskedApiKey(plainApiKey);
        return new AiConfigDetail(
                true,
                provider.getValue(),
                providerConfig.apiUrl(),
                apiKey,
                providerConfig.model(),
                userPreferencePrompt(config),
                authorizationRules(config),
                providerConfig.updatedAt());
    }

    /** 默认详情只保留首尾少量字符，避免普通配置查询暴露完整密钥。 */
    private String maskedApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "********";
        }
        return apiKey.substring(0, 4) + "********" + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * 保存 AI 配置；API Key 先加密再落盘，避免配置文件出现明文密钥。
     *
     * @param request 配置请求
     * @return 脱敏配置状态
     */
    public AiConfigStatus save(AiConfigRequest request) {
        return maintenanceLock.read(() -> saveUnlocked(request));
    }

    /** 在业务维护读锁内完成配置读改写，避免备份捕获不一致文件。 */
    private AiConfigStatus saveUnlocked(AiConfigRequest request) {
        ValidatedAiConfig validated = validate(request, true);
        Path root = configRoot();
        Map<String, StoredProviderConfig> providers = new LinkedHashMap<>();
        StoredAiConfig existing = readStoredConfig();
        if (existing != null && existing.providers() != null) {
            providers.putAll(existing.providers());
        }
        // 每个 Provider 独立保存配置，切换 Provider 时不会复用当前 Provider 的 URL/Key/模型。
        providers.put(validated.provider().getValue(), new StoredProviderConfig(
                validated.apiUrl(),
                validated.model(),
                crypto.encrypt(root, validated.apiKey()),
                Instant.now()));
        StoredAiConfig config = new StoredAiConfig(
                validated.provider().getValue(),
                providers,
                validated.userPreferencePrompt(),
                validated.localShellAuthorizations());
        try {
            Files.createDirectories(root);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath().toFile(), config);
            return statusUnlocked();
        } catch (IOException ex) {
            throw new BusinessException("AI_CONFIG_SAVE_FAILED", "AI 配置保存失败", HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    /**
     * 获取可调用 Provider 的运行时配置；未配置时返回稳定业务错误。
     *
     * @return AI 运行时配置
     */
    public AiRuntimeConfig requireConfigured() {
        return maintenanceLock.read(this::requireConfiguredUnlocked);
    }

    /** 读取运行时配置，调用方已持有维护读锁。 */
    private AiRuntimeConfig requireConfiguredUnlocked() {
        StoredAiConfig config = readStoredConfig();
        StoredProviderConfig providerConfig = activeProviderConfig(config);
        if (config == null || providerConfig == null) {
            throw new BusinessException("AI_NOT_CONFIGURED", "AI 尚未配置", HttpStatus.CONFLICT, "");
        }
        AiProviderType provider = AiProviderType.fromValue(config.activeProvider());
        return new AiRuntimeConfig(
                provider,
                providerConfig.apiUrl(),
                crypto.decrypt(configRoot(), providerConfig.encryptedApiKey()),
                providerConfig.model(),
                userPreferencePrompt(config),
                capabilityRegistry.resolve(provider, providerConfig.model()));
    }

    /**
     * 校验连接测试入参，不落盘；Provider 调用由上层服务执行。
     *
     * @param request 配置请求
     * @return 运行时配置
     */
    public AiRuntimeConfig runtimeFrom(AiConfigRequest request) {
        ValidatedAiConfig validated = validate(request, true);
        return new AiRuntimeConfig(
                validated.provider(),
                validated.apiUrl(),
                validated.apiKey(),
                validated.model(),
                validated.userPreferencePrompt(),
                capabilityRegistry.resolve(validated.provider(), validated.model()));
    }

    /**
     * 获取 Local Shell MCP 命令授权规则；未配置时返回空列表，避免执行策略依赖 null 判断。
     *
     * @return Local Shell MCP 命令授权规则
     */
    public List<AiCommandAuthorizationRule> localShellAuthorizations() {
        return maintenanceLock.read(() -> authorizationRules(readStoredConfig()));
    }

    /**
     * 校验模型列表请求并构造临时运行时配置；模型列表拉取不要求用户先填写模型名称。
     *
     * @param request 模型列表请求
     * @return 临时运行时配置
     */
    public AiRuntimeConfig runtimeForModelList(AiModelListRequest request) {
        if (request == null) {
            throw invalid("AI 配置不能为空", "");
        }
        AiProviderType provider = AiProviderType.fromValue(request.provider());
        String apiUrl = required(request.apiUrl(), "API URL 不能为空");
        String apiKey = required(request.apiKey(), "API Key 不能为空");
        validateUrl(apiUrl);
        return new AiRuntimeConfig(
                provider, apiUrl, apiKey, "", AiPromptDefaults.DEFAULT_USER_PREFERENCE_PROMPT,
                capabilityRegistry.resolve(provider, ""));
    }

    /**
     * 返回与当前模型能力兼容的全部已配置备用 Provider，用于安全阶段路由和降级。
     *
     * @param active 当前运行时配置
     * @return 备用配置，不存在时返回空列表
     */
    public List<AiRuntimeConfig> compatibleFallbacks(AiRuntimeConfig active) {
        return maintenanceLock.read(() -> compatibleFallbacksUnlocked(active));
    }

    /** 读取备用 Provider，调用方已持有维护读锁。 */
    private List<AiRuntimeConfig> compatibleFallbacksUnlocked(AiRuntimeConfig active) {
        StoredAiConfig stored = readStoredConfig();
        if (stored == null || stored.providers() == null || active == null) {
            return List.of();
        }
        List<AiRuntimeConfig> fallbacks = new ArrayList<>();
        for (Map.Entry<String, StoredProviderConfig> entry : stored.providers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(active.provider().getValue())) {
                continue;
            }
            AiProviderType provider = AiProviderType.fromValue(entry.getKey());
            StoredProviderConfig value = entry.getValue();
            var capability = capabilityRegistry.resolve(provider, value.model());
            if (capabilityRegistry.compatible(active.capability(), capability)) {
                fallbacks.add(new AiRuntimeConfig(
                        provider, value.apiUrl(), crypto.decrypt(configRoot(), value.encryptedApiKey()),
                        value.model(), userPreferencePrompt(stored), capability));
            }
        }
        return List.copyOf(fallbacks);
    }

    /**
     * 读取本地配置文件；不存在时返回 null，损坏时抛出业务异常。
     *
     * @return 本地配置
     */
    private StoredAiConfig readStoredConfig() {
        Path path = configPath();
        if (!Files.exists(path)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(path.toFile());
            if (root.has("providers")) {
                return objectMapper.treeToValue(root, StoredAiConfig.class);
            }
            return legacyConfig(root);
        } catch (IOException ex) {
            throw new BusinessException("AI_CONFIG_READ_FAILED", "AI 配置读取失败", HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    /**
     * 获取当前激活 Provider 的配置；配置损坏或未配置时返回 null。
     *
     * @param config 本地配置集合
     * @return 当前 Provider 配置
     */
    private StoredProviderConfig activeProviderConfig(StoredAiConfig config) {
        if (config == null || !StringUtils.hasText(config.activeProvider()) || config.providers() == null) {
            return null;
        }
        return config.providers().get(config.activeProvider());
    }

    /**
     * 兼容旧版单 Provider 配置文件，读取后在下次保存时自动转换为多 Provider 结构。
     *
     * @param root 旧配置 JSON
     * @return 多 Provider 配置集合
     */
    private StoredAiConfig legacyConfig(JsonNode root) {
        String provider = text(root, "provider");
        if (!StringUtils.hasText(provider)) {
            return null;
        }
        Map<String, StoredProviderConfig> providers = new LinkedHashMap<>();
        providers.put(provider, new StoredProviderConfig(
                text(root, "apiUrl"),
                text(root, "model"),
                text(root, "encryptedApiKey"),
                objectMapper.convertValue(root.get("updatedAt"), Instant.class)));
        return new StoredAiConfig(provider, providers, normalizeUserPreference(text(root, "systemPrompt")), List.of());
    }

    /**
     * 从 JSON 中安全读取文本字段，缺失时返回空字符串。
     *
     * @param root JSON 根节点
     * @param field 字段名
     * @return 文本值
     */
    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    /**
     * 校验用户配置；URL 限定为 https 或本机/内网 http，降低 SSRF 和误配风险。
     *
     * @param request 配置请求
     * @param requireApiKey 是否要求 API Key
     * @return 已规范化配置
     */
    private ValidatedAiConfig validate(AiConfigRequest request, boolean requireApiKey) {
        if (request == null) {
            throw invalid("AI 配置不能为空", "");
        }
        AiProviderType provider = AiProviderType.fromValue(request.provider());
        String apiUrl = required(request.apiUrl(), "API URL 不能为空");
        String model = required(request.model(), "模型不能为空");
        String apiKey = requireApiKey ? required(request.apiKey(), "API Key 不能为空") : request.apiKey();
        String userPreferencePrompt = normalizeUserPreference(request.systemPrompt());
        List<AiCommandAuthorizationRule> authorizations = validateAuthorizationRules(request.localShellAuthorizations());
        validateUrl(apiUrl);
        return new ValidatedAiConfig(provider, apiUrl, apiKey, model, userPreferencePrompt, authorizations);
    }

    /**
     * 校验并规范化 Local Shell MCP 授权规则，防止异常配置扩大执行权限或撑大配置文件。
     *
     * @param rules 原始授权规则
     * @return 规范化后的授权规则
     */
    private List<AiCommandAuthorizationRule> validateAuthorizationRules(List<AiCommandAuthorizationRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<AiCommandAuthorizationRule> normalized = new ArrayList<>();
        for (AiCommandAuthorizationRule rule : rules) {
            if (rule == null || !StringUtils.hasText(rule.command())) {
                continue;
            }
            if (normalized.size() >= MAX_AUTHORIZATION_RULES) {
                throw invalid("授权命令最多支持 " + MAX_AUTHORIZATION_RULES + " 条", "");
            }
            normalized.add(normalizeAuthorizationRule(rule));
        }
        return List.copyOf(normalized);
    }

    /**
     * 规范化单条授权规则；等级统一转为大写，命令去除首尾空白后保存。
     *
     * @param rule 原始授权规则
     * @return 规范化授权规则
     */
    private AiCommandAuthorizationRule normalizeAuthorizationRule(AiCommandAuthorizationRule rule) {
        String command = rule.command().trim();
        if (command.length() > MAX_AUTHORIZATION_COMMAND_LENGTH) {
            throw invalid("授权命令长度不能超过 " + MAX_AUTHORIZATION_COMMAND_LENGTH + " 个字符", command);
        }
        String level = StringUtils.hasText(rule.level()) ? rule.level().trim().toUpperCase() : "";
        if (!"LOW".equals(level) && !"MEDIUM".equals(level) && !"HIGH".equals(level)) {
            throw invalid("授权命令安全等级仅支持低、中、高", level);
        }
        return new AiCommandAuthorizationRule(command, level);
    }

    /**
     * 校验必填文本并去除首尾空白。
     *
     * @param value 原始文本
     * @param message 错误提示
     * @return 规范化文本
     */
    private String required(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw invalid(message, "");
        }
        return value.trim();
    }

    /**
     * 获取配置中的用户偏好；旧版默认 System Prompt 会迁移为空偏好。
     *
     * @param config 本地配置
     * @return 用户偏好提示词
     */
    private String userPreferencePrompt(StoredAiConfig config) {
        return config == null ? AiPromptDefaults.DEFAULT_USER_PREFERENCE_PROMPT : normalizeUserPreference(config.systemPrompt());
    }

    /**
     * 从本地配置读取授权规则；旧配置没有该字段时返回空列表。
     *
     * @param config 本地配置
     * @return 授权规则
     */
    private List<AiCommandAuthorizationRule> authorizationRules(StoredAiConfig config) {
        if (config == null || config.localShellAuthorizations() == null) {
            return List.of();
        }
        return List.copyOf(config.localShellAuthorizations());
    }

    /**
     * 规范化用户偏好；用户不再能通过该字段替换不可变安全 Prompt。
     *
     * @param value 原始提示词
     * @return 有界用户偏好
     */
    private String normalizeUserPreference(String value) {
        if (!StringUtils.hasText(value) || AiPromptDefaults.DEFAULT_SYSTEM_PROMPT.equals(value.trim())) {
            return AiPromptDefaults.DEFAULT_USER_PREFERENCE_PROMPT;
        }
        String prompt = value.trim();
        if (prompt.length() > MAX_USER_PREFERENCE_PROMPT_LENGTH) {
            throw invalid("用户偏好提示词长度不能超过 " + MAX_USER_PREFERENCE_PROMPT_LENGTH + " 个字符", "");
        }
        return prompt;
    }

    /**
     * 校验 API URL 协议和主机；公网地址必须使用 HTTPS，本机和私网允许 HTTP。
     *
     * @param apiUrl API URL
     */
    private void validateUrl(String apiUrl) {
        try {
            URI uri = new URI(apiUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!StringUtils.hasText(scheme) || !StringUtils.hasText(host)) {
                throw invalid("API URL 格式不正确", apiUrl);
            }
            boolean https = "https".equalsIgnoreCase(scheme);
            boolean localHttp = "http".equalsIgnoreCase(scheme) && isLocalOrPrivateHost(host);
            if (!https && !localHttp) {
                throw new BusinessException("AI_API_URL_REJECTED", "API URL 仅允许 HTTPS 或本机/内网 HTTP", HttpStatus.BAD_REQUEST, host);
            }
        } catch (URISyntaxException ex) {
            throw invalid("API URL 格式不正确", ex.getMessage());
        }
    }

    /**
     * 判断主机是否为本机或私网地址，支持常见内网网段和 localhost。
     *
     * @param host 主机名
     * @return 本机或私网返回 true
     */
    private boolean isLocalOrPrivateHost(String host) {
        String value = host.toLowerCase();
        return "localhost".equals(value)
                || "127.0.0.1".equals(value)
                || "::1".equals(value)
                || value.startsWith("10.")
                || value.startsWith("192.168.")
                || value.matches("172\\.(1[6-9]|2\\d|3[0-1])\\..*");
    }

    /**
     * 提取 API URL 主机作为状态摘要，避免前端看到完整 URL 中的敏感路径。
     *
     * @param apiUrl API URL
     * @return 主机摘要
     */
    private String host(String apiUrl) {
        try {
            return new URI(apiUrl).getHost();
        } catch (URISyntaxException ex) {
            return "";
        }
    }

    /**
     * 构造统一配置错误。
     *
     * @param message 用户提示
     * @param detail 诊断摘要
     * @return 业务异常
     */
    private BusinessException invalid(String message, String detail) {
        return new BusinessException("AI_CONFIG_INVALID", message, HttpStatus.BAD_REQUEST, detail);
    }

    /**
     * 获取 AI 配置根目录。
     *
     * @return 配置根目录
     */
    private Path configRoot() {
        return Path.of(properties.getAiConfigRoot());
    }

    /**
     * 获取配置文件路径。
     *
     * @return 配置文件路径
     */
    private Path configPath() {
        return configRoot().resolve(CONFIG_FILENAME);
    }

    /**
     * 已校验配置对象，减少后续方法参数数量。
     *
     * <p>by AI.Coding</p>
     *
     * @param provider Provider 类型
     * @param apiUrl API URL
     * @param apiKey API Key
     * @param model 模型名称
     * @param userPreferencePrompt 用户偏好提示词
     * @param localShellAuthorizations Local Shell MCP 命令授权规则
     */
    private record ValidatedAiConfig(
            AiProviderType provider,
            String apiUrl,
            String apiKey,
            String model,
            String userPreferencePrompt,
            List<AiCommandAuthorizationRule> localShellAuthorizations) {
    }
}
