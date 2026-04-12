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
 * SerpAPI 搜索 provider。
 *
 * 商业聚合服务，帮我们绕过 Google 反爬。需在环境变量或 .env 中配置 SERPAPI_KEY。
 */
public class SerpApiSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(SerpApiSearchProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENDPOINT = "https://serpapi.com/search.json";

    private final String apiKey;
    private final OkHttpClient httpClient;

    public SerpApiSearchProvider(String apiKey) {
        this(apiKey, new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build());
    }

    SerpApiSearchProvider(String apiKey, OkHttpClient httpClient) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return "serpapi";
    }

    @Override
    public boolean isReady() {
        return !apiKey.isBlank();
    }

    @Override
    public String unavailableHint() {
        return "Web 搜索未配置 SerpAPI Key。请在 .env 中设置 SERPAPI_KEY，"
                + "或 SEARCH_PROVIDER=searxng 切换到自托管 SearXNG。"
                + "申请 SerpAPI Key：https://serpapi.com/manage-api-key";
    }

    @Override
    public List<SearchResult> search(String query, int topK) throws IOException {
        if (!isReady()) {
            throw new IOException(unavailableHint());
        }
        int maxResults = topK > 0 ? Math.min(topK, 10) : 5;

        HttpUrl url = HttpUrl.parse(ENDPOINT).newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("num", String.valueOf(maxResults))
                .addQueryParameter("hl", "zh-cn")
                .build();

        Request request = new Request.Builder().url(url).get().build();
        log.info("SerpAPI search: query={}, topK={}", query, maxResults);

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 401) {
                    throw new IOException("SerpAPI Key 无效或已过期");
                }
                throw new IOException("SerpAPI 请求失败 (HTTP " + response.code() + ")");
            }
            String body = response.body() == null ? "" : response.body().string();
            return parse(body, maxResults);
        }
    }

    private List<SearchResult> parse(String json, int maxResults) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode organic = root.path("organic_results");
        List<SearchResult> results = new ArrayList<>();
        if (organic.isArray()) {
            int position = 0;
            for (JsonNode node : organic) {
                if (position >= maxResults) {
                    break;
                }
                String title = node.path("title").asText("");
                String link = node.path("link").asText("");
                String snippet = node.path("snippet").asText("");
                if (title.isBlank() && snippet.isBlank()) {
                    continue;
                }
                position++;
                results.add(SearchResult.of(position, title, link, snippet));
            }
        }
        // 没有 organic 结果时，尝试 answer_box / knowledge_graph 兜底
        if (results.isEmpty()) {
            String answer = extractAnswerBox(root.path("answer_box"));
            if (!answer.isBlank()) {
                results.add(SearchResult.of(1, "Google 精选摘要", "", answer));
            }
        }
        return results;
    }

    private String extractAnswerBox(JsonNode answerBox) {
        if (answerBox.isMissingNode()) return "";
        String snippet = answerBox.path("snippet").asText("");
        if (!snippet.isBlank()) return snippet;
        String answer = answerBox.path("answer").asText("");
        if (!answer.isBlank()) return answer;
        return "";
    }
}
