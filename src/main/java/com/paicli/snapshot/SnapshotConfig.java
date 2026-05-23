package com.paicli.snapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record SnapshotConfig(
        boolean enabled,
        Path snapshotsRoot,
        int maxSnapshots,
        List<String> excludes
) {
    private static final List<String> DEFAULT_EXCLUDES = List.of(
            ".git",
            ".paicli/snapshots",
            "target",
            "node_modules",
            "dist",
            ".idea",
            "*.class",
            "*.jar"
    );

    public static SnapshotConfig fromEnvironment() {
        boolean enabled = readBoolean("paicli.snapshot.enabled", "PAICLI_SNAPSHOT_ENABLED", true);
        Path root = Path.of(readString("paicli.snapshot.dir", "PAICLI_SNAPSHOT_DIR",
                Path.of(System.getProperty("user.home"), ".paicli", "snapshots").toString()));
        int max = readInt("paicli.snapshot.max", "PAICLI_SNAPSHOT_MAX", 50);
        List<String> excludes = mergeExcludes(readString("paicli.snapshot.excludes", "PAICLI_SNAPSHOT_EXCLUDES", ""));
        return new SnapshotConfig(enabled, root, Math.max(1, max), excludes);
    }

    public SnapshotConfig withEnabled(boolean enabled) {
        return new SnapshotConfig(enabled, snapshotsRoot, maxSnapshots, excludes);
    }

    private static boolean readBoolean(String property, String env, boolean fallback) {
        String value = readNullable(property, env);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> fallback;
        };
    }

    private static int readInt(String property, String env, int fallback) {
        String value = readNullable(property, env);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String readString(String property, String env, String fallback) {
        String value = readNullable(property, env);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String readNullable(String property, String env) {
        String value = System.getProperty(property);
        if (value != null) {
            return value;
        }
        return System.getenv(env);
    }

    private static List<String> mergeExcludes(String configured) {
        Set<String> merged = new LinkedHashSet<>(DEFAULT_EXCLUDES);
        if (configured != null && !configured.isBlank()) {
            for (String item : configured.split(",")) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    merged.add(trimmed);
                }
            }
        }
        return new ArrayList<>(merged);
    }
}
