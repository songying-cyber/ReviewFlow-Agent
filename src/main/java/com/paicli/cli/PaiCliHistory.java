package com.paicli.cli;

import org.jline.reader.impl.history.DefaultHistory;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

final class PaiCliHistory extends DefaultHistory {
    private static final int MAX_HISTORY_LINE_LENGTH = 8_000;
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i).*(api[_-]?key|authorization|bearer|password|passwd|secret|token)\\s*[:=].*");
    private static final Pattern SECRET_FLAG = Pattern.compile(
            "(?i).*(--?(api[_-]?key|authorization|password|passwd|secret|token)\\b|\\bbearer\\b).*");
    private static final Pattern BASE64_IMAGE = Pattern.compile(
            "(?i).*(data:image/|@image:data:|[A-Za-z0-9+/]{240,}={0,2}).*");

    @Override
    public void add(Instant time, String line) {
        if (shouldSkip(line)) {
            return;
        }
        super.add(time, line);
    }

    static boolean shouldSkip(String line) {
        if (line == null) {
            return true;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_HISTORY_LINE_LENGTH) {
            return true;
        }
        if (SECRET_ASSIGNMENT.matcher(trimmed).matches()
                || SECRET_FLAG.matcher(trimmed).matches()
                || BASE64_IMAGE.matcher(trimmed).matches()) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.contains("bearer ")
                || lower.contains("authorization:")
                || lower.contains("-----begin ")
                || lower.contains("private key");
    }
}
