package com.paicli.util;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 轻量终端 Markdown 渲染器。
 *
 * 目标不是完整支持所有 Markdown 语法，而是把常见的标题、列表、表格、引用和代码块
 * 渲染成更适合 CLI 终端阅读的纯文本布局。
 */
public final class TerminalMarkdownRenderer {
    private static final Pattern ORDERED_LIST = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.*)$");
    private static final Pattern UNORDERED_LIST = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?\\s*$");
    private static final int COMPACT_TABLE_MAX_CELL_LENGTH = 24;
    private static final int COMPACT_TABLE_MAX_TOTAL_WIDTH = 80;

    private final PrintStream out;
    private final StringBuilder pending = new StringBuilder();
    private final List<String> pendingTable = new ArrayList<>();
    private boolean inCodeBlock;
    private boolean needsLineBreakBeforeNextBlock;
    private boolean lastOutputBlank;
    private BlockType lastBlockType = BlockType.NONE;

    public TerminalMarkdownRenderer(PrintStream out) {
        this.out = out;
    }

    public void append(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }

        pending.append(chunk);
        flushCompleteLines();
    }

    public void finish() {
        if (pending.length() > 0) {
            processLine(pending.toString());
            pending.setLength(0);
        }
        flushPendingTable();
    }

    public static String render(String markdown) {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        PrintStream stream = new PrintStream(buffer);
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(stream);
        renderer.append(markdown);
        renderer.finish();
        stream.flush();
        return buffer.toString();
    }

    private void flushCompleteLines() {
        int newlineIndex;
        while ((newlineIndex = indexOfNewline(pending)) >= 0) {
            String line = pending.substring(0, newlineIndex);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            processLine(line);
            pending.delete(0, newlineIndex + 1);
        }
    }

    private int indexOfNewline(StringBuilder builder) {
        for (int i = 0; i < builder.length(); i++) {
            if (builder.charAt(i) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private void processLine(String rawLine) {
        String line = rawLine == null ? "" : rawLine;

        if (line.trim().startsWith("```")) {
            flushPendingTable();
            toggleCodeBlock(line.trim().substring(3).trim());
            return;
        }

        if (inCodeBlock) {
            writeLine("    " + line, BlockType.CODE_BLOCK);
            return;
        }

        if (looksLikeTableLine(line)) {
            pendingTable.add(line);
            return;
        }

        flushPendingTable();

        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            writeBlankLine();
            return;
        }

        var headingMatcher = HEADING.matcher(line);
        if (headingMatcher.matches()) {
            renderHeading(headingMatcher.group(1).length(), sanitizeInline(headingMatcher.group(2).trim()));
            return;
        }

        var orderedMatcher = ORDERED_LIST.matcher(line);
        if (orderedMatcher.matches()) {
            int indentLevel = indentLevel(orderedMatcher.group(1));
            if (indentLevel == 0 && lastBlockType != BlockType.NONE && lastBlockType != BlockType.ORDERED_LIST_ITEM) {
                writeBlankLine();
            }
            writeLine(indent(indentLevel) + orderedMatcher.group(2) + ". " + sanitizeInline(orderedMatcher.group(3).trim()),
                    BlockType.ORDERED_LIST_ITEM);
            return;
        }

        var unorderedMatcher = UNORDERED_LIST.matcher(line);
        if (unorderedMatcher.matches()) {
            int indentLevel = indentLevel(unorderedMatcher.group(1));
            writeLine(indent(indentLevel) + "- " + sanitizeInline(unorderedMatcher.group(2).trim()),
                    BlockType.UNORDERED_LIST_ITEM);
            return;
        }

        if (trimmed.startsWith(">")) {
            writeLine(AnsiStyle.quotePrefix("│") + " " + sanitizeInline(trimmed.substring(1).trim()), BlockType.QUOTE);
            return;
        }

        writeLine(sanitizeInline(line), BlockType.PARAGRAPH);
    }

    private void toggleCodeBlock(String language) {
        if (!inCodeBlock) {
            ensureBlockSpacing();
            String label = language.isBlank() ? "code" : "code: " + language;
            writeLine(AnsiStyle.codeLabel("┌─ " + label), BlockType.CODE_BLOCK);
            inCodeBlock = true;
        } else {
            writeLine(AnsiStyle.codeLabel("└─ end"), BlockType.CODE_BLOCK);
            inCodeBlock = false;
            writeBlankLine();
        }
    }

    private void renderHeading(int level, String content) {
        ensureBlockSpacing();
        writeLine(AnsiStyle.heading(content), BlockType.HEADING);
        char underline = level == 1 ? '=' : '-';
        writeLine(AnsiStyle.subtle(String.valueOf(underline).repeat(Math.max(content.length(), 4))), BlockType.HEADING);
        writeBlankLine();
    }

    private void flushPendingTable() {
        if (pendingTable.isEmpty()) {
            return;
        }

        List<List<String>> rows = new ArrayList<>();
        for (String line : pendingTable) {
            if (TABLE_SEPARATOR.matcher(line).matches()) {
                continue;
            }

            List<String> cells = parseTableRow(line);
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        pendingTable.clear();

        if (rows.isEmpty()) {
            return;
        }

        ensureBlockSpacing();
        int columnCount = rows.stream().mapToInt(List::size).max().orElse(0);
        if (rows.size() >= 2 && columnCount == 2 && shouldRenderAsKeyValue(rows)) {
            renderKeyValueTable(rows);
            writeBlankLine();
            return;
        }

        int[] widths = new int[columnCount];
        for (List<String> row : rows) {
            for (int i = 0; i < columnCount; i++) {
                String cell = i < row.size() ? sanitizeInline(row.get(i)) : "";
                widths[i] = Math.max(widths[i], cell.length());
            }
        }

        String border = buildTableBorder(widths);
        writeLine(AnsiStyle.subtle(border), BlockType.TABLE);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            StringBuilder line = new StringBuilder("|");
            for (int i = 0; i < columnCount; i++) {
                String cell = i < row.size() ? sanitizeInline(row.get(i)) : "";
                line.append(" ").append(padRight(cell, widths[i])).append(" |");
            }
            String renderedLine = rowIndex == 0 ? AnsiStyle.emphasis(line.toString()) : line.toString();
            writeLine(renderedLine, BlockType.TABLE);
            if (rowIndex == 0 && rows.size() > 1) {
                writeLine(AnsiStyle.subtle(border), BlockType.TABLE);
            }
        }
        writeLine(AnsiStyle.subtle(border), BlockType.TABLE);
        writeBlankLine();
    }

    private boolean shouldRenderAsKeyValue(List<List<String>> rows) {
        int maxWidth = 0;
        int totalWidth = 0;

        for (List<String> row : rows) {
            String left = row.size() > 0 ? sanitizeInline(row.get(0)) : "";
            String right = row.size() > 1 ? sanitizeInline(row.get(1)) : "";
            maxWidth = Math.max(maxWidth, Math.max(left.length(), right.length()));
            totalWidth = Math.max(totalWidth, left.length() + right.length());
        }

        return maxWidth > COMPACT_TABLE_MAX_CELL_LENGTH || totalWidth > COMPACT_TABLE_MAX_TOTAL_WIDTH;
    }

    private void renderKeyValueTable(List<List<String>> rows) {
        List<String> header = rows.get(0);
        String leftHeader = sanitizeInline(header.get(0));
        String rightHeader = sanitizeInline(header.get(1));
        writeLine(AnsiStyle.emphasis(leftHeader + " / " + rightHeader), BlockType.TABLE);
        writeLine(AnsiStyle.subtle("-".repeat(Math.max((leftHeader + " / " + rightHeader).length(), 8))), BlockType.TABLE);

        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String left = row.size() > 0 ? sanitizeInline(row.get(0)) : "";
            String right = row.size() > 1 ? sanitizeInline(row.get(1)) : "";
            writeLine(AnsiStyle.emphasis("- " + left), BlockType.TABLE);
            if (!right.isBlank()) {
                writeLine("  " + right, BlockType.TABLE);
            }
            if (i < rows.size() - 1) {
                writeBlankLine();
            }
        }
    }

    private List<String> parseTableRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        String[] parts = trimmed.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            cells.add(part.trim());
        }
        return cells;
    }

    private String buildTableBorder(int[] widths) {
        StringBuilder border = new StringBuilder("+");
        for (int width : widths) {
            border.append("-".repeat(width + 2)).append("+");
        }
        return border.toString();
    }

    private boolean looksLikeTableLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (TABLE_SEPARATOR.matcher(trimmed).matches()) {
            return true;
        }
        return trimmed.contains("|") && trimmed.chars().filter(ch -> ch == '|').count() >= 2;
    }

    private void ensureBlockSpacing() {
        if (needsLineBreakBeforeNextBlock && !lastOutputBlank) {
            out.println();
            lastOutputBlank = true;
        }
        needsLineBreakBeforeNextBlock = false;
    }

    private void writeLine(String line) {
        writeLine(line, BlockType.PARAGRAPH);
    }

    private void writeLine(String line, BlockType blockType) {
        out.println(line);
        lastOutputBlank = line.isBlank();
        needsLineBreakBeforeNextBlock = blockType != BlockType.ORDERED_LIST_ITEM
                && blockType != BlockType.UNORDERED_LIST_ITEM
                && blockType != BlockType.QUOTE;
        if (!line.isBlank()) {
            lastBlockType = blockType;
        }
    }

    private void writeBlankLine() {
        if (!lastOutputBlank) {
            out.println();
            lastOutputBlank = true;
        }
        needsLineBreakBeforeNextBlock = false;
    }

    private String sanitizeInline(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        String sanitized = value
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replace("~~", "")
                .replace("*", "")
                .replace("_", "");

        sanitized = sanitized.replaceAll("\\[(.+?)]\\((.+?)\\)", "$1");
        return sanitized.stripTrailing();
    }

    private String padRight(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }

    private int indentLevel(String leadingWhitespace) {
        if (leadingWhitespace == null || leadingWhitespace.isEmpty()) {
            return 0;
        }
        int spaces = 0;
        for (char ch : leadingWhitespace.toCharArray()) {
            spaces += ch == '\t' ? 4 : 1;
        }
        return Math.max(0, spaces / 2);
    }

    private String indent(int level) {
        return "  ".repeat(Math.max(level, 0));
    }

    private enum BlockType {
        NONE,
        HEADING,
        PARAGRAPH,
        ORDERED_LIST_ITEM,
        UNORDERED_LIST_ITEM,
        QUOTE,
        TABLE,
        CODE_BLOCK
    }
}
