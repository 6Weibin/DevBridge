package com.devbridge.server.security;

import com.devbridge.server.config.ControlPlaneSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 本地控制面认证过滤器，校验 Host、Origin 和进程级会话令牌。
 *
 * <p>by AI.Coding</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalControlPlaneAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/";

    private final ControlPlaneSecurityProperties properties;
    private final LocalControlPlaneTokenService tokenService;
    private final Set<String> allowedOrigins;

    /**
     * 注入控制面安全配置和令牌服务。
     *
     * @param properties 安全配置
     * @param tokenService 令牌服务
     */
    public LocalControlPlaneAuthenticationFilter(
            ControlPlaneSecurityProperties properties,
            LocalControlPlaneTokenService tokenService) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.allowedOrigins = normalizeOrigins(properties.getAllowedOrigins());
    }

    /**
     * 校验本地控制面请求；静态资源只在首页签发同源会话 Cookie。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param filterChain 过滤器链
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }
        boolean apiRequest = request.getRequestURI().startsWith(API_PREFIX);
        boolean bootstrapRequest = isBootstrapRequest(request);
        if (!apiRequest && !bootstrapRequest) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!isLocalHost(request)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "CONTROL_PLANE_HOST_FORBIDDEN");
            return;
        }
        if (!isTrustedOrigin(request)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "CONTROL_PLANE_ORIGIN_FORBIDDEN");
            return;
        }
        if (bootstrapRequest) {
            issueSessionCookie(response);
            filterChain.doFilter(request, response);
            return;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!tokenService.matches(requestToken(request))) {
            if (isSameOriginBrowserRequest(request)) {
                // 后端重启会轮换进程令牌；同源页面可安全刷新 Cookie，业务请求无需用户手动重载页面。
                issueSessionCookie(response);
                filterChain.doFilter(request, response);
                return;
            }
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "CONTROL_PLANE_UNAUTHORIZED");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 判断是否为需要签发浏览器会话的首页请求。
     *
     * @param request HTTP 请求
     * @return 首页请求返回 true
     */
    private boolean isBootstrapRequest(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        return "/".equals(request.getRequestURI()) || "/index.html".equals(request.getRequestURI());
    }

    /**
     * 校验请求目标主机必须为回环地址。
     *
     * @param request HTTP 请求
     * @return 本机 Host 返回 true
     */
    private boolean isLocalHost(HttpServletRequest request) {
        Enumeration<String> hostHeaders = request.getHeaders(HttpHeaders.HOST);
        String host = hostHeaders != null && hostHeaders.hasMoreElements()
                ? hostHeaders.nextElement()
                : request.getServerName();
        if (host == null || (hostHeaders != null && hostHeaders.hasMoreElements())) {
            return false;
        }
        String normalized = hostName(host).toLowerCase(Locale.ROOT);
        return "127.0.0.1".equals(normalized)
                || "localhost".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized)
                || "::1".equals(normalized);
    }

    /**
     * 从 Host 请求头中移除端口并保留 IPv6 地址。
     *
     * @param hostHeader Host 请求头
     * @return 主机名
     */
    private String hostName(String hostHeader) {
        String value = hostHeader.trim();
        if (value.startsWith("[") && value.contains("]")) {
            return value.substring(1, value.indexOf(']'));
        }
        int colon = value.lastIndexOf(':');
        return colon > 0 && value.indexOf(':') == colon ? value.substring(0, colon) : value;
    }

    /**
     * 校验 Origin 为同源或显式受信的本地前端。
     *
     * @param request HTTP 请求
     * @return 受信返回 true
     */
    private boolean isTrustedOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || origin.isBlank()) {
            return true;
        }
        String normalized = normalizeOrigin(origin);
        return sameOrigin(request, normalized) || allowedOrigins.contains(normalized);
    }

    /**
     * 判断 Origin 是否与当前后端请求同源。
     *
     * @param request HTTP 请求
     * @param origin 规范 Origin
     * @return 同源返回 true
     */
    private boolean sameOrigin(HttpServletRequest request, String origin) {
        if (origin.isBlank()) {
            return false;
        }
        String expected = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        return expected.equalsIgnoreCase(origin);
    }

    /**
     * 判断请求是否明确来自当前后端同源页面；缺少 Origin 的脚本和本机进程不能使用自动恢复。
     *
     * @param request HTTP 请求
     * @return 同源浏览器请求返回 true
     */
    private boolean isSameOriginBrowserRequest(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        return origin != null && !origin.isBlank()
                && sameOrigin(request, normalizeOrigin(origin));
    }

    /**
     * 从请求头或 HttpOnly Cookie 中读取令牌。
     *
     * @param request HTTP 请求
     * @return 请求令牌
     */
    private String requestToken(HttpServletRequest request) {
        String header = request.getHeader(LocalControlPlaneTokenService.TOKEN_HEADER);
        if (header != null && !header.isBlank()) {
            return header;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (LocalControlPlaneTokenService.TOKEN_COOKIE.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return "";
    }

    /**
     * 为同源浏览器签发仅当前进程有效的安全 Cookie。
     *
     * @param response HTTP 响应
     */
    private void issueSessionCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie
                .from(LocalControlPlaneTokenService.TOKEN_COOKIE, tokenService.token())
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    /**
     * 返回不包含敏感细节的认证错误。
     *
     * @param response HTTP 响应
     * @param status HTTP 状态
     * @param code 稳定错误码
     */
    private void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"本地控制面认证失败\"}");
    }

    /**
     * 规范化受信 Origin 集合。
     *
     * @param origins 原始 Origin
     * @return 规范集合
     */
    private Set<String> normalizeOrigins(Iterable<String> origins) {
        Set<String> normalized = new HashSet<>();
        if (origins != null) {
            for (String origin : origins) {
                String value = normalizeOrigin(origin);
                if (!value.isBlank()) {
                    normalized.add(value);
                }
            }
        }
        return Set.copyOf(normalized);
    }

    /**
     * 将 Origin 规范为 scheme、host 和显式端口。
     *
     * @param origin 原始 Origin
     * @return 规范 Origin，非法输入返回空串
     */
    private String normalizeOrigin(String origin) {
        try {
            URI uri = URI.create(origin.trim());
            if (uri.getScheme() == null || uri.getHost() == null || uri.getPort() < 0) {
                return "";
            }
            return uri.getScheme().toLowerCase(Locale.ROOT)
                    + "://"
                    + uri.getHost().toLowerCase(Locale.ROOT)
                    + ":"
                    + uri.getPort();
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }
}
