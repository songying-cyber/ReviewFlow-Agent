package com.paicli.wechat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class WechatTextFormatter {
    private WechatTextFormatter() {
    }

    static String format(String text) {
        return normalize(text, true);
    }

    static String formatDelta(String text) {
        return normalize(text, false);
    }

    private static String normalize(String text, boolean trim) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = WechatRenderer.stripAnsi(text)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("▪ ", "")
                .replace("■ ", "");
        normalized = normalizeSupportedMarkdownSubset(normalized)
                .replaceAll("(?m)^[ \\t]+$", "")
                .replaceAll("\\n{3,}", "\n\n");
        return trim ? normalized.trim() : normalized;
    }

    private static String normalizeSupportedMarkdownSubset(String text) {
        StringBuilder out = new StringBuilder(text.length());
        CodeFence fence = null;
        List<String> table = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.stripLeading();
            if (fence != null) {
                if (stripped.startsWith("```")) {
                    appendFence(out, fence);
                    fence = null;
                    if (i < lines.length - 1) {
                        out.append('\n');
                    }
                } else {
                    fence.lines.add(line);
                }
                continue;
            }

            if (stripped.startsWith("```")) {
                appendPendingTable(out, table);
                table.clear();
                fence = new CodeFence(stripped.substring(3).strip());
                continue;
            }

            if (isTableLikeLine(line)) {
                table.add(line);
                continue;
            } else {
                appendPendingTable(out, table);
                table.clear();
                out.append(normalizeMarkdownLine(line));
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        if (fence != null) {
            appendFence(out, fence);
        }
        appendPendingTable(out, table);
        return out.toString();
    }

    private static String normalizeMarkdownLine(String line) {
        String normalized = line
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", "")
                .replace("~~", "");
        normalized = normalizeInlineFence(normalized);
        normalized = normalizeLongFlowLine(normalized);
        String trimmed = normalized.stripLeading();
        if (trimmed.matches("^#{5,6}\\s+.*")) {
            return normalized.replaceFirst("^(\\s*)#{5,6}\\s+", "$1");
        }
        if (trimmed.matches("^#{1,4}\\s*\\S.*")) {
            String heading = trimmed.replaceFirst("^#{1,4}\\s*", "").strip();
            heading = heading.replaceAll("\\*\\*\\s*([^*\\n]*?)\\s*\\*\\*", "$1");
            return heading.isEmpty() ? "" : "**" + stripCjkItalicMarkers(heading) + "**";
        }
        normalized = trimBoldMarkers(normalized);
        return stripCjkItalicMarkers(normalized);
    }

    private static String normalizeInlineFence(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("```") && trimmed.endsWith("```") && trimmed.length() > 6) {
            String inner = trimmed.substring(3, trimmed.length() - 3).strip();
            return line.substring(0, line.indexOf("```")) + inner;
        }
        if (trimmed.endsWith("```") && !trimmed.equals("```")) {
            return line.substring(0, line.lastIndexOf("```")).stripTrailing();
        }
        return line;
    }

    private static String trimBoldMarkers(String line) {
        return line.replaceAll("\\*\\*\\s*([^*\\n]*?)\\s*\\*\\*", "**$1**");
    }

    private static String trimBoldContent(String text) {
        return text.replaceAll("\\*\\*\\s*([^*\\n]*?)\\s*\\*\\*", "**$1**");
    }

    private static String normalizeLongFlowLine(String line) {
        if (line.length() < 28 || !line.contains("→")) {
            return line;
        }
        String[] parts = line.split("\\s*→\\s*");
        if (parts.length < 3) {
            return line;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(parts[0].strip());
        for (int i = 1; i < parts.length; i++) {
            sb.append("\n→ ").append(parts[i].strip());
        }
        return sb.toString();
    }

    private static void appendFence(StringBuilder out, CodeFence fence) {
        String body = String.join("\n", fence.lines).strip();
        if (body.isEmpty()) {
            return;
        }
        if (!isCodeFenceLanguage(fence.language) && looksLikeProseFlow(body)) {
            out.append(normalizeMarkdownLine(body));
            return;
        }
        out.append("```");
        if (!fence.language.isBlank()) {
            out.append(fence.language);
        }
        out.append('\n').append(body).append("\n```");
    }

    private static boolean isCodeFenceLanguage(String language) {
        if (language == null || language.isBlank()) {
            return false;
        }
        String lang = language.toLowerCase(Locale.ROOT);
        return lang.matches("java|json|xml|yaml|yml|bash|sh|zsh|python|py|javascript|js|typescript|ts|sql|html|css|go|rust|rs|kotlin|kt|swift|php|ruby|rb|c|cpp|csharp|cs|diff|properties|toml");
    }

    private static boolean looksLikeProseFlow(String body) {
        return body.contains("→")
                || body.matches("(?s).*[\\p{IsHan}].*")
                || body.length() > 120;
    }

    private static boolean isTableLikeLine(String line) {
        String trimmed = line == null ? "" : line.trim();
        return trimmed.startsWith("|") && trimmed.indexOf('|', 1) > 0;
    }

    private static void appendPendingTable(StringBuilder out, List<String> tableLines) {
        if (tableLines.isEmpty()) {
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : tableLines) {
            if (isTableSeparator(line)) {
                continue;
            }
            List<String> cells = parseTableRow(line);
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        List<String> header = rows.get(0);
        if (rows.size() == 1 && header.size() >= 4 && header.size() % 2 == 0) {
            for (int i = 0; i < header.size(); i += 2) {
                out.append("- **").append(header.get(i)).append("**：").append(header.get(i + 1));
                if (i + 2 < header.size()) {
                    out.append('\n');
                }
            }
            return;
        }
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            out.append(formatTableRow(header, row));
            if (i < rows.size() - 1) {
                out.append('\n');
            }
        }
        if (rows.size() == 1) {
            out.append(String.join(" / ", header));
        }
    }

    private static String formatTableRow(List<String> header, List<String> row) {
        if (row.size() == 2) {
            return "- **" + row.get(0) + "**：" + row.get(1);
        }
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < row.size(); i++) {
            String key = i < header.size() ? header.get(i) : "列" + (i + 1);
            pairs.add(key + "：" + row.get(i));
        }
        return "- " + String.join("；", pairs);
    }

    private static List<String> parseTableRow(String line) {
        String normalized = line.trim();
        normalized = normalized.replaceAll("(\\|\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|)(?=\\|)", "$1\n");
        List<String> cells = new ArrayList<>();
        for (String part : normalized.split("\\|")) {
            String cell = part.strip();
            if (!cell.isEmpty() && !isTableSeparatorCell(cell)) {
                cells.add(stripCjkItalicMarkers(trimBoldMarkers(cell)));
            }
        }
        return cells;
    }

    private static boolean isTableSeparator(String line) {
        String trimmed = line == null ? "" : line.trim();
        return trimmed.matches("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$");
    }

    private static boolean isTableSeparatorCell(String value) {
        return value.matches(":?-{3,}:?");
    }

    private static String stripCjkItalicMarkers(String line) {
        String normalized = line;
        normalized = normalized.replaceAll("(?<!\\*)\\*([^*\\n]*[\\p{IsHan}\\u3040-\\u30FF\\uAC00-\\uD7AF][^*\\n]*)\\*(?!\\*)", "$1");
        normalized = normalized.replaceAll("(?<!_)_([^_\\n]*[\\p{IsHan}\\u3040-\\u30FF\\uAC00-\\uD7AF][^_\\n]*)_(?!_)", "$1");
        return normalized;
    }

    private static final class CodeFence {
        private final String language;
        private final List<String> lines = new ArrayList<>();

        private CodeFence(String language) {
            this.language = language == null ? "" : language;
        }
    }
}
