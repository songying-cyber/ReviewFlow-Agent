package com.paicli.snapshot;

import java.util.List;

public record RestoreResult(
        boolean success,
        String commitId,
        String message,
        List<String> restoredFiles,
        List<String> removedFiles
) {
    public static RestoreResult success(String commitId, List<String> restoredFiles, List<String> removedFiles) {
        return new RestoreResult(true, commitId, "已恢复到快照 " + shortId(commitId), restoredFiles, removedFiles);
    }

    public static RestoreResult failure(String message) {
        return new RestoreResult(false, null, message, List.of(), List.of());
    }

    public String formatForCli() {
        if (!success) {
            return "❌ " + message;
        }
        return """
                ✅ %s
                   写回文件: %d
                   删除文件: %d
                """.formatted(message, restoredFiles.size(), removedFiles.size()).trim();
    }

    private static String shortId(String commitId) {
        return commitId == null || commitId.length() <= 10 ? commitId : commitId.substring(0, 10);
    }
}
