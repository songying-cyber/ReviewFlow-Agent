package com.paicli.render.inline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.util.AnsiStyle;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一组工具调用渲染成 {@link FoldableBlock}。
 *
 * <p>折叠态：{@code ⏵ 读取 3 个文件 (ctrl+o to expand)}<br>
 * 展开态：原 PlainRenderer 风格的工具标签 + 缩进的关键参数 + 折叠提示。
 *
 * <p>每次调用产生一个 block 并立即渲染折叠态，注册到 {@link BlockRegistry}。
 */
public final class ToolCallRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PrintStream out;
    private final BlockRegistry registry;

    public ToolCallRenderer(PrintStream out, BlockRegistry registry) {
        this.out = out;
        this.registry = registry;
    }

    public void render(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        Map<String, List<LlmClient.ToolCall>> grouped = group(toolCalls);
        String header = collapsedHeader(grouped);
        List<String> expanded = expandedLines(grouped);

        FoldableBlock block = new FoldableBlock(out, header, expanded);
        registry.register(block);
        block.renderInitial();
    }

    static Map<String, List<LlmClient.ToolCall>> group(List<LlmClient.ToolCall> toolCalls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }
        return grouped;
    }

    static String collapsedHeader(Map<String, List<LlmClient.ToolCall>> grouped) {
        if (grouped.size() == 1) {
            var entry = grouped.entrySet().iterator().next();
            String label = toolLabel(entry.getKey(), entry.getValue().size());
            return AnsiStyle.subtle("⏵ " + stripPrefixIcon(label) + " (ctrl+o to expand)");
        }
        int totalCalls = grouped.values().stream().mapToInt(List::size).sum();
        return AnsiStyle.subtle("⏵ " + grouped.size() + " 组工具调用 / "
                + totalCalls + " 次 (ctrl+o to expand)");
    }

    static List<String> expandedLines(Map<String, List<LlmClient.ToolCall>> grouped) {
        List<String> lines = new ArrayList<>();
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<LlmClient.ToolCall> calls = group.getValue();
            lines.add(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (LlmClient.ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    lines.add(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
        return lines;
    }

    /** 移除 emoji 前缀（折叠态视觉更紧凑），如 "📖 读取 3 个文件" → "读取 3 个文件"。 */
    private static String stripPrefixIcon(String label) {
        if (label == null || label.isEmpty()) {
            return "";
        }
        int firstSpace = label.indexOf(' ');
        if (firstSpace < 0) {
            return label;
        }
        // 仅当第一个 token 是 emoji（高 Unicode）时才剥离
        int cp = label.codePointAt(0);
        if (cp >= 0x2600 && cp <= 0x1FAFF) {
            return label.substring(firstSpace + 1);
        }
        return label;
    }

    static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "search_code" -> "🔍 搜索代码 " + count + " 次";
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            case "save_memory" -> "💾 保存长期记忆 " + count + " 条";
            default -> toolName != null && toolName.startsWith("mcp__")
                    ? formatMcpLabel(toolName, count)
                    : "🔧 " + toolName + " × " + count;
        };
    }

    private static String formatMcpLabel(String toolName, int count) {
        String[] parts = toolName.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
        return count == 1
                ? "🔌 调用 MCP 工具 " + display
                : "🔌 调用 MCP 工具 " + display + " × " + count;
    }

    static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                case "save_memory" -> "fact";
                default -> null;
            };
            if (key == null) {
                return argsJson != null && argsJson.length() > 80
                        ? argsJson.substring(0, 77) + "..." : argsJson == null ? "" : argsJson;
            }
            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            if (argsJson == null) {
                return "";
            }
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }
}
