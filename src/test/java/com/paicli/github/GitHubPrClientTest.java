package com.paicli.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubPrClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fetchSnapshotLoadsPullRequestDiffFilesCommentsAndCiStatus() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("""
                    {
                      "id": 1007,
                      "node_id": "PR_node",
                      "number": 7,
                      "title": "Fix workflow recovery",
                      "body": "Adds recovery",
                      "state": "open",
                      "html_url": "https://github.com/acme/widgets/pull/7",
                      "base": {"ref": "main", "sha": "base123"},
                      "head": {"ref": "feature/recovery", "sha": "head123", "repo": {"full_name": "acme/widgets"}},
                      "user": {"login": "alice"}
                    }
                    """));
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/plain")
                    .setBody("diff --git a/src/App.java b/src/App.java\n+hello\n"));
            server.enqueue(json("""
                    [
                      {
                        "filename": "src/App.java",
                        "status": "modified",
                        "additions": 3,
                        "deletions": 1,
                        "changes": 4,
                        "patch": "@@ -1 +1 @@",
                        "blob_url": "https://github.com/acme/widgets/blob/head123/src/App.java"
                      }
                    ]
                    """));
            server.enqueue(json("""
                    [
                      {
                        "id": 9001,
                        "node_id": "comment_node",
                        "path": "src/App.java",
                        "line": 12,
                        "original_line": 10,
                        "side": "RIGHT",
                        "body": "Please add a test.",
                        "user": {"login": "reviewer"},
                        "state": "SUBMITTED",
                        "diff_hunk": "@@ -10 +12 @@",
                        "created_at": "2026-08-13T00:00:00Z",
                        "updated_at": "2026-08-13T00:01:00Z"
                      }
                    ]
                    """));
            server.enqueue(json("""
                    {
                      "state": "failure",
                      "statuses": [
                        {"context": "ci/unit", "state": "failure", "description": "unit failed", "target_url": "https://ci.example/unit"}
                      ]
                    }
                    """));
            server.enqueue(json("""
                    {
                      "check_runs": [
                        {
                          "id": 77,
                          "name": "build",
                          "status": "completed",
                          "conclusion": "success",
                          "details_url": "https://ci.example/build",
                          "html_url": "https://github.com/acme/widgets/actions/runs/1",
                          "started_at": "2026-08-13T00:00:00Z",
                          "completed_at": "2026-08-13T00:02:00Z"
                        }
                      ]
                    }
                    """));
            server.start();

            GitHubPrClient client = client(server);
            GitHubPrSnapshot snapshot = client.fetchSnapshot(new GitHubPrReference("acme", "widgets", 7));

            assertEquals("Fix workflow recovery", snapshot.pullRequest().title());
            assertEquals("head123", snapshot.pullRequest().headSha());
            assertTrue(snapshot.diff().contains("diff --git"));
            assertEquals("src/App.java", snapshot.changedFiles().get(0).filename());
            assertEquals("Please add a test.", snapshot.reviewComments().get(0).body());
            assertEquals("failure", snapshot.ciStatus().combinedState());
            assertEquals("ci/unit", snapshot.ciStatus().statuses().get(0).context());
            assertEquals("build", snapshot.ciStatus().checkRuns().get(0).name());

            RecordedRequest prRequest = server.takeRequest(1, TimeUnit.SECONDS);
            RecordedRequest diffRequest = server.takeRequest(1, TimeUnit.SECONDS);
            RecordedRequest filesRequest = server.takeRequest(1, TimeUnit.SECONDS);
            RecordedRequest commentsRequest = server.takeRequest(1, TimeUnit.SECONDS);
            RecordedRequest statusRequest = server.takeRequest(1, TimeUnit.SECONDS);
            RecordedRequest checksRequest = server.takeRequest(1, TimeUnit.SECONDS);

            assertEquals("/repos/acme/widgets/pulls/7", prRequest.getPath());
            assertEquals("application/vnd.github+json", prRequest.getHeader("Accept"));
            assertEquals("Bearer test-token", prRequest.getHeader("Authorization"));
            assertEquals("/repos/acme/widgets/pulls/7", diffRequest.getPath());
            assertEquals("application/vnd.github.v3.diff", diffRequest.getHeader("Accept"));
            assertEquals("/repos/acme/widgets/pulls/7/files?per_page=100", filesRequest.getPath());
            assertEquals("/repos/acme/widgets/pulls/7/comments?per_page=100", commentsRequest.getPath());
            assertEquals("/repos/acme/widgets/commits/head123/status", statusRequest.getPath());
            assertEquals("/repos/acme/widgets/commits/head123/check-runs?per_page=100", checksRequest.getPath());
        }
    }

    @Test
    void fetchPullRequestViaGraphqlPostsQueryAndVariables() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("""
                    {
                      "data": {
                        "repository": {
                          "pullRequest": {
                            "id": "PR_graphql",
                            "title": "GraphQL PR",
                            "body": "Body",
                            "state": "OPEN",
                            "url": "https://github.com/acme/widgets/pull/7",
                            "baseRefName": "main",
                            "baseRefOid": "base123",
                            "headRefName": "feature",
                            "headRefOid": "head123",
                            "headRepository": {"nameWithOwner": "acme/widgets"},
                            "author": {"login": "alice"}
                          }
                        }
                      }
                    }
                    """));
            server.start();

            GitHubPullRequest pr = client(server).fetchPullRequestViaGraphql(new GitHubPrReference("acme", "widgets", 7));

            assertEquals("GraphQL PR", pr.title());
            assertEquals("head123", pr.headSha());

            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("/graphql", request.getPath());
            assertEquals("POST", request.getMethod());
            assertEquals("Bearer test-token", request.getHeader("Authorization"));

            JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
            assertTrue(body.path("query").asText().contains("pullRequest"));
            assertEquals("acme", body.path("variables").path("owner").asText());
            assertEquals("widgets", body.path("variables").path("repo").asText());
            assertEquals(7, body.path("variables").path("number").asInt());
        }
    }

    @Test
    void publishReviewPostsReviewPayload() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("""
                    {
                      "id": 123,
                      "node_id": "review_node",
                      "state": "COMMENTED",
                      "html_url": "https://github.com/acme/widgets/pull/7#pullrequestreview-123",
                      "body": "Review summary"
                    }
                    """));
            server.start();

            GitHubReviewResult result = client(server).publishReview(
                    new GitHubPrReference("acme", "widgets", 7),
                    GitHubReviewRequest.comment(
                            "head123",
                            "Review summary",
                            List.of(GitHubReviewFinding.fileComment("src/App.java", 12, "Use the existing retry helper."))));

            assertEquals(123, result.id());
            assertEquals("COMMENTED", result.state());

            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("/repos/acme/widgets/pulls/7/reviews", request.getPath());
            assertEquals("POST", request.getMethod());
            assertEquals("application/vnd.github+json", request.getHeader("Accept"));

            JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
            assertEquals("head123", body.path("commit_id").asText());
            assertEquals("COMMENT", body.path("event").asText());
            assertEquals("Review summary", body.path("body").asText());
            assertEquals("src/App.java", body.path("comments").get(0).path("path").asText());
            assertEquals(12, body.path("comments").get(0).path("line").asInt());
            assertEquals("RIGHT", body.path("comments").get(0).path("side").asText());
            assertEquals("Use the existing retry helper.", body.path("comments").get(0).path("body").asText());
        }
    }

    @Test
    void tokenRequiredForGraphqlAndPublishingReviews() {
        GitHubPrClient client = new GitHubPrClient(
                new GitHubConfig("", "https://api.github.test", "https://api.github.test/graphql"),
                new OkHttpClient());
        GitHubPrReference ref = new GitHubPrReference("acme", "widgets", 7);

        assertThrows(Exception.class, () -> client.fetchPullRequestViaGraphql(ref));
        assertThrows(Exception.class, () -> client.publishReview(ref, GitHubReviewRequest.comment("head", "body", List.of())));
    }

    private static GitHubPrClient client(MockWebServer server) {
        return new GitHubPrClient(
                new GitHubConfig("test-token", server.url("/").toString(), server.url("/graphql").toString()),
                new OkHttpClient());
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
