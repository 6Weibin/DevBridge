package com.devbridge.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.config.ControlPlaneSecurityProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 本地控制面认证过滤器测试，覆盖令牌、Host、Origin 和浏览器 Cookie。
 *
 * <p>by AI.Coding</p>
 */
class LocalControlPlaneAuthenticationFilterTest {

    /**
     * 验证缺少令牌的 API 请求被拒绝。
     */
    @Test
    void apiRequestWithoutTokenShouldBeUnauthorized() throws Exception {
        TestContext context = context();
        MockHttpServletRequest request = apiRequest("GET");

        context.filter().doFilter(request, context.response(), context.chain());

        assertThat(context.response().getStatus()).isEqualTo(401);
        assertThat(context.chain().getRequest()).isNull();
    }

    /**
     * 验证有效请求头令牌可以访问本机 API。
     */
    @Test
    void validHeaderTokenShouldAuthenticateRequest() throws Exception {
        TestContext context = context();
        MockHttpServletRequest request = apiRequest("POST");
        request.addHeader(LocalControlPlaneTokenService.TOKEN_HEADER, "test-control-token");
        request.addHeader("Origin", "http://127.0.0.1:15173");

        context.filter().doFilter(request, context.response(), context.chain());

        assertThat(context.response().getStatus()).isEqualTo(200);
        assertThat(context.chain().getRequest()).isSameAs(request);
    }

    /**
     * 验证错误 Host 即使携带有效令牌也被拒绝。
     */
    @Test
    void nonLocalHostShouldBeForbidden() throws Exception {
        TestContext context = context();
        MockHttpServletRequest request = apiRequest("GET");
        request.setServerName("evil.example.com");
        request.addHeader("Host", "evil.example.com");
        request.addHeader(LocalControlPlaneTokenService.TOKEN_HEADER, "test-control-token");

        context.filter().doFilter(request, context.response(), context.chain());

        assertThat(context.response().getStatus()).isEqualTo(403);
    }

    /**
     * 验证非受信 Origin 不能借用泄露令牌调用控制面。
     */
    @Test
    void untrustedOriginShouldBeForbidden() throws Exception {
        TestContext context = context();
        MockHttpServletRequest request = apiRequest("POST");
        request.addHeader("Origin", "http://evil.example.com");
        request.addHeader(LocalControlPlaneTokenService.TOKEN_HEADER, "test-control-token");

        context.filter().doFilter(request, context.response(), context.chain());

        assertThat(context.response().getStatus()).isEqualTo(403);
    }

    /**
     * 验证同源首页设置安全 Cookie，后续 API 可使用该 Cookie 认证。
     */
    @Test
    void bootstrapPageShouldIssueCookieForSameOriginBrowser() throws Exception {
        TestContext bootstrap = context();
        MockHttpServletRequest page = new MockHttpServletRequest("GET", "/");
        page.setServerName("127.0.0.1");
        page.setServerPort(8080);
        page.addHeader("Host", "127.0.0.1:8080");
        bootstrap.filter().doFilter(page, bootstrap.response(), bootstrap.chain());
        String cookieHeader = bootstrap.response().getHeader("Set-Cookie");

        TestContext api = context();
        MockHttpServletRequest request = apiRequest("GET");
        request.setCookies(new Cookie(LocalControlPlaneTokenService.TOKEN_COOKIE, "test-control-token"));
        api.filter().doFilter(request, api.response(), api.chain());

        assertThat(cookieHeader).contains("HttpOnly").contains("SameSite=Strict");
        assertThat(api.chain().getRequest()).isSameAs(request);
    }

    /**
     * 验证后端令牌轮换后，同源页面可在原 API 请求上刷新 Cookie 并继续执行。
     */
    @Test
    void staleCookieShouldRecoverForSameOriginBrowser() throws Exception {
        TestContext context = context();
        MockHttpServletRequest request = apiRequest("POST");
        request.addHeader("Origin", "http://127.0.0.1:8080");
        request.setCookies(new Cookie(LocalControlPlaneTokenService.TOKEN_COOKIE, "expired-token"));

        context.filter().doFilter(request, context.response(), context.chain());

        assertThat(context.response().getHeader("Set-Cookie"))
                .contains(LocalControlPlaneTokenService.TOKEN_COOKIE).contains("HttpOnly");
        assertThat(context.chain().getRequest()).isSameAs(request);
    }

    /**
     * 验证受信 Origin 的预检请求不需要携带令牌。
     */
    @Test
    void trustedPreflightShouldPassWithoutToken() throws Exception {
        TestContext context = context();
        MockHttpServletRequest request = apiRequest("OPTIONS");
        request.addHeader("Origin", "http://localhost:5173");

        context.filter().doFilter(request, context.response(), context.chain());

        assertThat(context.chain().getRequest()).isSameAs(request);
    }

    /**
     * 验证后端关闭后当前进程令牌立即失效。
     */
    @Test
    void tokenShouldBecomeInvalidWhenServiceIsDestroyed() {
        ControlPlaneSecurityProperties properties = new ControlPlaneSecurityProperties();
        properties.setToken("test-control-token");
        LocalControlPlaneTokenService tokenService = new LocalControlPlaneTokenService(properties);
        assertThat(tokenService.matches("test-control-token")).isTrue();

        tokenService.invalidate();

        assertThat(tokenService.matches("test-control-token")).isFalse();
        assertThat(tokenService.token()).isNull();
    }

    /**
     * 创建固定令牌和受信来源的测试上下文。
     *
     * @return 测试上下文
     */
    private TestContext context() {
        ControlPlaneSecurityProperties properties = new ControlPlaneSecurityProperties();
        properties.setEnabled(true);
        properties.setToken("test-control-token");
        LocalControlPlaneTokenService tokenService = new LocalControlPlaneTokenService(properties);
        return new TestContext(
                new LocalControlPlaneAuthenticationFilter(properties, tokenService),
                new MockHttpServletResponse(),
                new MockFilterChain());
    }

    /**
     * 创建本机 API 请求。
     *
     * @param method HTTP 方法
     * @return API 请求
     */
    private MockHttpServletRequest apiRequest(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/ai/agent/tasks");
        request.setServerName("127.0.0.1");
        request.setServerPort(8080);
        request.addHeader("Host", "127.0.0.1:8080");
        return request;
    }

    /**
     * 测试过滤器依赖集合。
     *
     * @param filter 认证过滤器
     * @param response HTTP 响应
     * @param chain 过滤器链
     */
    private record TestContext(
            LocalControlPlaneAuthenticationFilter filter,
            MockHttpServletResponse response,
            MockFilterChain chain) {
    }
}
