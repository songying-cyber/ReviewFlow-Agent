package com.paicli.github;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public record GitHubConfig(String token, String apiBaseUrl, String graphqlUrl) {
    public static final String DEFAULT_API_BASE_URL = "https://api.github.com";
    public static final String DEFAULT_GRAPHQL_URL = "https://api.github.com/graphql";

    public GitHubConfig {
        token = token == null ? "" : token.trim();
        apiBaseUrl = normalizeUrl(apiBaseUrl, DEFAULT_API_BASE_URL);
        graphqlUrl = normalizeUrl(graphqlUrl, deriveGraphqlUrl(apiBaseUrl));
    }

    public static GitHubConfig fromEnvironment() {
        String token = firstNonBlank(
                System.getProperty("paicli.github.token"),
                System.getenv("PAICLI_GITHUB_TOKEN"),
                System.getenv("GITHUB_TOKEN"),
                System.getenv("GH_TOKEN"),
                readFromDotEnv("PAICLI_GITHUB_TOKEN"),
                readFromDotEnv("GITHUB_TOKEN"),
                readFromDotEnv("GH_TOKEN"));
        String apiBaseUrl = firstNonBlank(
                System.getProperty("paicli.github.api.baseUrl"),
                System.getenv("PAICLI_GITHUB_API_BASE_URL"),
                readFromDotEnv("PAICLI_GITHUB_API_BASE_URL"),
                DEFAULT_API_BASE_URL);
        String graphqlUrl = firstNonBlank(
                System.getProperty("paicli.github.graphql.url"),
                System.getenv("PAICLI_GITHUB_GRAPHQL_URL"),
                readFromDotEnv("PAICLI_GITHUB_GRAPHQL_URL"),
                deriveGraphqlUrl(apiBaseUrl));
        return new GitHubConfig(token, apiBaseUrl, graphqlUrl);
    }

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    private static String deriveGraphqlUrl(String apiBaseUrl) {
        String normalized = normalizeUrl(apiBaseUrl, DEFAULT_API_BASE_URL);
        if (DEFAULT_API_BASE_URL.equals(normalized)) {
            return DEFAULT_GRAPHQL_URL;
        }
        return normalized + "/graphql";
    }

    private static String normalizeUrl(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = stripQuotes(value.trim());
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isBlank() ? fallback : trimmed;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return stripQuotes(value.trim());
            }
        }
        return null;
    }

    private static String readFromDotEnv(String key) {
        File[] envFiles = {new File(".env"), new File(System.getProperty("user.home"), ".env")};
        for (File envFile : envFiles) {
            if (!envFile.exists()) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || !line.startsWith(key + "=")) {
                        continue;
                    }
                    return stripQuotes(line.substring((key + "=").length()).trim());
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private static String stripQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
