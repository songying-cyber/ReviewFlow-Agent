package com.paicli.cli;

import com.paicli.github.GitHubChangedFile;
import com.paicli.github.GitHubCiStatus;
import com.paicli.github.GitHubPrSnapshot;
import com.paicli.github.GitHubPullRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MainReviewPromptTest {

    @Test
    void reviewPromptIncludesCodeReviewStandards() {
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
                "diff --git a/src/App.java b/src/App.java\n+hello",
                List.of(new GitHubChangedFile("src/App.java", "modified", 1, 0, 1,
                        "@@ -1,1 +1,2 @@\n context\n+hello", null, "")),
                List.of(),
                new GitHubCiStatus("success", List.of(), List.of()));

        String prompt = Main.buildReviewPrPrompt(snapshot);

        assertTrue(prompt.contains("真实 PR 目标基线到 head"));
        assertTrue(prompt.contains("安全和鉴权"));
        assertTrue(prompt.contains("数据库迁移"));
        assertTrue(prompt.contains("配置和环境变量"));
        assertTrue(prompt.contains("部署和网关"));
        assertTrue(prompt.contains("迭代文档"));
        assertTrue(prompt.contains("git diff --check"));
        assertTrue(prompt.contains("[P0|P1|P2|P3] 问题标题"));
        assertTrue(prompt.contains("已通过的命令"));
        assertTrue(prompt.contains("未验证范围和剩余风险"));
    }
}
