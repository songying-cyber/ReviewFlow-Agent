package com.paicli.web;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网络访问策略。
 *
 * <ul>
 *   <li>scheme 白名单：仅允许 http / https</li>
 *   <li>主机黑名单：屏蔽 loopback、site-local、link-local、未指定地址（防 SSRF）</li>
 *   <li>简易 token bucket 限流：每 60 秒最多 30 次请求</li>
 * </ul>
 *
 * 当前是基础围栏，覆盖常见 SSRF 场景；面向严苛企业环境时还需补 DNS rebinding 防护、
 * 完整 CIDR 黑白名单、证书校验加固等。
 */
public class NetworkPolicy {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final long DEFAULT_WINDOW_MILLIS = 60_000L;
    private static final int DEFAULT_MAX_PER_WINDOW = 30;

    private final long windowMillis;
    private final int maxPerWindow;
    private final AtomicLong windowStart = new AtomicLong(0);
    private final AtomicLong counter = new AtomicLong(0);

    public NetworkPolicy() {
        this(DEFAULT_WINDOW_MILLIS, DEFAULT_MAX_PER_WINDOW);
    }

    NetworkPolicy(long windowMillis, int maxPerWindow) {
        this.windowMillis = windowMillis;
        this.maxPerWindow = maxPerWindow;
    }

    /**
     * 校验 URL 是否允许访问。返回 null 表示通过，否则返回拒绝原因。
     */
    public String checkUrl(String url) {
        if (url == null || url.isBlank()) {
            return "URL 不能为空";
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return "URL 格式非法: " + e.getMessage();
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            return "URL 缺少 scheme（需 http 或 https）";
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            return "禁止的 scheme: " + scheme + "（仅允许 http、https）";
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "URL 缺少 host";
        }

        String hostReason = checkHost(host);
        if (hostReason != null) {
            return hostReason;
        }

        return null;
    }

    /**
     * 申请一次请求配额。返回 null 表示通过，否则返回限流原因。
     */
    public String acquire() {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (start == 0 || now - start >= windowMillis) {
            windowStart.set(now);
            counter.set(1);
            return null;
        }
        long current = counter.incrementAndGet();
        if (current > maxPerWindow) {
            long resetIn = (windowMillis - (now - start)) / 1000L;
            return String.format("请求过于频繁（%d 秒内已达 %d 次上限），约 %d 秒后重试",
                    windowMillis / 1000L, maxPerWindow, Math.max(resetIn, 1L));
        }
        return null;
    }

    private String checkHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);

        // 字面量黑名单（即便 DNS 解析没拿到，也直接拒绝）
        if (lower.equals("localhost") || lower.endsWith(".localhost")) {
            return "禁止访问 localhost";
        }
        if (lower.equals("0.0.0.0")) {
            return "禁止访问 0.0.0.0";
        }

        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress addr : addrs) {
                if (addr.isLoopbackAddress()) {
                    return "禁止访问环回地址（" + addr.getHostAddress() + "）";
                }
                if (addr.isAnyLocalAddress()) {
                    return "禁止访问未指定地址（" + addr.getHostAddress() + "）";
                }
                if (addr.isLinkLocalAddress()) {
                    return "禁止访问链路本地地址（" + addr.getHostAddress() + "）";
                }
                if (addr.isSiteLocalAddress()) {
                    return "禁止访问站内地址（" + addr.getHostAddress() + "）";
                }
            }
        } catch (UnknownHostException e) {
            return "无法解析主机: " + host;
        }
        return null;
    }
}
