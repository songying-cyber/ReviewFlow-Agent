package com.paicli.cli;

import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JLine 输入高亮器。
 *
 * <p>只处理用户正在编辑的输入行，不改变最终提交内容。目标是让 slash 命令、
 * @ 引用、图片输入、危险命令和敏感字段在输入阶段就有明确视觉反馈。
 */
final class PaiCliHighlighter implements Highlighter {
    private static final AttributedStyle COMMAND_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.CYAN)
            .bold();
    private static final AttributedStyle MENTION_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BLUE)
            .underline();
    private static final AttributedStyle IMAGE_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.MAGENTA)
            .bold();
    private static final AttributedStyle DANGER_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.RED)
            .bold()
            .underline();
    private static final AttributedStyle SECRET_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.YELLOW)
            .bold();
    private static final AttributedStyle UNCLOSED_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.YELLOW)
            .underline();

    private static final Pattern SLASH_COMMAND = Pattern.compile("^/\\S*");
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("@image:(<[^>]*>|\\S*)|@clipboard(?![\\p{L}\\p{N}_])");
    private static final Pattern AT_REFERENCE = Pattern.compile("@[\\p{L}\\p{N}_./:~${}<>-]+");
    private static final Pattern DANGEROUS = Pattern.compile(
            "(?i)\\b(sudo|mkfs|shutdown|reboot|halt|poweroff)\\b"
                    + "|\\brm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+(/|~|\\$home)"
                    + "|\\bcurl\\b[^|\\n]*\\|\\s*(sh|bash|zsh|fish|ksh)\\b"
                    + "|\\bwget\\b[^|\\n]*\\|\\s*(sh|bash|zsh|fish|ksh)\\b"
                    + "|\\bdd\\b[^\\n]*\\bof=/dev/"
    );
    private static final Pattern SECRET_HINT = Pattern.compile(
            "(?i)\\b(api[_-]?key|token|password|secret|authorization|bearer)\\b");

    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        String text = buffer == null ? "" : buffer;
        if (text.isEmpty()) {
            return AttributedString.EMPTY;
        }
        AttributedStyle[] styles = new AttributedStyle[text.length()];
        apply(styles, text, AT_REFERENCE, MENTION_STYLE);
        apply(styles, text, IMAGE_REFERENCE, IMAGE_STYLE);
        apply(styles, text, SECRET_HINT, SECRET_STYLE);
        apply(styles, text, DANGEROUS, DANGER_STYLE);
        apply(styles, text, SLASH_COMMAND, COMMAND_STYLE);
        markUnclosedDelimiter(styles, text);

        AttributedStringBuilder builder = new AttributedStringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            builder.append(String.valueOf(text.charAt(i)),
                    styles[i] == null ? AttributedStyle.DEFAULT : styles[i]);
        }
        return builder.toAttributedString();
    }

    private static void apply(AttributedStyle[] styles, String text, Pattern pattern, AttributedStyle style) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int start = Math.max(0, matcher.start());
            int end = Math.min(styles.length, matcher.end());
            for (int i = start; i < end; i++) {
                styles[i] = style;
            }
        }
    }

    private static void markUnclosedDelimiter(AttributedStyle[] styles, String text) {
        int singleQuote = lastUnclosed(text, '\'');
        int doubleQuote = lastUnclosed(text, '"');
        int imageAngle = unclosedImageAngle(text);
        int idx = Math.max(imageAngle, Math.max(singleQuote, doubleQuote));
        if (idx >= 0 && idx < styles.length) {
            styles[idx] = UNCLOSED_STYLE;
        }
    }

    private static int lastUnclosed(String text, char quote) {
        boolean open = false;
        int last = -1;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == quote) {
                open = !open;
                last = open ? i : -1;
            }
        }
        return open ? last : -1;
    }

    private static int unclosedImageAngle(String text) {
        int start = text.indexOf("@image:<");
        if (start < 0) {
            return -1;
        }
        int angle = start + "@image:".length();
        int close = text.indexOf('>', angle + 1);
        return close < 0 ? angle : -1;
    }
}
