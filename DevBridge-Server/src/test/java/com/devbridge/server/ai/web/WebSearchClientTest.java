package com.devbridge.server.ai.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.ai.web.WebSearchClient.SearchRequest;
import com.devbridge.server.ai.web.WebSearchConfigService.WebSearchRuntimeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tavily 搜索协议解析测试。
 *
 * <p>by AI.Coding</p>
 */
class WebSearchClientTest {

    /** 本地伪服务返回的搜索结果应转换为稳定结构。 */
    @Test
    void shouldParseTavilyResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            byte[] body = ("{\"results\":[{\"title\":\"Android Docs\","
                    + "\"url\":\"https://developer.android.com/\",\"content\":\"Official docs\","
                    + "\"score\":0.9}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WebSearchClient client = new WebSearchClient(
                    new ObjectMapper(), new WebUrlGuard(), new SensitiveDataMasker());
            var result = client.search(new WebSearchRuntimeConfig(
                    true, "http://127.0.0.1:" + server.getAddress().getPort() + "/search", "key", 5),
                    new SearchRequest("Android API", 1));

            assertThat(result.results()).hasSize(1);
            assertThat(result.results().get(0).sourceDomain()).isEqualTo("developer.android.com");
        } finally {
            server.stop(0);
        }
    }
}
