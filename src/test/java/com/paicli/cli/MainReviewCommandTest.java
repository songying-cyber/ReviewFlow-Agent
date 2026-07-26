package com.paicli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.github.GitHubChangedFile;
import com.paicli.github.GitHubCiStatus;
import com.paicli.github.GitHubCommitStatus;
import com.paicli.github.GitHubPrSnapshot;
import com.paicli.github.GitHubPullRequest;
import com.paicli.github.GitHubReviewComment;
import com.paicli.benchmark.CodeReviewBenchOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainReviewCommandTest {

    @Test
    void parseReviewPrCliOptionsAcceptsDryRunJson() {
        Main.ReviewPrCliOptions options = Main.parseReviewPrCliOptions(new String[]{
                "review",
                "pr",
                "https://github.com/acme/widgets/pull/7",
                "--dry-run",
                "--format",
                "json",
                "--include-prompt"
        });

        assertNull(options.error());
        assertEquals("https://github.com/acme/widgets/pull/7", options.prReference());
        assertTrue(options.dryRun());
        assertEquals("json", options.format());
        assertTrue(options.includePrompt());
    }

    @Test
    void parseReviewPrCliOptionsRejectsUnknownOption() {
        Main.ReviewPrCliOptions options = Main.parseReviewPrCliOptions(new String[]{
                "review",
                "pr",
                "https://github.com/acme/widgets/pull/7",
                "--publish"
        });

        assertEquals("未知参数: --publish", options.error());
        assertFalse(options.dryRun());
    }

    @Test
    void buildReviewPrSmokeJsonIncludesStructuredCounts() throws Exception {
        GitHubPrSnapshot snapshot = new GitHubPrSnapshot(
                new GitHubPullRequest(
                        "1",
                        "node",
                        "acme",
                        "widgets",
                        7,
                        "Add login flow",
                        "PR body",
                        "open",
                        "https://github.com/acme/widgets/pull/7",
                        "main",
                        "base123",
                        "feature/login",
                        "head123",
                        "acme/widgets",
                        "alice"),
                "diff --git a/src/App.java b/src/App.java\n@@ -1,1 +1,2 @@\n context\n+hello",
                List.of(new GitHubChangedFile("src/App.java", "modified", 1, 0, 1,
                        "@@ -1,1 +1,2 @@\n context\n+hello", null, "")),
                List.of(new GitHubReviewComment(99L, "node-comment", "src/App.java", 2, 2,
                        "RIGHT", "Looks risky", "bob", "SUBMITTED",
                        "@@ -1,1 +1,2 @@", "2026-08-13T00:00:00Z",
                        "2026-08-13T00:00:00Z", 2, 2, "head123", "head123")),
                new GitHubCiStatus("success",
                        List.of(new GitHubCommitStatus("ci/test", "success", "ok", "")),
                        List.of()));
        String prompt = Main.buildReviewPrPrompt(snapshot);

        JsonNode root = new ObjectMapper().readTree(Main.buildReviewPrSmokeJson(snapshot, prompt, true));

        assertTrue(root.get("ok").asBoolean());
        assertEquals("dry_run", root.get("mode").asText());
        assertTrue(root.get("readyForAgent").asBoolean());
        assertEquals("acme", root.get("pr").get("owner").asText());
        assertEquals(7, root.get("pr").get("number").asInt());
        assertEquals(1, root.get("counts").get("changedFiles").asInt());
        assertEquals(1, root.get("counts").get("reviewComments").asInt());
        assertEquals("success", root.get("ci").get("combinedState").asText());
        assertEquals("src/App.java", root.get("changedFiles").get(0).get("path").asText());
        assertTrue(root.get("prompt").asText().contains("真实 PR 目标基线到 head"));
    }

    @Test
    void parseCodeReviewBenchOptionsAcceptsSmokeLimit() {
        Main.BenchmarkCliParseResult parsed = Main.parseCodeReviewBenchOptions(new String[]{
                "benchmark",
                "code-review-bench",
                "/tmp/code-review-benchmark/offline",
                "--mode",
                "smoke",
                "--limit",
                "1",
                "--only-url",
                "https://github.com/acme/widgets/pull/7",
                "--parallel",
                "3",
                "--timeout-seconds",
                "45",
                "--tool",
                "paicli"
        });

        assertNull(parsed.error());
        assertEquals(CodeReviewBenchOptions.Mode.SMOKE, parsed.options().mode());
        assertEquals(1, parsed.options().limit());
        assertEquals("https://github.com/acme/widgets/pull/7", parsed.options().onlyUrl());
        assertEquals(3, parsed.options().parallelism());
        assertEquals(45, parsed.options().timeoutSeconds());
        assertEquals("paicli", parsed.options().tool());
        assertTrue(parsed.options().outputData().endsWith(Path.of("results", "benchmark_data.paicli.json")));
    }

    @Test
    void parseCodeReviewBenchOptionsInPlaceUsesBenchmarkData() {
        Main.BenchmarkCliParseResult parsed = Main.parseCodeReviewBenchOptions(new String[]{
                "benchmark",
                "code-review-bench",
                "/tmp/code-review-benchmark/offline",
                "--in-place"
        });

        assertNull(parsed.error());
        assertTrue(parsed.options().inPlace());
        assertTrue(parsed.options().outputData().endsWith(Path.of("results", "benchmark_data.json")));
    }
}
