package com.paicli.render.inline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.hitl.ApprovalPolicy;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.util.AnsiStyle;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Inline 形态的 HITL 审批提示。
 *
 * <p>主菜单选项 {@code y / a / n / s / m} 通过 raw mode 单字符读取（不需要回车），
 * 后续输入（拒绝原因、新参数 JSON）回退到 {@code BufferedReader.readLine}。
 *
 * <p>有意保持和 {@link com.paicli.render.PlainRenderer#promptApproval} 一致的语义；
 * 只是首选项交互更紧凑。
 */
public final class InlineApprovalPrompter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ATTEMPTS = 5;

    private final PrintStream out;
    private final Terminal terminal;
    private final BufferedReader stdinReader;

    public InlineApprovalPrompter(PrintStream out, Terminal terminal) {
        this(out, terminal, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
    }

    InlineApprovalPrompter(PrintStream out, Terminal terminal, BufferedReader stdinReader) {
        this.out = out;
        this.terminal = terminal;
        this.stdinReader = stdinReader;
    }

    public ApprovalResult prompt(ApprovalRequest request) {
        boolean sensitive = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();
        out.println();
        out.println(AnsiStyle.heading("⚠️  HITL 审批"));
        if (sensitive) {
            out.println("  " + request.sensitiveNotice());
        }
        out.println(request.toDisplayText());

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String optionsLine = sensitive
                    ? AnsiStyle.subtle("[y] approve  [n] reject  [s] skip  [m] modify")
                    : AnsiStyle.subtle("[y] approve  [a] all  [n] reject  [s] skip  [m] modify");
            out.print("> " + optionsLine + " ");
            out.flush();

            int key = readSingleKey();
            if (key < 0) {
                out.println();
                return ApprovalResult.reject("无法读取按键");
            }
            char ch = Character.toLowerCase((char) key);
            // echo + newline so后续输出不和提示行挤在一起
            out.println(ch);
            out.flush();

            switch (ch) {
                case 'y', '\n', '\r' -> {
                    return ApprovalResult.approve();
                }
                case 'a' -> {
                    if (sensitive) {
                        out.println(AnsiStyle.subtle("  敏感操作不支持全部放行，请选 y/n/s/m"));
                        continue;
                    }
                    return promptApproveAllScope(request);
                }
                case 'n' -> {
                    return ApprovalResult.reject(promptForReason());
                }
                case 's' -> {
                    return ApprovalResult.skip();
                }
                case 'm' -> {
                    ApprovalResult modified = promptForModifiedArgs(request);
                    if (modified != null) {
                        return modified;
                    }
                }
                default -> out.println(AnsiStyle.subtle("  ❓ 未识别按键 '" + ch + "'，重新选择"));
            }
        }
        out.println(AnsiStyle.subtle("  连续多次无效输入，保守处理为拒绝"));
        return ApprovalResult.reject("连续多次无效输入");
    }

    private int readSingleKey() {
        Attributes original;
        try {
            original = terminal.enterRawMode();
        } catch (Exception e) {
            return -1;
        }
        try {
            terminal.flush();
            return terminal.reader().read();
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                terminal.setAttributes(original);
            } catch (Exception ignored) {
            }
        }
    }

    private String promptForReason() {
        out.print("  拒绝原因（可直接回车跳过）: ");
        out.flush();
        try {
            String line = stdinReader.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException e) {
            return "";
        }
    }

    private ApprovalResult promptApproveAllScope(ApprovalRequest request) {
        String mcpServer = ApprovalPolicy.mcpServerName(request.toolName());
        if (mcpServer == null || mcpServer.isBlank()) {
            out.println(AnsiStyle.subtle("  已批准，后续 " + request.toolName() + " 自动通过"));
            return ApprovalResult.approveAll();
        }
        out.println("  全部放行范围 [tool/Enter] 仅本工具  [server] 整个 MCP server " + mcpServer);
        out.print("> ");
        out.flush();
        String scope;
        try {
            scope = stdinReader.readLine();
        } catch (IOException e) {
            scope = "";
        }
        String n = scope == null ? "" : scope.trim().toLowerCase();
        if ("server".equals(n) || "s".equals(n)) {
            out.println(AnsiStyle.subtle("  已批准 server 范围"));
            return ApprovalResult.approveAllByServer();
        }
        out.println(AnsiStyle.subtle("  已批准 tool 范围"));
        return ApprovalResult.approveAll();
    }

    private ApprovalResult promptForModifiedArgs(ApprovalRequest request) {
        out.println("  当前参数: " + request.arguments());
        out.print("  修改后的 JSON（空行 = 保留原参数）: ");
        out.flush();
        String modified;
        try {
            modified = stdinReader.readLine();
        } catch (IOException e) {
            return null;
        }
        if (modified == null || modified.isBlank()) {
            out.println(AnsiStyle.subtle("  保留原参数"));
            return ApprovalResult.approve();
        }
        String trimmed = modified.trim();
        try {
            JSON.readTree(trimmed);
        } catch (Exception e) {
            out.println(AnsiStyle.subtle("  ❌ 非法 JSON: " + e.getMessage()));
            return null;
        }
        return ApprovalResult.modify(trimmed);
    }
}
