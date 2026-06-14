package com.paicli.util;

/**
 * 终端 ANSI 样式辅助。
 */
public final class AnsiStyle {
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String ITALIC = "\u001B[3m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String GRAY = "\u001B[90m";
    private static final String PURPLE = "\u001B[38;5;141m";
    private static final String BG_PANEL = "\u001B[48;5;236m";
    private static final boolean ENABLED = determineEnabled();

    private AnsiStyle() {
    }

    public static String heading(String text) {
        return wrap(BOLD + CYAN, text);
    }

    public static String section(String text) {
        return wrap(BOLD + GREEN, text);
    }

    public static String answerMarker() {
        return wrap(BOLD + GREEN, "▪");
    }

    public static String subtle(String text) {
        return wrap(DIM + GRAY, text);
    }

    public static String thinking(String text) {
        return wrap(ITALIC + GRAY, text);
    }

    public static String userMessageBlock(String text, int columns) {
        String safe = text == null ? "" : text.strip();
        String content = "  >  " + safe;
        int width = Math.max(20, columns);
        int padding = Math.max(1, width - displayWidth(content));
        String line = content + " ".repeat(padding);
        if (!ENABLED) {
            return line.stripTrailing();
        }
        return BG_PANEL + PURPLE + "  >  " + RESET + BG_PANEL + safe + " ".repeat(padding) + RESET;
    }

    public static String codeLabel(String text) {
        return wrap(BOLD + YELLOW, text);
    }

    public static String error(String text) {
        return wrap(BOLD + RED, text);
    }

    public static String quotePrefix(String text) {
        return wrap(DIM + CYAN, text);
    }

    public static String emphasis(String text) {
        return wrap(BOLD, text);
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    private static String wrap(String prefix, String text) {
        if (!ENABLED || text == null || text.isEmpty()) {
            return text;
        }
        return prefix + text + RESET;
    }

    private static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
            width += block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                    || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                    || block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA ? 2 : 1;
        }
        return width;
    }

    private static boolean determineEnabled() {
        String property = System.getProperty("paicli.render.color");
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property);
        }

        if (System.getenv("NO_COLOR") != null) {
            return false;
        }

        String term = System.getenv("TERM");
        return term == null || !term.equalsIgnoreCase("dumb");
    }
}
