package com.paicli.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.hitl.ApprovalPolicy;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.util.AnsiStyle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain 渲染器：纯 println 模式，等价 phase-15 行为，无折叠、无状态栏。
 *
 * <p>同时充当 inline / lanterna 两套实现的回退基线——任何高级特性都退化成普通文本。
 */
public final class PlainRenderer implements Renderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PrintStream out;
    private final BufferedReader in;

    public PlainRenderer() {
        this(System.out, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
    }

    PlainRenderer(PrintStream out, BufferedReader in) {
        this.out = out;
        this.in = in;
    }

    @Override
    public void start() {
        // no-op
    }

    @Override
    public void close() {
        // no-op：不接管 System.out / System.in，启动者自己管生命周期
    }

    @Override
    public PrintStream stream() {
        return out;
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<LlmClient.ToolCall> calls = group.getValue();
            out.println(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (LlmClient.ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    out.println(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        out.println();
        out.println(AnsiStyle.heading("📝 " + (filePath == null ? "(unnamed)" : filePath)));
        if (before == null && after != null) {
            out.println(AnsiStyle.subtle("  (新建文件，" + after.length() + " 字符)"));
            return;
        }
        if (before != null && after == null) {
            out.println(AnsiStyle.subtle("  (删除文件)"));
            return;
        }
        // Day 4 才做行内 diff；plain 模式只打印长度变化提示。
        int beforeLen = before == null ? 0 : before.length();
        int afterLen = after == null ? 0 : after.length();
        out.println(AnsiStyle.subtle("  " + beforeLen + " → " + afterLen + " 字符"));
    }

    @Override
    public void updateStatus(StatusInfo status) {
        // plain 模式没有状态栏
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        boolean sensitivePerCall = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();
        out.println();
        out.println("────────── ⚠️  HITL 审批请求 ──────────");
        if (sensitivePerCall) {
            out.println("⚠️  " + request.sensitiveNotice());
        }
        out.println(request.toDisplayText());

        for (int attempt = 0; attempt < 5; attempt++) {
            out.println();
            if (sensitivePerCall) {
                out.println("请选择操作：[y/Enter] 批准本次  [n] 拒绝  [s] 跳过  [m] 修改参数");
            } else {
                out.println("请选择操作：[y/Enter] 批准  [a] 全部放行  [n] 拒绝  [s] 跳过  [m] 修改参数");
            }
            out.print("> ");
            out.flush();

            String input;
            try {
                input = in.readLine();
            } catch (IOException e) {
                out.println("  [HITL] 读取用户输入失败，保守处理为拒绝");
                return ApprovalResult.reject("读取输入失败: " + e.getMessage());
            }
            if (input == null) {
                out.println("  [HITL] 输入流已关闭，保守处理为拒绝");
                return ApprovalResult.reject("输入流已关闭");
            }

            String normalized = input.trim().toLowerCase();
            if (normalized.isEmpty() || normalized.equals("y")) {
                out.println("  已批准");
                return ApprovalResult.approve();
            }
            switch (normalized) {
                case "a" -> {
                    if (sensitivePerCall) {
                        out.println("  敏感页面操作不支持全部放行，请选择 y/n/s/m");
                        continue;
                    }
                    return promptApproveAllScope(request);
                }
                case "n" -> {
                    out.print("  拒绝原因（可直接回车跳过）：");
                    out.flush();
                    String reason;
                    try {
                        reason = in.readLine();
                    } catch (IOException e) {
                        reason = "";
                    }
                    return ApprovalResult.reject(reason == null ? "" : reason.trim());
                }
                case "s" -> {
                    out.println("  已跳过本次操作");
                    return ApprovalResult.skip();
                }
                case "m" -> {
                    ApprovalResult modified = promptModifiedArguments(request);
                    if (modified != null) {
                        return modified;
                    }
                }
                default -> out.println("  ❓ 无法识别的选项：'" + input + "'，请输入 y/a/n/s/m 之一（Enter 等价于 y）");
            }
        }
        out.println("  [HITL] 连续多次无效输入，保守处理为拒绝");
        return ApprovalResult.reject("连续多次无效输入");
    }

    @Override
    public int openPalette(String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return -1;
        }
        out.println();
        out.println(AnsiStyle.heading("📋 " + (title == null ? "请选择" : title)));
        for (int i = 0; i < items.size(); i++) {
            out.printf("  [%d] %s%n", i + 1, items.get(i));
        }
        out.print("> ");
        out.flush();
        try {
            String line = in.readLine();
            if (line == null || line.isBlank()) {
                return -1;
            }
            int idx = Integer.parseInt(line.trim()) - 1;
            return (idx >= 0 && idx < items.size()) ? idx : -1;
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    private ApprovalResult promptApproveAllScope(ApprovalRequest request) {
        String mcpServer = ApprovalPolicy.mcpServerName(request.toolName());
        if (mcpServer == null || mcpServer.isBlank()) {
            out.println("  已批准，后续 " + request.toolName() + " 操作将自动通过");
            return ApprovalResult.approveAll();
        }

        out.println("  全部放行范围：");
        out.println("  [tool / Enter] 仅本工具 " + request.toolName());
        out.println("  [server]       整个 MCP server " + mcpServer + "（连续浏览器操作推荐）");
        out.print("> ");
        out.flush();
        String scope;
        try {
            scope = in.readLine();
        } catch (IOException e) {
            out.println("  读取范围失败，默认按工具维度放行");
            scope = "";
        }
        String normalized = scope == null ? "" : scope.trim().toLowerCase();
        if ("server".equals(normalized) || "s".equals(normalized)) {
            out.println("  已批准，后续 MCP server " + mcpServer + " 的工具调用将自动通过");
            return ApprovalResult.approveAllByServer();
        }
        out.println("  已批准，后续 " + request.toolName() + " 操作将自动通过");
        return ApprovalResult.approveAll();
    }

    private ApprovalResult promptModifiedArguments(ApprovalRequest request) {
        out.println("  当前参数：" + request.arguments());
        out.print("  请输入修改后的参数（JSON 格式，空行则使用原始参数）：");
        out.flush();

        String modified;
        try {
            modified = in.readLine();
        } catch (IOException e) {
            out.println("  读取失败，回到主菜单");
            return null;
        }
        if (modified == null || modified.isBlank()) {
            out.println("  输入为空，改为批准原始参数");
            return ApprovalResult.approve();
        }

        String trimmed = modified.trim();
        try {
            JSON.readTree(trimmed);
        } catch (Exception e) {
            out.println("  ❌ 修改后的参数不是合法 JSON：" + e.getMessage());
            return null;
        }
        return ApprovalResult.modify(trimmed);
    }

    // ---- 工具标签格式化（与 Agent.printToolCalls 保持一致） ----

    private static String toolLabel(String toolName, int count) {
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

    private static String extractKeyParam(String toolName, String argsJson) {
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
                return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
            }
            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }
}
