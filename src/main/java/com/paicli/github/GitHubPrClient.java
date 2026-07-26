package com.paicli.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitHubPrClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String API_VERSION = "2022-11-28";
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    private static final GitHubInlineCommentFormatter COMMENT_FORMATTER = new GitHubInlineCommentFormatter();

    private final GitHubConfig config;
    private final OkHttpClient httpClient;
    private final HttpUrl apiBaseUrl;
    private final HttpUrl graphqlUrl;

    public GitHubPrClient(GitHubConfig config) {
        this(config, new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build());
    }

    GitHubPrClient(GitHubConfig config, OkHttpClient httpClient) {
        this.config = config == null ? GitHubConfig.fromEnvironment() : config;
        this.httpClient = httpClient;
        this.apiBaseUrl = HttpUrl.get(this.config.apiBaseUrl());
        this.graphqlUrl = HttpUrl.get(this.config.graphqlUrl());
    }

    public GitHubPullRequest fetchPullRequest(GitHubPrReference ref) throws IOException {
        JsonNode node = getJson(repoUrl(ref, "pulls", String.valueOf(ref.number())), "application/vnd.github+json");
        return pullRequestFromRest(ref, node);
    }

    public GitHubPullRequest fetchPullRequestViaGraphql(GitHubPrReference ref) throws IOException {
        String query = """
                query($owner: String!, $repo: String!, $number: Int!) {
                  repository(owner: $owner, name: $repo) {
                    pullRequest(number: $number) {
                      id
                      title
                      body
                      state
                      url
                      baseRefName
                      baseRefOid
                      headRefName
                      headRefOid
                      headRepository { nameWithOwner }
                      author { login }
                    }
                  }
                }
                """;
        ObjectNode variables = MAPPER.createObjectNode();
        variables.put("owner", ref.owner());
        variables.put("repo", ref.repo());
        variables.put("number", ref.number());
        JsonNode root = graphql(query, variables);
        JsonNode pr = root.path("data").path("repository").path("pullRequest");
        if (pr.isMissingNode() || pr.isNull()) {
            throw new IOException("GitHub GraphQL 未返回 PR: " + ref.repoFullName() + "#" + ref.number());
        }
        return new GitHubPullRequest(
                text(pr, "id"),
                text(pr, "id"),
                ref.owner(),
                ref.repo(),
                ref.number(),
                text(pr, "title"),
                text(pr, "body"),
                text(pr, "state"),
                text(pr, "url"),
                text(pr, "baseRefName"),
                text(pr, "baseRefOid"),
                text(pr, "headRefName"),
                text(pr, "headRefOid"),
                text(pr.path("headRepository"), "nameWithOwner"),
                text(pr.path("author"), "login")
        );
    }

    public String fetchDiff(GitHubPrReference ref) throws IOException {
        return getText(repoUrl(ref, "pulls", String.valueOf(ref.number())), "application/vnd.github.v3.diff");
    }

    public List<GitHubChangedFile> fetchChangedFiles(GitHubPrReference ref) throws IOException {
        List<GitHubChangedFile> files = new ArrayList<>();
        for (JsonNode node : getJsonPages(repoUrl(ref, "pulls", String.valueOf(ref.number()), "files"))) {
            files.add(new GitHubChangedFile(
                    text(node, "filename"),
                    text(node, "status"),
                    node.path("additions").asInt(),
                    node.path("deletions").asInt(),
                    node.path("changes").asInt(),
                    text(node, "patch"),
                    text(node, "previous_filename"),
                    text(node, "blob_url")
            ));
        }
        return List.copyOf(files);
    }

    public List<GitHubReviewComment> fetchReviewComments(GitHubPrReference ref) throws IOException {
        List<GitHubReviewComment> comments = new ArrayList<>();
        for (JsonNode node : getJsonPages(repoUrl(ref, "pulls", String.valueOf(ref.number()), "comments"))) {
            comments.add(new GitHubReviewComment(
                    node.path("id").asLong(),
                    text(node, "node_id"),
                    text(node, "path"),
                    integerOrNull(node, "line"),
                    integerOrNull(node, "original_line"),
                    text(node, "side"),
                    text(node, "body"),
                    text(node.path("user"), "login"),
                    text(node, "state"),
                    text(node, "diff_hunk"),
                    text(node, "created_at"),
                    text(node, "updated_at"),
                    integerOrNull(node, "position"),
                    integerOrNull(node, "original_position"),
                    text(node, "commit_id"),
                    text(node, "original_commit_id")
            ));
        }
        return List.copyOf(comments);
    }

    public GitHubCiStatus fetchCiStatus(GitHubPrReference ref, String headSha) throws IOException {
        JsonNode statusesRoot = getJson(repoUrl(ref, "commits", headSha, "status"), "application/vnd.github+json");
        List<GitHubCommitStatus> statuses = new ArrayList<>();
        for (JsonNode node : statusesRoot.path("statuses")) {
            statuses.add(new GitHubCommitStatus(
                    text(node, "context"),
                    text(node, "state"),
                    text(node, "description"),
                    text(node, "target_url")
            ));
        }

        List<GitHubCheckRun> checkRuns = new ArrayList<>();
        HttpUrl checkRunsUrl = repoUrl(ref, "commits", headSha, "check-runs")
                .newBuilder()
                .addQueryParameter("per_page", "100")
                .build();
        JsonNode checksRoot = getJson(checkRunsUrl, "application/vnd.github+json");
        for (JsonNode node : checksRoot.path("check_runs")) {
            checkRuns.add(new GitHubCheckRun(
                    node.path("id").asLong(),
                    text(node, "name"),
                    text(node, "status"),
                    text(node, "conclusion"),
                    text(node, "details_url"),
                    text(node, "html_url"),
                    text(node, "started_at"),
                    text(node, "completed_at")
            ));
        }
        return new GitHubCiStatus(text(statusesRoot, "state"), List.copyOf(statuses), List.copyOf(checkRuns));
    }

    public GitHubPrSnapshot fetchSnapshot(GitHubPrReference ref) throws IOException {
        GitHubPullRequest pr = fetchPullRequest(ref);
        return new GitHubPrSnapshot(
                pr,
                fetchDiff(ref),
                fetchChangedFiles(ref),
                fetchReviewComments(ref),
                fetchCiStatus(ref, pr.headSha())
        );
    }

    public JsonNode graphql(String query, JsonNode variables) throws IOException {
        if (!config.hasToken()) {
            throw new IOException("GitHub GraphQL 需要配置 token（PAICLI_GITHUB_TOKEN / GITHUB_TOKEN / GH_TOKEN）");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("query", query);
        body.set("variables", variables == null ? MAPPER.createObjectNode() : variables);
        Request request = baseRequest(graphqlUrl, "application/json")
                .post(RequestBody.create(MAPPER.writeValueAsString(body), JSON))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            JsonNode root = parseJsonResponse(response);
            if (root.hasNonNull("errors")) {
                throw new IOException("GitHub GraphQL 返回错误: " + root.path("errors"));
            }
            return root;
        }
    }

    public GitHubReviewResult publishReview(GitHubPrReference ref, GitHubReviewRequest review) throws IOException {
        if (!config.hasToken()) {
            throw new IOException("发布 GitHub PR review 需要配置 token（PAICLI_GITHUB_TOKEN / GITHUB_TOKEN / GH_TOKEN）");
        }
        ObjectNode body = MAPPER.createObjectNode();
        if (review.commitId() != null && !review.commitId().isBlank()) {
            body.put("commit_id", review.commitId());
        }
        body.put("event", review.event() == null || review.event().isBlank() ? "COMMENT" : review.event());
        body.put("body", review.body() == null ? "" : review.body());
        ArrayNode comments = body.putArray("comments");
        if (review.comments() != null) {
            for (GitHubReviewFinding finding : review.comments()) {
                validateInlineComment(finding);
                ObjectNode comment = comments.addObject();
                comment.put("path", finding.path());
                comment.put("body", COMMENT_FORMATTER.format(finding));
                comment.put("side", finding.side() == null || finding.side().isBlank() ? "RIGHT" : finding.side());
                comment.put("line", finding.line());
            }
        }
        JsonNode node = postJson(repoUrl(ref, "pulls", String.valueOf(ref.number()), "reviews"), body);
        return new GitHubReviewResult(
                node.path("id").asLong(),
                text(node, "node_id"),
                text(node, "state"),
                text(node, "html_url"),
                text(node, "body")
        );
    }

    private static void validateInlineComment(GitHubReviewFinding finding) throws IOException {
        if (finding == null) {
            throw new IOException("GitHub inline review comment 不能为空");
        }
        if (finding.path() == null || finding.path().isBlank()) {
            throw new IOException("GitHub inline review comment 缺少 path");
        }
        if (finding.line() == null || finding.line() <= 0) {
            throw new IOException("GitHub inline review comment 缺少有效 line");
        }
        if (finding.body() == null || finding.body().isBlank()) {
            throw new IOException("GitHub inline review comment 缺少 body");
        }
    }

    private GitHubPullRequest pullRequestFromRest(GitHubPrReference ref, JsonNode node) {
        return new GitHubPullRequest(
                String.valueOf(node.path("id").asLong()),
                text(node, "node_id"),
                ref.owner(),
                ref.repo(),
                node.path("number").asInt(ref.number()),
                text(node, "title"),
                text(node, "body"),
                text(node, "state"),
                text(node, "html_url"),
                text(node.path("base"), "ref"),
                text(node.path("base"), "sha"),
                text(node.path("head"), "ref"),
                text(node.path("head"), "sha"),
                text(node.path("head").path("repo"), "full_name"),
                text(node.path("user"), "login")
        );
    }

    private List<JsonNode> getJsonPages(HttpUrl firstUrl) throws IOException {
        List<JsonNode> nodes = new ArrayList<>();
        HttpUrl next = firstUrl.newBuilder().addQueryParameter("per_page", "100").build();
        while (next != null) {
            Page page = getJsonPage(next);
            if (!page.body().isArray()) {
                throw new IOException("GitHub 分页响应不是数组: " + next);
            }
            page.body().forEach(nodes::add);
            next = page.nextUrl();
        }
        return nodes;
    }

    private Page getJsonPage(HttpUrl url) throws IOException {
        Request request = baseRequest(url, "application/vnd.github+json").get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            JsonNode body = parseJsonResponse(response);
            return new Page(body, nextUrl(response.header("Link")));
        }
    }

    private JsonNode getJson(HttpUrl url, String accept) throws IOException {
        Request request = baseRequest(url, accept).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            return parseJsonResponse(response);
        }
    }

    private String getText(HttpUrl url, String accept) throws IOException {
        Request request = baseRequest(url, accept).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = responseBody(response);
            if (!response.isSuccessful()) {
                throw new IOException("GitHub API 请求失败: HTTP " + response.code() + " " + preview(body));
            }
            return body;
        }
    }

    private JsonNode postJson(HttpUrl url, JsonNode json) throws IOException {
        Request request = baseRequest(url, "application/vnd.github+json")
                .post(RequestBody.create(MAPPER.writeValueAsString(json), JSON))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            return parseJsonResponse(response);
        }
    }

    private JsonNode parseJsonResponse(Response response) throws IOException {
        String body = responseBody(response);
        if (!response.isSuccessful()) {
            throw new IOException("GitHub API 请求失败: HTTP " + response.code() + " " + preview(body));
        }
        return body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }

    private Request.Builder baseRequest(HttpUrl url, String accept) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "paicli-github-review/1.0");
        if (config.hasToken()) {
            builder.header("Authorization", "Bearer " + config.token().trim());
        }
        return builder;
    }

    private HttpUrl repoUrl(GitHubPrReference ref, String... segments) {
        HttpUrl.Builder builder = apiBaseUrl.newBuilder()
                .addPathSegment("repos")
                .addPathSegment(ref.owner())
                .addPathSegment(ref.repo());
        for (String segment : segments) {
            builder.addPathSegment(segment);
        }
        return builder.build();
    }

    private HttpUrl nextUrl(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) {
            return null;
        }
        Matcher matcher = NEXT_LINK.matcher(linkHeader);
        return matcher.find() ? HttpUrl.get(matcher.group(1)) : null;
    }

    private static String responseBody(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    private static Integer integerOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String preview(String body) {
        if (body == null) {
            return "";
        }
        String normalized = body.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300) + "...";
    }

    private record Page(JsonNode body, HttpUrl nextUrl) {
    }
}
