package com.paicli.github;

public record GitHubChangedFile(
        String filename,
        String status,
        int additions,
        int deletions,
        int changes,
        String patch,
        String previousFilename,
        String blobUrl
) {
}
