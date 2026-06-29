package com.paicli.tool;

import java.nio.file.Path;
import java.util.List;

interface CodeSearchEngine {
    CodeSearchResult search(CodeSearchRequest request);
}

record CodeSearchRequest(
        String query,
        Path root,
        Path projectRoot,
        String glob,
        boolean regex,
        boolean caseSensitive,
        int contextLines,
        int maxResults,
        int headLimit
) {}

record CodeSearchResult(
        String engine,
        List<GrepMatch> matches,
        boolean partial,
        String partialReason
) {}

record ContextLine(int lineNumber, String text) {}

record GrepMatch(String file, int lineNumber, List<ContextLine> context) {}
