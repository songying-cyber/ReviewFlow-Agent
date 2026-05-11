package com.paicli.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKILL.md frontmatter 解析器（极简 YAML 子集，不引入 SnakeYAML）。
 *
 * 支持的语法（覆盖 95% 实际写法）：
 * - 单行 key: value
 * - 多行 key: |\n  line1\n  line2（以首行缩进推断）
 * - 行内数组 key: [a, b, c]
 *
 * 不支持（命中即 warnings 报错并跳过该字段，不阻塞整个 skill 加载）：
 * - 嵌套对象 key: { nested: ... }
 * - YAML anchor / alias / merge key
 * - 复杂 YAML 类型（!!str 等）
 *
 * 解析失败的 skill 会在 SkillRegistry 层被丢弃，warnings 打印到 stderr。
 */
public final class SkillFrontmatterParser {

    public record ParseResult(Map<String, Object> frontmatter, String body, List<String> warnings) {
    }

    private SkillFrontmatterParser() {
    }

    public static ParseResult parse(String fullText) {
        if (fullText == null) {
            return new ParseResult(Map.of(), "", List.of("SKILL.md 内容为 null"));
        }
        String normalized = fullText.replace("\r\n", "\n").replace("\r", "\n");
        if (!normalized.startsWith("---\n")) {
            return new ParseResult(Map.of(), normalized,
                    List.of("缺少 frontmatter 起始标记 ---"));
        }

        int endIdx = findFrontmatterEnd(normalized);
        if (endIdx < 0) {
            return new ParseResult(Map.of(), normalized,
                    List.of("缺少 frontmatter 结束标记 ---"));
        }

        String frontmatterText = normalized.substring(4, endIdx);
        String body = normalized.substring(endIdx + 4);
        if (body.startsWith("\n")) {
            body = body.substring(1);
        }

        List<String> warnings = new ArrayList<>();
        Map<String, Object> frontmatter = parseFrontmatter(frontmatterText, warnings);
        return new ParseResult(frontmatter, body, warnings);
    }

    private static int findFrontmatterEnd(String text) {
        int idx = 4;
        while (idx < text.length()) {
            int lineEnd = text.indexOf('\n', idx);
            if (lineEnd < 0) {
                return -1;
            }
            String line = text.substring(idx, lineEnd);
            if (line.equals("---")) {
                return idx;
            }
            idx = lineEnd + 1;
        }
        return -1;
    }

    private static Map<String, Object> parseFrontmatter(String text, List<String> warnings) {
        Map<String, Object> result = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank() || line.trim().startsWith("#")) {
                i++;
                continue;
            }
            int colonIdx = findKeyColonIndex(line);
            if (colonIdx < 0) {
                warnings.add("无法解析的 frontmatter 行: " + line);
                i++;
                continue;
            }
            String key = line.substring(0, colonIdx).trim();
            String rawValue = line.substring(colonIdx + 1).trim();

            if (key.isEmpty()) {
                warnings.add("frontmatter 行缺少 key: " + line);
                i++;
                continue;
            }

            if (rawValue.isEmpty()) {
                warnings.add("frontmatter 字段 '" + key + "' 缺少值或使用了不支持的嵌套结构");
                i++;
                continue;
            }

            if (rawValue.startsWith("{")) {
                warnings.add("frontmatter 字段 '" + key + "' 使用了不支持的嵌套对象语法");
                i++;
                continue;
            }

            if (rawValue.equals("|") || rawValue.startsWith("|")) {
                StringBuilder sb = new StringBuilder();
                i++;
                Integer baseIndent = null;
                while (i < lines.length) {
                    String next = lines[i];
                    if (next.isBlank()) {
                        sb.append('\n');
                        i++;
                        continue;
                    }
                    int indent = leadingSpaces(next);
                    if (indent == 0) {
                        break;
                    }
                    if (baseIndent == null) {
                        baseIndent = indent;
                    }
                    if (indent < baseIndent) {
                        break;
                    }
                    sb.append(next.substring(baseIndent)).append('\n');
                    i++;
                }
                String value = sb.toString().replaceAll("\\s+", " ").trim();
                result.put(key, value);
                continue;
            }

            if (rawValue.startsWith("[") && rawValue.endsWith("]")) {
                String inner = rawValue.substring(1, rawValue.length() - 1).trim();
                List<String> items = new ArrayList<>();
                if (!inner.isEmpty()) {
                    for (String part : inner.split(",")) {
                        String trimmed = part.trim();
                        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
                            trimmed = trimmed.substring(1, trimmed.length() - 1);
                        } else if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) {
                            trimmed = trimmed.substring(1, trimmed.length() - 1);
                        }
                        if (!trimmed.isEmpty()) {
                            items.add(trimmed);
                        }
                    }
                }
                result.put(key, items);
                i++;
                continue;
            }

            String value = rawValue;
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            } else if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            result.put(key, value);
            i++;
        }
        return result;
    }

    private static int findKeyColonIndex(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == ':' && !inSingle && !inDouble) {
                return i;
            }
        }
        return -1;
    }

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }
}
