package com.paicli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.github.GitHubChangedFile;
import com.paicli.github.GitHubCiStatus;
import com.paicli.github.GitHubConfig;
import com.paicli.github.GitHubPrClient;
import com.paicli.github.GitHubPrReference;
import com.paicli.github.GitHubPrSnapshot;
import com.paicli.github.GitHubPullRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeReviewBenchRunnerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void parseFindingsAcceptsJsonFenceAndNormalizesSeverity() throws Exception {
        String content = """
                ```json
                {
                  "summary": "found issues",
                  "findings": [
                    {
                      "severity": "High",
                      "category": "bug",
                      "path": "src/App.java",
                      "line": 42,
                      "title": "Null crash",
                      "body": "The handler dereferences user without a null check."
                    }
                  ]
                }
                ```
                """;

        List<CodeReviewBenchRunner.Finding> findings = CodeReviewBenchRunner.parseFindings(content);

        assertEquals(1, findings.size());
        assertEquals("P1", findings.get(0).severity());
        assertEquals("bug", findings.get(0).category());
        assertEquals("src/App.java", findings.get(0).path());
        assertEquals(42, findings.get(0).line());
        assertEquals("Null crash", findings.get(0).title());
    }

    @Test
    void parseFindingsAcceptsCommonModelJsonLooseness() throws Exception {
        String content = """
                {
                  findings: [
                    {
                      severity: 'Low',
                      category: 'doc_defect',
                      path: 'docs/guide.md',
                      line: 12,
                      title: 'Missing migration note',
                      body: 'Document the behavior change.',
                    },
                  ],
                }
                """;

        List<CodeReviewBenchRunner.Finding> findings = CodeReviewBenchRunner.parseFindings(content);

        assertEquals(1, findings.size());
        assertEquals("P3", findings.get(0).severity());
        assertEquals("docs/guide.md", findings.get(0).path());
    }

    @Test
    void failedReviewsStillConsumeLimit() throws Exception {
        Path offline = tempDir.resolve("offline");
        Files.createDirectories(offline.resolve("results"));
        ObjectNode data = MAPPER.createObjectNode();
        data.set("https://github.com/acme/widgets/pull/1", benchmarkEntry());
        data.set("https://github.com/acme/widgets/pull/2", benchmarkEntry());
        MAPPER.writeValue(offline.resolve("results").resolve("benchmark_data.json").toFile(), data);

        CodeReviewBenchRunner runner = new CodeReviewBenchRunner(new FailingGitHubClient());
        CodeReviewBenchOptions options = new CodeReviewBenchOptions(
                offline,
                "paicli",
                CodeReviewBenchOptions.Mode.SMOKE,
                1,
                null,
                1,
                10,
                true,
                false,
                true,
                offline.resolve("results").resolve("benchmark_data.paicli.json"),
                offline.resolve("results").resolve("openai_gpt-4o-mini").resolve("candidates.json"),
                offline.resolve("results").resolve("paicli-worktrees"));

        CodeReviewBenchRunner.RunResult result = runner.run(options, null);

        assertEquals(0, result.processed());
        assertEquals(1, result.failed());
        assertEquals(1, result.failures().size());
    }

    @Test
    void benchmarkPromptIncludesHighRecallInstructionsAndRepositoryContext() {
        GitHubPrSnapshot snapshot = new GitHubPrSnapshot(
                new GitHubPullRequest(
                        "1",
                        "node",
                        "acme",
                        "widgets",
                        7,
                        "Fix translated messages",
                        "",
                        "open",
                        "https://github.com/acme/widgets/pull/7",
                        "main",
                        "base123",
                        "feature/messages",
                        "head123",
                        "acme/widgets",
                        "alice"),
                "@@ -40,0 +41,2 @@\n+picocli.exit(CompatibilityResult.FEATURE_DISABLED);\n+return;",
                List.of(new GitHubChangedFile("messages_lt.properties", "modified", 1, 1, 2,
                        "@@ -1,1 +1,1 @@\n-old\n+new", null, "")),
                List.of(),
                new GitHubCiStatus("success", List.of(), List.of()));
        CodeReviewBenchRunner.RepositoryContext context = new CodeReviewBenchRunner.RepositoryContext(
                true,
                "",
                List.of(new CodeReviewBenchRunner.RepositoryFileContext(
                        "messages_lt.properties",
                        "totpStep1=Installa una delle seguenti applicazioni sul tuo cellulare:")));

        String prompt = CodeReviewBenchRunner.buildBenchmarkReviewPrompt(snapshot, context);

        assertTrue(prompt.contains("Code Review Bench 模式"));
        assertTrue(prompt.contains("不要过度克制"));
        assertTrue(prompt.contains("translation/locale"));
        assertTrue(prompt.contains("Patch By File"));
        assertTrue(prompt.contains("Checked Out Head File Context"));
        assertTrue(prompt.contains("Installa una delle seguenti"));
        assertTrue(prompt.contains("picocli.exit"));
        assertTrue(prompt.contains("Targeted Audit Hints"));
        assertTrue(prompt.contains("Treat picocli as an in-scope command helper"));
        assertTrue(prompt.contains("missing release notes / migration guidance"));
        assertTrue(prompt.contains("Release/migration docs"));
        assertTrue(prompt.contains("API misuse"));

        String filterContext = CodeReviewBenchRunner.buildPrecisionFilterContext(snapshot, context);
        assertTrue(filterContext.contains("PR Summary"));
        assertTrue(filterContext.contains("Touched File Evidence Summary"));
        assertTrue(filterContext.length() < prompt.length());
    }

    private static ObjectNode benchmarkEntry() {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("pr_title", "Test PR");
        entry.putNull("original_url");
        entry.put("source_repo", "widgets");
        entry.putArray("golden_comments");
        entry.put("golden_source_file", "widgets.json");
        entry.putArray("reviews");
        return entry;
    }

    private static class FailingGitHubClient extends GitHubPrClient {
        FailingGitHubClient() {
            super(new GitHubConfig("", GitHubConfig.DEFAULT_API_BASE_URL, GitHubConfig.DEFAULT_GRAPHQL_URL));
        }

        @Override
        public GitHubPrSnapshot fetchSnapshot(GitHubPrReference ref) throws IOException {
            throw new IOException("boom");
        }
    }
}
