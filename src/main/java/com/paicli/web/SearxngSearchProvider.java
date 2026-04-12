package com.paicli.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * SearXNG 搜索 provider。
 *
 * SearXNG 是开源的元搜索引擎（github.com/searxng/searxng），自己不爬取互联网，
 * 而是把请求转发到 Google / Bing / DuckDuckGo / Brave 等几十个引擎，再聚合返回。
 *
 * 推荐用法：本地 docker 起一个实例
 * <pre>
 *   docker run --rm -p 8888:8888 searxng/searxng
 * </pre>
 * 然后 .env 里配置：
 * <pre>
 *   SEARCH_PROVIDER=searxng
 *   SEARXNG_URL=http://localhost:8888
 * </pre>
 *
 * 调用 JSON API：{@code GET /search?q=xxx&format=json}。无需 API Key。
 */
public class SearxngSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(SearxngSearchProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final OkHttpClient httpClient;

    public SearxngSearchProvider(String baseUrl) {
        this(baseUrl, new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build());
    }

    SearxngSearchProvider(String baseUrl, OkHttpClient httpClient) {
        this.baseUrl = (baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", ""));
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return "searxng";
    }

    @Override
    public boolean isReady() {
        return !baseUrl.isBlank() && HttpUrl.parse(baseUrl + "/search") != null;
    }

    @Override
    public String unavailableHint() {
        return "SearXNG 未配置或地址非法。请在 .env 中设置 SEARXNG_URL=http://localhost:8888，"
                + "并确保实例已启动。";
    }

    @Override
    public List<SearchResult> search(String query, int topK) throws IOException {
        if (!isReady()) {
            throw new IOException(unavailableHint());
        }
        int maxResults = topK > 0 ? Math.min(topK, 10) : 5;

        HttpUrl url = HttpUrl.parse(baseUrl + "/search").newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("language", "zh")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                // 部分 SearXNG 实例对默认 UA 限流，伪装成普通客户端更稳
                .header("User-Agent", "paicli-web-search/1.0")
                .get()
                .build();
        log.info("SearXNG search: query={}, topK={}, base={}", query, maxResults, baseUrl);

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("SearXNG 请求失败 (HTTP " + response.code()
                        + ")。某些公共实例禁用了 JSON API，建议自托管 docker 实例。");
            }
            String body = response.body() == null ? "" : response.body().string();
            return parse(body, maxResults);
        }
    }

    private List<SearchResult> parse(String json, int maxResults) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode results = root.path("results");
        List<SearchResult> out = new ArrayList<>();
        if (results.isArray()) {
            int position = 0;
            for (JsonNode node : results) {
                if (position >= maxResults) {
                    break;
                }
                String title = node.path("title").asText("");
                String link = node.path("url").asText("");
                String snippet = node.path("content").asText("");
                if (title.isBlank() && snippet.isBlank()) {
                    continue;
                }
                position++;
                out.add(SearchResult.of(position, title, link, snippet));
            }
        }
        return out;
    }
}
