package com.paicli.github;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReviewPreparerTest {

    @Test
    void preparesOnlyCommentsThatStillMapToCurrentDiff() {
        GitHubPrSnapshot snapshot = snapshot(List.of(new GitHubChangedFile(
                "src/App.java",
                "modified",
                2,
                1,
                3,
                """
                        @@ -20,3 +20,4 @@
                         before
                        -old
                        +new
                        +extra
                        """,
                null,
                "")));

        GitHubPreparedReview prepared = new GitHubReviewPreparer().prepare(
                snapshot,
                "COMMENT",
                "Review summary",
                List.of(
                        GitHubReviewFinding.fileComment("src/App.java", 21, "Use the helper."),
                        GitHubReviewFinding.suggestion("src/App.java", 22, "Simplify branch", "This can be smaller.", "return ok;"),
                        GitHubReviewFinding.fileComment("src/App.java", 99, "Outdated line."),
                        GitHubReviewFinding.fileComment("src/Missing.java", 1, "Missing file.")));

        assertEquals("head123", prepared.request().commitId());
        assertEquals(2, prepared.request().comments().size());
        assertEquals(2, prepared.skippedFindings().size());
        assertEquals("src/App.java", prepared.request().comments().get(0).path());
        assertEquals(21, prepared.request().comments().get(0).line());
        assertTrue(prepared.request().comments().get(1).body().contains("```suggestion"));
        assertTrue(prepared.skippedFindings().get(0).reason().contains("outdated"));
        assertTrue(prepared.skippedFindings().get(1).reason().contains("not in the current PR diff"));
    }

    @Test
    void detectsExistingOutdatedReviewComments() {
        GitHubPrSnapshot snapshot = snapshot(
                List.of(new GitHubChangedFile("src/App.java", "modified", 1, 0, 1, """
                        @@ -1,1 +1,1 @@
                        -old
                        +new
                        """, null, "")),
                List.of(
                        new GitHubReviewComment(1, "node1", "src/App.java", 1, 1, "RIGHT", "current",
                                "alice", "SUBMITTED", "", "", "", 1, 1, "head123", "head123"),
                        new GitHubReviewComment(2, "node2", "src/App.java", null, 1, "RIGHT", "outdated",
                                "alice", "SUBMITTED", "", "", "", null, 1, "head123", "base123")));

        assertEquals(1, snapshot.outdatedReviewComments().size());
        assertEquals("outdated", snapshot.outdatedReviewComments().get(0).body());
    }

    private static GitHubPrSnapshot snapshot(List<GitHubChangedFile> files) {
        return snapshot(files, List.of());
    }

    private static GitHubPrSnapshot snapshot(List<GitHubChangedFile> files, List<GitHubReviewComment> comments) {
        return new GitHubPrSnapshot(
                new GitHubPullRequest("1", "node", "acme", "widgets", 7, "title", "body", "open",
                        "https://github.com/acme/widgets/pull/7", "main", "base123",
                        "feature", "head123", "acme/widgets", "alice"),
                "",
                files,
                comments,
                new GitHubCiStatus("success", List.of(), List.of()));
    }
}
