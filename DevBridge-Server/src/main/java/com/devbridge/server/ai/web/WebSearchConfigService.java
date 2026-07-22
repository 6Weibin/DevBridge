package com.devbridge.server.ai.web;

import com.devbridge.server.ai.config.AiConfigCrypto;
import com.devbridge.server.ai.storage.AiDataMaintenanceLock;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 网络检索配置服务，使用独立轻量文件保存全局 Tavily 配置。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class WebSearchConfigService {

    public static final String DEFAULT_API_URL = "https://api.tavily.com/search";
    private static final String CONFIG_FILENAME = "web-search-config.json";
    private static final int DEFAULT_RESULT_COUNT = 5;
    private static final int MAX_RESULT_COUNT = 10;

    private final Path configRoot;
    private final AiConfigCrypto crypto;
    private final ObjectMapper objectMapper;
    private final AiDataMaintenanceLock maintenanceLock;

    /** 注入配置目录、加密工具和维护锁。 */
    public WebSearchConfigService(
            DevBridgeProperties properties,
            AiConfigCrypto crypto,
            ObjectMapper objectMapper,
            AiDataMaintenanceLock maintenanceLock) {
        this.configRoot = Path.of(properties.getAiConfigRoot());
        this.crypto = crypto;
        this.objectMapper = objectMapper;
        this.maintenanceLock = maintenanceLock;
    }

    /** 返回配置详情；明文 API Key 仅供本机设置页面显式读取。 */
    public WebSearchConfigDetail detail(boolean revealApiKey) {
        return maintenanceLock.read(() -> detailUnlocked(revealApiKey));
    }

    /** 保存已校验配置，并加密 API Key。 */
    public WebSearchConfigDetail save(WebSearchConfigRequest request) {
        return maintenanceLock.read(() -> saveUnlocked(request));
    }

    /** 返回可执行的已启用配置。 */
    public WebSearchRuntimeConfig requireEnabled() {
        return maintenanceLock.read(this::requireEnabledUnlocked);
    }

    /** 校验临时配置，供连接测试使用。 */
    public WebSearchRuntimeConfig runtimeFrom(WebSearchConfigRequest request) {
        return validate(request, true);
    }

    /** 读取并转换配置详情。 */
    private WebSearchConfigDetail detailUnlocked(boolean revealApiKey) {
        StoredWebSearchConfig stored = readStored();
        if (stored == null) {
            return new WebSearchConfigDetail(false, false, "tavily", DEFAULT_API_URL, "", DEFAULT_RESULT_COUNT, null);
        }
        String plainKey = StringUtils.hasText(stored.encryptedApiKey())
                ? crypto.decrypt(configRoot, stored.encryptedApiKey()) : "";
        return new WebSearchConfigDetail(
                true, stored.enabled(), "tavily", stored.apiUrl(),
                revealApiKey ? plainKey : mask(plainKey), stored.defaultResultCount(), stored.updatedAt());
    }

    /** 在维护锁内完成配置写入。 */
    private WebSearchConfigDetail saveUnlocked(WebSearchConfigRequest request) {
        WebSearchRuntimeConfig validated = validate(request, request != null && request.enabled());
        StoredWebSearchConfig stored = new StoredWebSearchConfig(
                validated.enabled(), validated.apiUrl(),
                StringUtils.hasText(validated.apiKey()) ? crypto.encrypt(configRoot, validated.apiKey()) : "",
                validated.defaultResultCount(), Instant.now());
        try {
            Files.createDirectories(configRoot);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath().toFile(), stored);
            return detailUnlocked(false);
        } catch (IOException ex) {
            throw new BusinessException("WEB_SEARCH_CONFIG_SAVE_FAILED", "网络检索配置保存失败",
                    HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    /** 读取启用配置，未配置时返回稳定业务错误。 */
    private WebSearchRuntimeConfig requireEnabledUnlocked() {
        WebSearchConfigDetail detail = detailUnlocked(true);
        if (!detail.configured() || !detail.enabled()) {
            throw new BusinessException("WEB_SEARCH_NOT_CONFIGURED", "网络检索尚未启用",
                    HttpStatus.CONFLICT, "请在 AI 配置中启用并保存 Tavily 配置");
        }
        return new WebSearchRuntimeConfig(true, detail.apiUrl(), detail.apiKey(), detail.defaultResultCount());
    }

    /** 校验 URL、密钥和结果数量。 */
    private WebSearchRuntimeConfig validate(WebSearchConfigRequest request, boolean requireKey) {
        if (request == null) {
            throw invalid("网络检索配置不能为空", "");
        }
        String apiUrl = StringUtils.hasText(request.apiUrl()) ? request.apiUrl().trim() : DEFAULT_API_URL;
        String apiKey = request.apiKey() == null ? "" : request.apiKey().trim();
        int count = request.defaultResultCount() <= 0 ? DEFAULT_RESULT_COUNT : request.defaultResultCount();
        validateUrl(apiUrl);
        if (requireKey && !StringUtils.hasText(apiKey)) {
            throw invalid("Tavily API Key 不能为空", "");
        }
        if (count > MAX_RESULT_COUNT) {
            throw invalid("默认搜索结果数不能超过 " + MAX_RESULT_COUNT, String.valueOf(count));
        }
        return new WebSearchRuntimeConfig(request.enabled(), apiUrl, apiKey, count);
    }

    /** 公网配置必须使用 HTTPS，本机自建代理允许 HTTP。 */
    private void validateUrl(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean localHttp = "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host));
            if (!StringUtils.hasText(host) || (!https && !localHttp)) {
                throw invalid("搜索 API URL 仅允许 HTTPS 或本机 HTTP", value);
            }
        } catch (URISyntaxException ex) {
            throw invalid("搜索 API URL 格式不正确", ex.getMessage());
        }
    }

    /** 读取本地配置文件。 */
    private StoredWebSearchConfig readStored() {
        if (!Files.exists(configPath())) return null;
        try {
            return objectMapper.readValue(configPath().toFile(), StoredWebSearchConfig.class);
        } catch (IOException ex) {
            throw new BusinessException("WEB_SEARCH_CONFIG_READ_FAILED", "网络检索配置读取失败",
                    HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    /** 返回配置文件路径。 */
    private Path configPath() {
        return configRoot.resolve(CONFIG_FILENAME);
    }

    /** 对普通详情查询隐藏密钥中间内容。 */
    private String mask(String value) {
        if (!StringUtils.hasText(value)) return "";
        if (value.length() <= 8) return "********";
        return value.substring(0, 4) + "********" + value.substring(value.length() - 4);
    }

    /** 构造统一配置错误。 */
    private BusinessException invalid(String message, String detail) {
        return new BusinessException("WEB_SEARCH_CONFIG_INVALID", message, HttpStatus.BAD_REQUEST, detail);
    }

    /** 网络检索配置保存请求。by AI.Coding */
    public record WebSearchConfigRequest(boolean enabled, String apiUrl, String apiKey, int defaultResultCount) {
    }

    /** 网络检索配置详情。by AI.Coding */
    public record WebSearchConfigDetail(
            boolean configured, boolean enabled, String provider, String apiUrl,
            String apiKey, int defaultResultCount, Instant updatedAt) {
    }

    /** 网络检索运行时配置。by AI.Coding */
    public record WebSearchRuntimeConfig(boolean enabled, String apiUrl, String apiKey, int defaultResultCount) {
    }

    /** 本地加密配置结构。by AI.Coding */
    private record StoredWebSearchConfig(
            boolean enabled, String apiUrl, String encryptedApiKey, int defaultResultCount, Instant updatedAt) {
    }
}
