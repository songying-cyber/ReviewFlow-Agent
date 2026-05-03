package com.paicli.hitl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 审批请求 - 描述一次待确认的工具调用
 *
 * 包含工具调用的完整信息，用于向用户展示"即将执行什么操作"。
 */
public record ApprovalRequest(
        String toolName,
        String arguments,
        String dangerLevel,
        String riskDescription,
        String suggestion,
        String callerContext,
        String sensitiveNotice
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BOX_INNER_WIDTH = 58;
    private static final int FIELD_WIDTH = BOX_INNER_WIDTH - 8;  // 为"│  xxx: "留出
    private static final int ARG_LINE_WIDTH = BOX_INNER_WIDTH - 6;
    private static final int MAX_LONG_VALUE_PREVIEW = 120;

    public static ApprovalRequest of(String toolName, String arguments, String suggestion) {
        return of(toolName, arguments, suggestion, null);
    }

    public static ApprovalRequest of(String toolName, String arguments, String suggestion, String callerContext) {
        return of(toolName, arguments, suggestion, callerContext, null);
    }

    public static ApprovalRequest of(String toolName, String arguments, String suggestion, String callerContext,
                                     String sensitiveNotice) {
        return new ApprovalRequest(
                toolName,
                arguments,
                ApprovalPolicy.getDangerLevel(toolName),
                ApprovalPolicy.getRiskDescription(toolName),
                suggestion,
                callerContext,
                sensitiveNotice
        );
    }

    /**
     * 格式化为可读的展示文本
     *
     * 所有 padding 都按"终端显示列宽"计算（CJK / emoji 占 2 列），不再按字符数 pad，
     * 避免中文和 emoji 把右边框挤歪。
     */
    public String toDisplayText() {
        StringBuilder sb = new StringBuilder();
        String border = "─".repeat(BOX_INNER_WIDTH);
        sb.append("┌").append(border).append("┐\n");
        sb.append(formatBoxLine("⚠️  需要审批")).append("\n");
        sb.append("├").append(border).append("┤\n");
        sb.append(formatBoxField("工具", toolName)).append("\n");
        String mcpServer = ApprovalPolicy.mcpServerName(toolName);
        if (mcpServer != null && !mcpServer.isBlank()) {
            sb.append(formatBoxField("MCP server", mcpServer)).append("\n");
        }
        sb.append(formatBoxField("等级", dangerLevel)).append("\n");
        sb.append(formatBoxField("风险", riskDescription)).append("\n");
        if (callerContext != null && !callerContext.isBlank()) {
            sb.append(formatBoxField("来源", callerContext)).append("\n");
        }
        if (sensitiveNotice != null && !sensitiveNotice.isBlank()) {
            sb.append(formatBoxField("敏感页面", sensitiveNotice)).append("\n");
        }
        sb.append("├").append(border).append("┤\n");
        sb.append(formatBoxLine("参数:")).append("\n");
        for (String line : formatArgs(arguments)) {
            sb.append(formatBoxIndented(line)).append("\n");
        }
        if (suggestion != null && !suggestion.isBlank()) {
            sb.append("├").append(border).append("┤\n");
            sb.append(formatBoxLine("执行理由:")).append("\n");
            for (String line : wrapByDisplayWidth(suggestion, ARG_LINE_WIDTH)) {
                sb.append(formatBoxIndented(line)).append("\n");
            }
        }
        sb.append("└").append(border).append("┘");
        return sb.toString();
    }

    /**
     * 构造一行："│  {prefix}: {value 按显示宽度 pad}│"
     */
    private String formatBoxField(String prefix, String value) {
        String label = prefix + ": ";
        String safeValue = value == null ? "" : value;
        int used = displayWidth(label) + 2;  // "│  " 占 2 显示列
        int target = BOX_INNER_WIDTH - used;
        String truncated = truncateByDisplayWidth(safeValue, target);
        String padded = padRightByDisplayWidth(truncated, target);
        return "│  " + label + padded + "│";
    }

    /**
     * 构造一行："│  {text}{尾部 pad}│"
     */
    private String formatBoxLine(String text) {
        String safe = text == null ? "" : text;
        int target = BOX_INNER_WIDTH - 2;  // "│  " 前后各占
        String truncated = truncateByDisplayWidth(safe, target);
        String padded = padRightByDisplayWidth(truncated, target);
        return "│  " + padded + "│";
    }

    /**
     * 构造缩进行："│    {text}{尾部 pad}│"（用于参数项 / 执行理由明细）
     */
    private String formatBoxIndented(String text) {
        String safe = text == null ? "" : text;
        int target = BOX_INNER_WIDTH - 4;  // "│    " 前占 4 列
        String truncated = truncateByDisplayWidth(safe, target);
        String padded = padRightByDisplayWidth(truncated, target);
        return "│    " + padded + "│";
    }

    /**
     * JSON-aware 的参数展示：解析为对象逐字段展示 key: value_preview，
     * 对长字符串（通常是 write_file 的 content）展示前若干字符 + 总长度摘要。
     * 解析失败时退回到原始字符串按显示宽度换行。
     */
    private List<String> formatArgs(String args) {
        List<String> lines = new ArrayList<>();
        if (args == null || args.isBlank()) {
            lines.add("(无参数)");
            return lines;
        }
        try {
            JsonNode root = MAPPER.readTree(args);
            if (root.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = root.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    String key = entry.getKey();
                    JsonNode valNode = entry.getValue();
                    if (valNode.isTextual()) {
                        String v = valNode.asText();
                        if (v.length() > MAX_LONG_VALUE_PREVIEW) {
                            String head = v.substring(0, MAX_LONG_VALUE_PREVIEW)
                                    .replace("\n", "⏎");
                            lines.addAll(wrapByDisplayWidth(
                                    key + ": \"" + head + "...\" (" + v.length() + " 字符)",
                                    ARG_LINE_WIDTH));
                        } else {
                            String v1 = v.replace("\n", "⏎");
                            lines.addAll(wrapByDisplayWidth(key + ": \"" + v1 + "\"", ARG_LINE_WIDTH));
                        }
                    } else {
                        lines.addAll(wrapByDisplayWidth(key + ": " + valNode.toString(), ARG_LINE_WIDTH));
                    }
                }
                if (lines.isEmpty()) {
                    lines.add("(空对象)");
                }
                return lines;
            }
        } catch (Exception ignored) {
            // 非法 JSON，退回到原样展示
        }
        return wrapByDisplayWidth(args.trim(), ARG_LINE_WIDTH);
    }

    /**
     * 计算字符串的终端显示宽度（CJK / 全角 / 常见 emoji 占 2 列，其他占 1 列）。
     */
    static int displayWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp == 0x7F) {
                continue;  // 控制字符不占列
            }
            if (isWideCodePoint(cp)) {
                w += 2;
            } else {
                w += 1;
            }
        }
        return w;
    }

    private static boolean isWideCodePoint(int cp) {
        // 覆盖主流 CJK、韩文、全角、常见 emoji / 符号范围
        return (cp >= 0x1100 && cp <= 0x115F)      // Hangul Jamo
                || (cp >= 0x2E80 && cp <= 0x9FFF)  // CJK Radicals/统一
                || (cp >= 0xA000 && cp <= 0xA4CF)  // Yi syllables
                || (cp >= 0xAC00 && cp <= 0xD7A3)  // Hangul syllables
                || (cp >= 0xF900 && cp <= 0xFAFF)  // CJK 兼容
                || (cp >= 0xFE30 && cp <= 0xFE4F)  // CJK 兼容符号
                || (cp >= 0xFF00 && cp <= 0xFF60)  // 全角
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x2600 && cp <= 0x27BF)  // 杂项符号 + dingbats（含部分 emoji）
                || (cp >= 0x1F300 && cp <= 0x1FAFF); // Emoji 主体区间
    }

    /**
     * 按显示宽度 pad 右侧空格到目标列宽；已超出则直接返回。
     */
    static String padRightByDisplayWidth(String s, int targetCols) {
        int w = displayWidth(s);
        if (w >= targetCols) {
            return s;
        }
        return s + " ".repeat(targetCols - w);
    }

    /**
     * 按显示宽度截断，如超出目标列宽则末尾加 "..."。
     */
    static String truncateByDisplayWidth(String s, int targetCols) {
        if (s == null) return "";
        if (displayWidth(s) <= targetCols) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int reserve = 3;  // "..." 占 3 列
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int cpWidth = isWideCodePoint(cp) ? 2 : 1;
            if (used + cpWidth > targetCols - reserve) {
                break;
            }
            sb.appendCodePoint(cp);
            used += cpWidth;
            i += Character.charCount(cp);
        }
        sb.append("...");
        return sb.toString();
    }

    /**
     * 按显示宽度将一段文本拆成多行，每行不超过 lineWidth 显示列。
     */
    static List<String> wrapByDisplayWidth(String text, int lineWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            lines.add("");
            return lines;
        }
        StringBuilder current = new StringBuilder();
        int used = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int cpWidth = isWideCodePoint(cp) ? 2 : 1;
            if (used + cpWidth > lineWidth) {
                lines.add(current.toString());
                current.setLength(0);
                used = 0;
            }
            current.appendCodePoint(cp);
            used += cpWidth;
            i += Character.charCount(cp);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }
}
