package com.devbridge.server.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AI 配置服务测试，覆盖密钥加密存储和状态脱敏。
 *
 * <p>by AI.Coding</p>
 */
class AiConfigServiceTest {

    /**
     * 验证保存配置后状态不回显 API Key，配置文件也不包含明文密钥。
     *
     * @param tempDir 临时目录
     * @throws Exception 文件读取失败时抛出
     */
    @Test
    void saveShouldEncryptApiKeyAndReturnMaskedStatus(@TempDir Path tempDir) throws Exception {
        AiConfigService service = service(tempDir);

        AiConfigStatus status = service.save(new AiConfigRequest(
                "openai",
                "https://api.openai.com",
                "sk-secret-value",
                "gpt-4o-mini"));

        assertThat(status.configured()).isTrue();
        assertThat(status.apiUrlHost()).isEqualTo("api.openai.com");
        assertThat(Files.readString(tempDir.resolve("ai-config.json"))).doesNotContain("sk-secret-value");
        assertThat(service.requireConfigured().apiKey()).isEqualTo("sk-secret-value");
    }

    /**
     * 验证配置详情可回填 API URL 和 API Key，满足本机配置页直接编辑。
     *
     * @param tempDir 临时目录
     */
    @Test
    void detailShouldReturnConfiguredApiUrlAndApiKey(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        service.save(new AiConfigRequest("openai", "https://api.openai.com", "sk-secret-value", "gpt-4o-mini"));

        AiConfigDetail detail = service.detail();

        assertThat(detail.configured()).isTrue();
        assertThat(detail.apiUrl()).isEqualTo("https://api.openai.com");
        assertThat(detail.apiKey()).isEqualTo("sk-secret-value");
        assertThat(detail.model()).isEqualTo("gpt-4o-mini");
    }

    /**
     * 验证多个 Provider 配置独立保存，切换 Provider 时可以读取各自配置。
     *
     * @param tempDir 临时目录
     */
    @Test
    void saveShouldKeepIndependentProviderConfigs(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        service.save(new AiConfigRequest("deepseek", "https://api.deepseek.com", "sk-deepseek", "deepseek-chat"));
        service.save(new AiConfigRequest("openai", "https://api.openai.com", "sk-openai", "gpt-4o-mini"));

        AiConfigDetail deepseek = service.detail("deepseek");
        AiConfigDetail openai = service.detail("openai");

        assertThat(service.status().provider()).isEqualTo("openai");
        assertThat(deepseek.apiUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(deepseek.apiKey()).isEqualTo("sk-deepseek");
        assertThat(deepseek.model()).isEqualTo("deepseek-chat");
        assertThat(openai.apiUrl()).isEqualTo("https://api.openai.com");
        assertThat(openai.apiKey()).isEqualTo("sk-openai");
        assertThat(openai.model()).isEqualTo("gpt-4o-mini");
    }

    /**
     * 验证旧 systemPrompt 字段按用户偏好保存，不再代表可替换的安全 Prompt。
     *
     * @param tempDir 临时目录
     */
    @Test
    void saveShouldPersistSystemPrompt(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        String prompt = "你是一个只用表格回复的设备分析助手。";

        service.save(new AiConfigRequest("openai", "https://api.openai.com", "sk-openai", "gpt-4o-mini", prompt));

        assertThat(service.detail().systemPrompt()).isEqualTo(prompt);
        assertThat(service.requireConfigured().userPreferencePrompt()).isEqualTo(prompt);
    }

    /**
     * 验证 Local Shell MCP 授权规则作为全局配置保存，并在详情接口中回填。
     *
     * @param tempDir 临时目录
     */
    @Test
    void saveShouldPersistLocalShellAuthorizationRules(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        List<AiCommandAuthorizationRule> rules = List.of(
                new AiCommandAuthorizationRule("git status", "low"),
                new AiCommandAuthorizationRule("rm target/tmp", "HIGH"));

        service.save(new AiConfigRequest("openai", "https://api.openai.com", "sk-openai", "gpt-4o-mini", "提示词", rules));

        assertThat(service.detail().localShellAuthorizations())
                .containsExactly(
                        new AiCommandAuthorizationRule("git status", "LOW"),
                        new AiCommandAuthorizationRule("rm target/tmp", "HIGH"));
        assertThat(service.localShellAuthorizations()).hasSize(2);
    }

    /**
     * 验证保存时会忽略空授权行，避免前端新增空行时阻断普通配置保存。
     *
     * @param tempDir 临时目录
     */
    @Test
    void saveShouldIgnoreBlankAuthorizationRules(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        List<AiCommandAuthorizationRule> rules = List.of(
                new AiCommandAuthorizationRule("   ", "LOW"),
                new AiCommandAuthorizationRule("pwd", "MEDIUM"));

        service.save(new AiConfigRequest("openai", "https://api.openai.com", "sk-openai", "gpt-4o-mini", "提示词", rules));

        assertThat(service.localShellAuthorizations())
                .containsExactly(new AiCommandAuthorizationRule("pwd", "MEDIUM"));
    }

    /**
     * 验证保存配置仍要求 API Key，避免前端提交空密钥覆盖有效配置。
     *
     * @param tempDir 临时目录
     */
    @Test
    void saveShouldRequireApiKey(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);

        assertThatThrownBy(() -> service.save(new AiConfigRequest("openai", "https://api.openai.com", "", "gpt-4o-mini")))
                .hasMessageContaining("API Key 不能为空");
    }

    /**
     * 验证模型列表请求不要求模型字段，满足用户先拉取模型再选择的配置流程。
     *
     * @param tempDir 临时目录
     */
    @Test
    void runtimeForModelListShouldNotRequireModel(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);

        AiRuntimeConfig config = service.runtimeForModelList(new AiModelListRequest(
                "openai",
                "https://api.openai.com",
                "sk-secret-value"));

        assertThat(config.provider()).isEqualTo(AiProviderType.OPENAI);
        assertThat(config.apiUrl()).isEqualTo("https://api.openai.com");
        assertThat(config.apiKey()).isEqualTo("sk-secret-value");
        assertThat(config.model()).isEmpty();
    }

    /**
     * 验证模型列表请求仍要求 API Key，避免向 Provider 发起无效匿名请求。
     *
     * @param tempDir 临时目录
     */
    @Test
    void runtimeForModelListShouldRequireApiKey(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);

        assertThatThrownBy(() -> service.runtimeForModelList(new AiModelListRequest("openai", "https://api.openai.com", "")))
                .hasMessageContaining("API Key 不能为空");
    }

    /**
     * 创建测试用配置服务。
     *
     * @param root 配置根目录
     * @return 配置服务
     */
    private AiConfigService service(Path root) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiConfigRoot(root.toString());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new AiConfigService(properties, new AiConfigCrypto(), mapper);
    }
}
