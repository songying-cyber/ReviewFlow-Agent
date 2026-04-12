package com.paicli.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 智谱 Web Search provider。
 *
 * <p>对国内 GLM 用户来说这是最自然的选择：
 * <ul>
 *   <li>独立 search API，不绑定模型调用：与 PaiCLI 多模型架构兼容</li>
 *   <li>API Key 与 GLM 推理共用 {@code GLM_API_KEY}，零额外配置</li>
 *   <li>中文搜索效果优于 Google（可选 search_pro_sogou / search_pro_quark）</li>
 *   <li>价格 0.01–0.05 元/次，比 SerpAPI 便宜 5–10 倍</li>
 * </ul>
 *
 * <p>Endpoint: {@code POST https://open.bigmodel.cn/api/paas/v4/tools/web_search}
 *
 * <p>支持的搜索引擎：
 * <ul>
 *   <li>{@code search_std}（0.01 元/次，默认）</li>
 *   <li>{@code search_pro}（0.03 元/次）</li>
 *   <li>{@code search_pro_sogou}（0.05 元/次，搜狗）</li>
 *   <li>{@code search_pro_quark}（0.05 元/次，夸克）</li>
 * </ul>
 */
public class ZhipuSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(ZhipuSearchProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/tools/web_search";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
    private static final Set<String> ALLOWED_ENGINES = Set.of(
            "search_std", "search_pro", "search_pro_sogou", "search_pro_quark");
    private static final String DEFAULT_ENGINE = "search_std";

    private final String apiKey;
    private final String searchEngine;
    private final OkHttpClient httpClient;

    public ZhipuSearchProvider(String apiKey) {
        this(apiKey, DEFAULT_ENGINE);
    }

    public ZhipuSearchProvider(String apiKey, String searchEngine) {
        this(apiKey, searchEngine, new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build());
    }

    ZhipuSearchProvider(String apiKey, String searchEngine, OkHttpClient httpClient) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.searchEngine = normalizeEngine(searchEngine);
        this.httpClient = httpClient;
    }

    private static String normalizeEngine(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_ENGINE;
        }
        String trimmed = raw.trim();
        return ALLOWED_ENGINES.contains(trimmed) ? trimmed : DEFAULT_ENGINE;
    }

    @Override
    public String name() {
        return "zhipu";
    }

    @Override
    public boolean isReady() {
        return !apiKey.isBlank();
    }

    @Override
    public String unavailableHint() {
        return "智谱 Web Search 未配置 API Key。请在 .env 中设置 GLM_API_KEY（与 GLM 推理共用），"
                + "或显式 SEARCH_PROVIDER=zhipu。";
    }

    @Override
    public List<SearchResult> search(String query, int topK) throws IOException {
        if (!isReady()) {
            throw new IOException(unavailableHint());
        }
        int count = topK > 0 ? Math.min(topK, 50) : 10;

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("search_engine", searchEngine);
        payload.put("search_query", query);
        payload.put("count", count);
        payload.put("content_size", "medium");

        Request request = new Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), JSON_MEDIA))
                .build();
        log.info("Zhipu search: query={}, engine={}, count={}", query, searchEngine, count);

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                if (response.code() == 401) {
                    throw new IOException("智谱 API Key 无效或已过期");
                }
                throw new IOException("智谱搜索请求失败 (HTTP " + response.code() + "): "
                        + truncate(body, 200));
            }
            return parse(body, count);
        }
    }

    private List<SearchResult> parse(String json, int maxResults) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode arr = root.path("search_result");
        List<SearchResult> results = new ArrayList<>();
        if (arr.isArray()) {
            int position = 0;
            for (JsonNode node : arr) {
                if (position >= maxResults) {
                    break;
                }
                String title = node.path("title").asText("");
                String link = node.path("link").asText("");
                String content = node.path("content").asText("");
                if (title.isBlank() && content.isBlank()) {
                    continue;
                }
                position++;
                results.add(SearchResult.of(position, title, link, content));
            }
        }
        return results;
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
