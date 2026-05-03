package com.paicli.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SensitivePagePolicyTest {

    @Test
    void matchesDefaultGithubSettingsRule(@TempDir Path tempDir) {
        SensitivePagePolicy policy = new SensitivePagePolicy(tempDir.resolve("missing.txt"));

        assertTrue(policy.isSensitive("https://github.com/settings/profile"));
    }

    @Test
    void matchesDefaultRepoSettingsRule(@TempDir Path tempDir) {
        SensitivePagePolicy policy = new SensitivePagePolicy(tempDir.resolve("missing.txt"));

        assertTrue(policy.isSensitive("https://github.com/owner/repo/settings/actions"));
    }

    @Test
    void doesNotMatchNormalGithubPage(@TempDir Path tempDir) {
        SensitivePagePolicy policy = new SensitivePagePolicy(tempDir.resolve("missing.txt"));

        assertFalse(policy.isSensitive("https://github.com/owner/repo"));
    }

    @Test
    void loadsUserRules(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("sensitive_patterns.txt");
        Files.writeString(rules, "*://example.com/private/*\n");
        SensitivePagePolicy policy = new SensitivePagePolicy(rules);

        assertTrue(policy.isSensitive("https://example.com/private/a"));
    }

    @Test
    void ignoresBlankAndCommentLines(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("sensitive_patterns.txt");
        Files.writeString(rules, "\n# comment\n*://example.com/admin/*\n");
        SensitivePagePolicy policy = new SensitivePagePolicy(rules);

        assertTrue(policy.isSensitive("https://example.com/admin/index"));
        assertFalse(policy.isSensitive("https://example.com/comment"));
    }

    @Test
    void missingUserFileKeepsDefaults(@TempDir Path tempDir) {
        SensitivePagePolicy policy = new SensitivePagePolicy(tempDir.resolve("missing.txt"));

        assertTrue(policy.isSensitive("https://paypal.com/home"));
    }

    @Test
    void matchingIsCaseInsensitive(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("sensitive_patterns.txt");
        Files.writeString(rules, "*://Example.COM/Admin/*\n");
        SensitivePagePolicy policy = new SensitivePagePolicy(rules);

        assertTrue(policy.isSensitive("https://example.com/admin/users"));
    }

    @Test
    void questionMarkMatchesSingleCharacter(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("sensitive_patterns.txt");
        Files.writeString(rules, "*://example.com/user?/settings\n");
        SensitivePagePolicy policy = new SensitivePagePolicy(rules);

        assertTrue(policy.isSensitive("https://example.com/user1/settings"));
        assertFalse(policy.isSensitive("https://example.com/user12/settings"));
    }

    @Test
    void regexCharactersAreEscaped(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("sensitive_patterns.txt");
        Files.writeString(rules, "*://example.com/a+b/*\n");
        SensitivePagePolicy policy = new SensitivePagePolicy(rules);

        assertTrue(policy.isSensitive("https://example.com/a+b/x"));
        assertFalse(policy.isSensitive("https://example.com/aaab/x"));
    }

    @Test
    void matchReturnsPattern(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("sensitive_patterns.txt");
        Files.writeString(rules, "*://example.com/billing/*\n");
        SensitivePagePolicy policy = new SensitivePagePolicy(rules);

        SensitivePagePolicy.MatchResult result = policy.match("https://example.com/billing/card");

        assertTrue(result.matched());
        assertEquals("*://example.com/billing/*", result.pattern());
    }
}
