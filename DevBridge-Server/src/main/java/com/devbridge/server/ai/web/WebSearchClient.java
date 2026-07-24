package com.devbridge.server.ai.web;

import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.ai.web.WebSearchConfigService.WebSearchRuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Tavily 搜索和安全网页抓取客户端，所有响应体和正文均设置明确上限。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class WebSearchClient {

    private static final int SEARCH_RESPONSE_MAX_BYTES = 1024 * 1024;
    private static final int PAGE_RESPONSE_MAX_BYTES = 2 * 1024 * 1024;
    private static final int PAGE_CONTENT_MAX_CHARACTERS = 50_000;
    private static final int MAX_REDIRECTS = 3;

    private final ObjectMapper objectMapper;
    private final WebUrlGuard urlGuard;
    private final SensitiveDataMasker masker;
    private final HttpClient httpClient;
    private final ConcurrentMap<String, CompletableFuture<HttpResponse<InputStream>>> running = new ConcurrentHashMap<>();

    /** 注入 JSON、安全校验和脱敏依赖。 */
    public WebSearchClient(ObjectMapper objectMapper, WebUrlGuard urlGuard, SensitiveDataMasker masker) {
        this.objectMapper = objectMapper;
        this.urlGuard = urlGuard;
        this.masker = masker;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 调用 Tavily 搜索并返回有界结构化结果。 */
    public SearchResponse search(WebSearchRuntimeConfig config, SearchRequest request, String callId) {
        String query = normalizedQuery(request.query());
        int count = Math.max(1, Math.min(10,
                request.maxResults() > 0 ? request.maxResults() : config.defaultResultCount()));
        JsonNode body = objectMapper.createObjectNode()
                .put("api_key", config.apiKey())
                .put("query", query)
                .put("search_depth", "basic")
                .put("include_answer", false)
                .put("include_raw_content", false)
                .put("max_results", count);
        HttpRequest httpRequest = jsonRequest(config.apiUrl(), body.toString(), Duration.ofSeconds(10));
        byte[] response = sendBounded(httpRequest, SEARCH_RESPONSE_MAX_BYTES, callId);
        return parseSearchResponse(query, response, count);
    }

    /** 兼容配置连接测试等非 Agent 调用。 */
    public SearchResponse search(WebSearchRuntimeConfig config, SearchRequest request) {
        return search(config, request, "");
    }

    /** 抓取公开网页并提取有界纯文本正文。 */
    public FetchResponse fetch(String url, int requestedMaxCharacters, String callId) {
        int maxCharacters = requestedMaxCharacters <= 0
                ? PAGE_CONTENT_MAX_CHARACTERS : Math.min(PAGE_CONTENT_MAX_CHARACTERS, requestedMaxCharacters);
        PageResponse response = fetchPage(urlGuard.requirePublicHttpUrl(url), 0, callId);
        String mediaType = response.contentType().split(";", 2)[0].trim().toLowerCase();
        if (!("text/html".equals(mediaType) || "application/xhtml+xml".equals(mediaType)
                || "text/plain".equals(mediaType))) {
            throw new IllegalArgumentException("网页内容类型不支持: " + mediaType);
        }
        return extract(response, mediaType, maxCharacters);
    }

    /** 取消正在等待响应头的网络调用。 */
    public void cancel(String callId) {
        CompletableFuture<HttpResponse<InputStream>> future = running.remove(callId);
        if (future != null) future.cancel(true);
    }

    /** 手动处理重定向，并在每一跳重新执行公网地址校验。 */
    private PageResponse fetchPage(URI uri, int redirects, String callId) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "text/html,text/plain,application/xhtml+xml")
                .header("User-Agent", "Ai-DevBridge/1.0")
                .GET().build();
        HttpResponse<InputStream> response = send(request, callId);
        if (isRedirect(response.statusCode())) {
            if (redirects >= MAX_REDIRECTS) throw new IllegalArgumentException("网页重定向次数超过限制");
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IllegalArgumentException("网页重定向缺少 Location"));
            URI target = urlGuard.requirePublicHttpUrl(uri.resolve(location).toString());
            closeQuietly(response.body());
            return fetchPage(target, redirects + 1, callId);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            closeQuietly(response.body());
            throw new IllegalArgumentException("网页读取失败，HTTP " + response.statusCode());
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("text/plain");
        return new PageResponse(uri, contentType, readBounded(response.body(), PAGE_RESPONSE_MAX_BYTES));
    }

    /** 提取 HTML 标题和正文；纯文本保持原内容。 */
    private FetchResponse extract(PageResponse response, String mediaType, int maxCharacters) {
        String title = "";
        String content;
        if ("text/plain".equals(mediaType)) {
            content = new String(response.body(), StandardCharsets.UTF_8);
        } else {
            try {
                // charset 传 null 时由 Jsoup 根据 HTTP/meta 内容识别，兼容非 UTF-8 中文网页。
                Document document = Jsoup.parse(
                        new ByteArrayInputStream(response.body()), null, response.uri().toString());
                title = document.title();
                document.select("script,style,noscript,svg,nav,footer,header,form,iframe").remove();
                content = document.body() == null ? "" : document.body().wholeText();
            } catch (IOException ex) {
                throw new IllegalArgumentException("网页正文解析失败", ex);
            }
        }
        String normalized = content.replace('\u00a0', ' ').replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n").trim();
        boolean truncated = normalized.length() > maxCharacters;
        String bounded = truncated ? normalized.substring(0, maxCharacters) : normalized;
        return new FetchResponse(response.uri().toString(), title, mediaType, bounded, truncated, Instant.now());
    }

    /** 解析 Tavily 响应并忽略无 URL 的异常结果。 */
    private SearchResponse parseSearchResponse(String query, byte[] response, int count) {
        try {
            JsonNode root = objectMapper.readTree(response);
            List<SearchResult> results = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                String url = item.path("url").asText("").trim();
                if (!StringUtils.hasText(url) || results.size() >= count) continue;
                results.add(new SearchResult(
                        item.path("title").asText(""), url,
                        item.path("content").asText(""), host(url),
                        item.path("published_date").asText(""), item.path("score").asDouble(0d)));
            }
            return new SearchResponse(query, List.copyOf(results), Instant.now());
        } catch (IOException ex) {
            throw new IllegalArgumentException("搜索服务响应格式无效", ex);
        }
    }

    /** 构造 Tavily JSON 请求。 */
    private HttpRequest jsonRequest(String url, String body, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /** 发送请求并把网络异常转换为稳定错误。 */
    private HttpResponse<InputStream> send(HttpRequest request, String callId) {
        CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(
                request, HttpResponse.BodyHandlers.ofInputStream());
        if (StringUtils.hasText(callId)) running.put(callId, future);
        try {
            return future.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new IllegalArgumentException("网络请求失败: " + cause.getMessage(), cause);
        } finally {
            if (StringUtils.hasText(callId)) running.remove(callId, future);
        }
    }

    /** 发送请求、校验状态并限制响应大小。 */
    private byte[] sendBounded(HttpRequest request, int maxBytes, String callId) {
        HttpResponse<InputStream> response = send(request, callId);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            byte[] error = readBounded(response.body(), Math.min(maxBytes, 16 * 1024));
            throw new IllegalArgumentException("搜索服务调用失败，HTTP " + response.statusCode()
                    + "：" + new String(error, StandardCharsets.UTF_8));
        }
        return readBounded(response.body(), maxBytes);
    }

    /** 读取有界响应体，超过限制立即失败。 */
    private byte[] readBounded(InputStream input, int maxBytes) {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) throw new IllegalArgumentException("网络响应超过大小限制");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("网络响应读取失败", ex);
        }
    }

    /** 搜索查询脱敏并限制长度。 */
    private String normalizedQuery(String query) {
        String value = masker.protectCredentials(query == null ? "" : query).trim();
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("搜索关键词不能为空");
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    /** 判断 HTTP 重定向状态。 */
    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** 提取来源域名。 */
    private String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    /** 安静关闭未读取的重定向或错误响应体。 */
    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // 响应已经结束当前分支，关闭失败不覆盖原始网络结论。
        }
    }

    /** 网络搜索请求。by AI.Coding */
    public record SearchRequest(String query, int maxResults) {
    }

    /** 网络搜索结果。by AI.Coding */
    public record SearchResult(
            String title, String url, String snippet, String sourceDomain,
            String publishedAt, double score) {
    }

    /** 网络搜索响应。by AI.Coding */
    public record SearchResponse(String query, List<SearchResult> results, Instant retrievedAt) {
    }

    /** 网页读取响应。by AI.Coding */
    public record FetchResponse(
            String url, String title, String mediaType, String content,
            boolean truncated, Instant retrievedAt) {
    }

    /** 内部网页响应。by AI.Coding */
    private record PageResponse(URI uri, String contentType, byte[] body) {
    }
}
