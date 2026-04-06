package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Web 搜索工具 - 通过 SerpAPI 进行互联网搜索
 *
 * MVP 版本：使用 SerpAPI（Google 搜索 API），支持：
 * - 关键词搜索，返回摘要结果（标题、摘要、链接）
 * - 可配置返回结果数量
 * - 结果大小限制，避免 token 浪费
 *
 * 使用前需在 .env 中配置 SERPAPI_KEY
 */
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_SNIPPET_LENGTH = 200;
    private static final int MAX_RESPONSE_CHARS = 4000;

    private final String apiKey;
    private final OkHttpClient httpClient;

    public WebSearchTool(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 执行搜索
     *
     * @param query  搜索关键词
     * @param topK   返回结果数量（可选，默认 5）
     * @return 格式化的搜索结果
     */
    public String search(String query, int topK) {
        if (apiKey == null || apiKey.isBlank()) {
            return "⚠️ Web 搜索未配置。请在 .env 中设置 SERPAPI_KEY。\n" +
                    "获取免费 API Key: https://serpapi.com/manage-api-key";
        }

        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空";
        }

        int maxResults = topK > 0 ? Math.min(topK, 10) : DEFAULT_MAX_RESULTS;

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = String.format(
                    "https://serpapi.com/search.json?q=%s&api_key=%s&num=%d&hl=zh-cn",
                    encodedQuery, apiKey, maxResults
            );

            log.info("Web search: query={}, maxResults={}", query, maxResults);

            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    if (response.code() == 401) {
                        return "❌ SerpAPI Key 无效或已过期，请检查 .env 中的 SERPAPI_KEY";
                    }
                    log.warn("SerpAPI returned {}: {}", response.code(), body.substring(0, Math.min(body.length(), 200)));
                    return "搜索请求失败 (HTTP " + response.code() + ")";
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                return parseAndFormatResults(query, responseBody, maxResults);
            }

        } catch (Exception e) {
            log.error("Web search failed", e);
            return "搜索失败: " + e.getMessage();
        }
    }

    /**
     * 解析 SerpAPI 返回的 JSON，格式化为可读文本
     */
    private String parseAndFormatResults(String query, String json, int maxResults) {
        try {
            JsonNode root = mapper.readTree(json);

            // SerpAPI 返回 organic_results 数组
            JsonNode organicResults = root.path("organic_results");
            if (!organicResults.isArray() || organicResults.isEmpty()) {
                // 尝试 answer_box（Google 精选摘要）
                JsonNode answerBox = root.path("answer_box");
                if (!answerBox.isMissingNode()) {
                    String answer = extractAnswerBox(answerBox);
                    if (answer != null && !answer.isBlank()) {
                        return "🔍 搜索: " + query + "\n\n" +
                                "📌 精选摘要:\n" + truncate(answer, MAX_RESPONSE_CHARS);
                    }
                }
                return "🔍 搜索: " + query + "\n\n未找到相关结果。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("🔍 搜索: ").append(query).append("\n\n");

            int count = 0;
            for (JsonNode result : organicResults) {
                if (count >= maxResults) break;

                String title = result.path("title").asText("");
                String snippet = result.path("snippet").asText("");
                String link = result.path("link").asText("");

                if (title.isBlank() && snippet.isBlank()) continue;

                count++;
                sb.append(count).append(". ");
                if (!title.isBlank()) {
                    sb.append(title);
                }
                sb.append("\n");

                if (!snippet.isBlank()) {
                    sb.append("   ").append(truncate(snippet, MAX_SNIPPET_LENGTH)).append("\n");
                }

                if (!link.isBlank()) {
                    sb.append("   🔗 ").append(link).append("\n");
                }
                sb.append("\n");

                // 控制总输出长度
                if (sb.length() > MAX_RESPONSE_CHARS) {
                    sb.append("...(结果已截断)\n");
                    break;
                }
            }

            // 检查 knowledge_graph（知识图谱）
            JsonNode knowledgeGraph = root.path("knowledge_graph");
            if (!knowledgeGraph.isMissingNode() && count < maxResults) {
                String kgTitle = knowledgeGraph.path("title").asText("");
                String kgDescription = knowledgeGraph.path("description").asText("");
                if (!kgTitle.isBlank()) {
                    sb.append("📋 知识图谱: ").append(kgTitle);
                    if (!kgDescription.isBlank()) {
                        sb.append("\n   ").append(truncate(kgDescription, MAX_SNIPPET_LENGTH));
                    }
                    sb.append("\n");
                }
            }

            log.info("Web search completed: {} results returned", count);
            return sb.toString().trim();

        } catch (Exception e) {
            log.error("Failed to parse search results", e);
            // 降级：返回原始 JSON 的前 N 个字符
            return "搜索结果解析失败，原始数据:\n" + truncate(json, 1000);
        }
    }

    /**
     * 提取 Google 精选摘要（answer_box）
     */
    private String extractAnswerBox(JsonNode answerBox) {
        // 多种 answer_box 格式
        String snippet = answerBox.path("snippet").asText("");
        if (!snippet.isBlank()) return snippet;

        String answer = answerBox.path("answer").asText("");
        if (!answer.isBlank()) return answer;

        JsonNode list = answerBox.path("list");
        if (list.isArray() && !list.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(list.size(), 5); i++) {
                sb.append("- ").append(list.get(i).asText("")).append("\n");
            }
            return sb.toString().trim();
        }

        return null;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
