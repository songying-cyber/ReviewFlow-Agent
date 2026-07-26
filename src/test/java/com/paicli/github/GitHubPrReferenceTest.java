package com.paicli.github;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHubPrReferenceTest {

    @Test
    void parsesGithubPullRequestUrl() {
        GitHubPrReference ref = GitHubPrReference.parse("https://github.com/acme/widgets/pull/42");

        assertEquals("acme", ref.owner());
        assertEquals("widgets", ref.repo());
        assertEquals(42, ref.number());
        assertEquals("acme/widgets", ref.repoFullName());
    }

    @Test
    void parsesShortPullRequestReference() {
        GitHubPrReference ref = GitHubPrReference.parse("acme/widgets#42");

        assertEquals("acme", ref.owner());
        assertEquals("widgets", ref.repo());
        assertEquals(42, ref.number());
    }

    @Test
    void parsesGithubEnterprisePullRequestUrl() {
        GitHubPrReference ref = GitHubPrReference.parse("https://github.internal/acme/widgets.git/pull/42");

        assertEquals("acme", ref.owner());
        assertEquals("widgets", ref.repo());
        assertEquals(42, ref.number());
    }

    @Test
    void rejectsUnsupportedReference() {
        assertThrows(IllegalArgumentException.class, () -> GitHubPrReference.parse("not-a-pr"));
    }

    @Test
    void normalizesConfigValues() {
        GitHubConfig config = new GitHubConfig(" test-token ", "https://github.internal/api/v3/", "");

        assertEquals("test-token", config.token());
        assertEquals("https://github.internal/api/v3", config.apiBaseUrl());
        assertEquals("https://github.internal/api/v3/graphql", config.graphqlUrl());
    }
}
