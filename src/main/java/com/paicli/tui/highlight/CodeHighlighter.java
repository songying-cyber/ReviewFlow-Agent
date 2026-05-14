package com.paicli.tui.highlight;

import com.paicli.util.AnsiStyle;

/**
 * 代码高亮器（基于正则表达式的轻量级实现）。
 *
 * <p>支持：
 * - Java / Kotlin / Python / JavaScript / TypeScript / Go / Rust
 * - 关键字、字符串、注释、数字高亮
 * - 零第三方依赖，复刻 CodeChunker 的分块策略
 */
public final class CodeHighlighter {

    // 语言关键字（大小写不敏感）
    private static final String[] JAVA_KEYWORDS = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "if", "implements", "import",
            "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try",
            "void", "volatile", "while", "var", "record", "sealed", "permits", "yield"
    };

    private static final String[] PYTHON_KEYWORDS = {
            "and", "as", "assert", "async", "await", "break", "class", "continue",
            "def", "del", "elif", "else", "except", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "nonlocal", "not",
            "or", "pass", "raise", "return", "try", "while", "with", "yield",
            "True", "False", "None", "match", "case"
    };

    private static final String[] JS_KEYWORDS = {
            "break", "case", "catch", "class", "const", "continue", "debugger", "default",
            "delete", "do", "else", "export", "extends", "false", "finally", "for",
            "function", "if", "implements", "import", "in", "instanceof", "interface",
            "let", "new", "null", "package", "private", "protected", "public",
            "return", "static", "super", "switch", "this", "throw", "true", "try",
            "typeof", "var", "void", "while", "with", "async", "await", "yield",
            "of", "from", "as", "undefined"
    };

    private static final String[] GO_KEYWORDS = {
            "break", "case", "chan", "const", "continue", "default", "defer", "else",
            "fallthrough", "for", "func", "go", "goto", "if", "import", "interface",
            "map", "package", "range", "return", "select", "struct", "switch", "type",
            "var", "nil", "true", "false", "iota", "int", "string", "error"
    };

    private static final String[] RUST_KEYWORDS = {
            "as", "break", "const", "continue", "crate", "dyn", "else", "enum",
            "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop",
            "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self",
            "static", "struct", "super", "trait", "true", "type", "unsafe", "use",
            "where", "while", "async", "await", "yield"
    };

    private static final String[] KOTLIN_KEYWORDS = {
            "as", "as?", "break", "class", "continue", "do", "else", "false",
            "for", "fun", "if", "in", "!in", "interface", "is", "!is", "null",
            "object", "package", "return", "super", "this", "throw", "true",
            "try", "typealias", "typeof", "val", "var", "when", "while", "by",
            "catch", "constructor", "delegate", "dynamic", "field", "file",
            "finally", "get", "import", "init", "param", "property", "receiver",
            "set", "setparam", "where", "actual", "abstract", "annotation",
            "companion", "const", "crossinline", "data", "enum", "expect",
            "external", "final", "infix", "inline", "inner", "internal",
            "lateinit", "noinline", "open", "operator", "out", "override",
            "private", "protected", "public", "reified", "sealed", "suspend",
            "tailrec", "vararg", "vararg", "field", "it"
    };

    private CodeHighlighter() {
    }

    /**
     * 高亮代码块。
     *
     * @param code      代码文本
     * @param language  语言标识（如 "java", "python", "javascript"）
     * @return 带 ANSI 颜色的高亮文本
     */
    public static String highlight(String code, String language) {
        if (code == null || code.isEmpty()) {
            return code;
        }

        String lang = (language != null ? language.toLowerCase() : "text");
        String[] keywords = getKeywords(lang);

        StringBuilder result = new StringBuilder();
        String[] lines = code.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) {
                result.append("\n");
            }
            result.append(highlightLine(line, keywords, lang));
        }

        return result.toString();
    }

    /**
     * 高亮单行代码。
     */
    private static String highlightLine(String line, String[] keywords, String lang) {
        // 注释优先（// 或 #）
        if (line.trim().startsWith("//") || line.trim().startsWith("#") || line.trim().startsWith("/*")) {
            return AnsiStyle.subtle(line);
        }

        // 字符串字面量（简单双引号匹配）
        if (line.contains("\"")) {
            return highlightWithStrings(line, keywords, lang);
        }

        // 无字符串，直接高亮关键字和数字
        return highlightTokens(line, keywords, lang);
    }

    /**
     * 处理带字符串的行（保护字符串内容不被拆分）。
     */
    private static String highlightWithStrings(String line, String[] keywords, String lang) {
        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < line.length()) {
            char c = line.charAt(i);

            // 字符串开始
            if (c == '"') {
                int start = i;
                i++;
                while (i < line.length() && line.charAt(i) != '"') {
                    if (line.charAt(i) == '\\' && i + 1 < line.length()) {
                        i += 2;  // 跳过转义字符
                    } else {
                        i++;
                    }
                }
                if (i < line.length()) {
                    i++;  // 包含结束引号
                }
                result.append(line, start, i);
            } else {
                // 非字符串部分，高亮关键字
                int tokenStart = i;
                while (i < line.length() && line.charAt(i) != '"'
                        && !Character.isWhitespace(line.charAt(i))
                        && !isOperator(line.charAt(i))) {
                    i++;
                }
                if (i > tokenStart) {
                    String token = line.substring(tokenStart, i);
                    if (isKeyword(token, keywords) || isNumber(token)) {
                        result.append(AnsiStyle.codeLabel(token));
                    } else {
                        result.append(token);
                    }
                }
                // 空白/运算符直接追加
                while (i < line.length() && (Character.isWhitespace(line.charAt(i)) || isOperator(line.charAt(i)))) {
                    result.append(line.charAt(i));
                    i++;
                }
            }
        }

        return result.toString();
    }

    /**
     * 高亮 token 序列。
     */
    private static String highlightTokens(String line, String[] keywords, String lang) {
        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < line.length()) {
            char c = line.charAt(i);

            // 空白直接追加
            if (Character.isWhitespace(c)) {
                result.append(c);
                i++;
                continue;
            }

            // 运算符直接追加
            if (isOperator(c)) {
                result.append(c);
                i++;
                continue;
            }

            // 提取 token
            int tokenStart = i;
            while (i < line.length()
                    && !Character.isWhitespace(line.charAt(i))
                    && !isOperator(line.charAt(i))) {
                i++;
            }
            String token = line.substring(tokenStart, i);

            // 关键字高亮
            if (isKeyword(token, keywords)) {
                result.append(AnsiStyle.codeLabel(token));
            } else if (isNumber(token)) {
                result.append(AnsiStyle.codeLabel(token));
            } else {
                result.append(token);
            }
        }

        return result.toString();
    }

    /**
     * 检查是否为运算符。
     */
    private static boolean isOperator(char c) {
        return "(){}[]+-*/%=!<>&|:;,.".indexOf(c) >= 0;
    }

    /**
     * 检查是否为关键字。
     */
    private static boolean isKeyword(String token, String[] keywords) {
        for (String kw : keywords) {
            if (kw.equals(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否为数字。
     */
    private static boolean isNumber(String token) {
        return token.matches("^-?\\d+(\\.\\d+)?[fFlLdD]?$");
    }

    /**
     * 根据语言获取关键字数组。
     */
    private static String[] getKeywords(String lang) {
        return switch (lang) {
            case "java" -> JAVA_KEYWORDS;
            case "kotlin" -> KOTLIN_KEYWORDS;
            case "python", "py" -> PYTHON_KEYWORDS;
            case "javascript", "js", "typescript", "ts" -> JS_KEYWORDS;
            case "go" -> GO_KEYWORDS;
            case "rust", "rs" -> RUST_KEYWORDS;
            default -> JAVA_KEYWORDS;  // 默认 Java
        };
    }
}
