package com.paicli.web;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 基础 HTTP 抓取器：拿 URL → 字节流 → 字符串。
 *
 * 边界：
 * <ul>
 *   <li>5MB 响应体上限，超出截断（流式读，避免 OOM）</li>
 *   <li>30s 整体超时（OkHttp callTimeout）</li>
 *   <li>不处理 JS 渲染、不处理登录态 —— 那是第 13/14 期的事</li>
 *   <li>遇到 4xx/5xx 直接抛 IOException，由调用方决定如何向用户呈现</li>
 * </ul>
 *
 * 字符集解析：优先 Content-Type charset，其次 HTML meta（Jsoup 会兜底处理），
 * 全失败用 UTF-8。这里只负责拿到字符串，meta 嗅探在 {@link HtmlExtractor} 里做。
 */
public class WebFetcher {

    private static final Logger log = LoggerFactory.getLogger(WebFetcher.class);
    public static final int DEFAULT_MAX_BYTES = 5 * 1024 * 1024;
    private static final long DEFAULT_TIMEOUT_SECONDS = 30L;

    private final OkHttpClient httpClient;
    private final int maxBytes;

    public WebFetcher() {
        this(DEFAULT_MAX_BYTES);
    }

    public WebFetcher(int maxBytes) {
        this(maxBytes, new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build());
    }

    WebFetcher(int maxBytes, OkHttpClient httpClient) {
        this.maxBytes = maxBytes;
        this.httpClient = httpClient;
    }

    public RawResponse fetch(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.9")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("User-Agent",
                        "Mozilla/5.0 (compatible; paicli-web-fetch/1.0)")
                .get()
                .build();

        log.info("web_fetch: GET {}", url);
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("响应体为空");
            }

            Charset charset = resolveCharset(response, body);
            byte[] bytes = readBounded(body.byteStream());
            boolean truncated = bytes.length >= maxBytes;
            String text = new String(bytes, charset);
            String contentType = response.header("Content-Type", "");
            return new RawResponse(url, text, contentType, charset.name(), truncated);
        }
    }

    private Charset resolveCharset(Response response, ResponseBody body) {
        try {
            if (body.contentType() != null && body.contentType().charset() != null) {
                return body.contentType().charset();
            }
        } catch (Exception ignored) {
        }
        return StandardCharsets.UTF_8;
    }

    private byte[] readBounded(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while ((n = input.read(buffer)) != -1) {
            int remaining = maxBytes - total;
            if (remaining <= 0) {
                break;
            }
            int writeLen = Math.min(n, remaining);
            out.write(buffer, 0, writeLen);
            total += writeLen;
            if (total >= maxBytes) {
                break;
            }
        }
        return out.toByteArray();
    }

    public record RawResponse(String url, String body, String contentType, String charset, boolean truncated) {}
}
