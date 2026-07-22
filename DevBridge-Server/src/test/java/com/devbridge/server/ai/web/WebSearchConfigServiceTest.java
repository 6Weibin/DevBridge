package com.devbridge.server.ai.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.config.AiConfigCrypto;
import com.devbridge.server.ai.storage.AiDataMaintenanceLock;
import com.devbridge.server.ai.web.WebSearchConfigService.WebSearchConfigRequest;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 网络检索配置加密存储测试。
 *
 * <p>by AI.Coding</p>
 */
class WebSearchConfigServiceTest {

    @TempDir
    Path tempDir;

    /** 保存后可回填明文，但配置文件中不能出现 API Key。 */
    @Test
    void shouldEncryptApiKeyAtRest() throws Exception {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiConfigRoot(tempDir.toString());
        WebSearchConfigService service = new WebSearchConfigService(
                properties, new AiConfigCrypto(), new ObjectMapper().findAndRegisterModules(),
                new AiDataMaintenanceLock());

        service.save(new WebSearchConfigRequest(true, WebSearchConfigService.DEFAULT_API_URL, "tvly-secret", 5));

        assertThat(service.detail(true).apiKey()).isEqualTo("tvly-secret");
        assertThat(Files.readString(tempDir.resolve("web-search-config.json"))).doesNotContain("tvly-secret");
    }
}
