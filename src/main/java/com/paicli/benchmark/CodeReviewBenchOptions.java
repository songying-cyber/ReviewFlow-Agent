package com.paicli.benchmark;

import java.nio.file.Path;
import java.util.Locale;

public record CodeReviewBenchOptions(
        Path offlineDir,
        String tool,
        Mode mode,
        int limit,
        String onlyUrl,
        int parallelism,
        int timeoutSeconds,
        boolean force,
        boolean inPlace,
        boolean checkout,
        Path outputData,
        Path candidatesFile,
        Path worktreeDir
) {
    public enum Mode {
        SMOKE,
        REVIEW;

        public static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return REVIEW;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "smoke", "dry-run", "dryrun" -> SMOKE;
                case "review", "llm" -> REVIEW;
                default -> throw new IllegalArgumentException("--mode 只支持 smoke 或 review");
            };
        }
    }

    public boolean requiresLlm() {
        return mode == Mode.REVIEW;
    }
}
