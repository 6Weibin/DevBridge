package com.devbridge.server.ai.web;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 网页抓取 URL 安全边界，阻断本机、私网、链路本地和非 HTTP 地址。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class WebUrlGuard {

    /** 校验当前 URL 及其全部 DNS 解析结果。 */
    public URI requirePublicHttpUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("网页地址仅允许 HTTP 或 HTTPS");
            }
            if (!StringUtils.hasText(uri.getHost()) || StringUtils.hasText(uri.getUserInfo())) {
                throw new IllegalArgumentException("网页地址主机无效");
            }
            validateAddresses(uri.getHost());
            return uri;
        } catch (IllegalArgumentException ex) {
            throw ex;
        }
    }

    /** DNS 任一结果属于非公网地址时整体拒绝，避免双栈地址绕过。 */
    private void validateAddresses(String host) {
        if ("localhost".equalsIgnoreCase(host) || "metadata.google.internal".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("网页地址不允许访问本机或云 Metadata");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new IllegalArgumentException("网页地址无法解析");
            }
            for (InetAddress address : addresses) {
                if (isNonPublic(address)) {
                    throw new IllegalArgumentException("网页地址解析到非公网 IP");
                }
            }
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("网页地址无法解析: " + host, ex);
        }
    }

    /** 判断 Java 内置分类未完整覆盖的共享地址和 IPv6 ULA。 */
    private boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127);
        }
        return bytes.length == 16 && ((bytes[0] & 0xfe) == 0xfc);
    }
}
