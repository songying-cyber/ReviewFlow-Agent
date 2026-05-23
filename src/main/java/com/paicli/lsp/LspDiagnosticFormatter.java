package com.paicli.lsp;

import com.paicli.util.AnsiStyle;

import java.util.List;

public final class LspDiagnosticFormatter {
    private static final int DEFAULT_MAX_DIAGNOSTICS = 20;

    private LspDiagnosticFormatter() {
    }

    public static LspDiagnosticReport format(List<LspDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return LspDiagnosticReport.EMPTY;
        }
        int max = maxDiagnostics();
        int count = Math.min(max, diagnostics.size());
        int omitted = diagnostics.size() - count;

        StringBuilder prompt = new StringBuilder();
        prompt.append("[LSP 诊断注入]\n");
        prompt.append("Agent 刚修改代码后，系统收集到以下诊断。请优先修复 error，再处理 warning；不要原样重复同一处错误写法。\n\n");

        StringBuilder display = new StringBuilder();
        display.append("\n").append(AnsiStyle.heading("🔎 LSP 诊断")).append("\n");

        for (int i = 0; i < count; i++) {
            LspDiagnostic d = diagnostics.get(i);
            String line = String.format("- [%s] %s:%d:%d %s (%s)",
                    severityLabel(d.severity()), d.filePath(), d.line(), d.column(), d.message(), d.source());
            prompt.append(line).append("\n");
            display.append(colorize(d.severity(), line)).append("\n");
        }

        if (omitted > 0) {
            String line = "... 还有 " + omitted + " 条诊断未注入（已达到上限 " + max + "）";
            prompt.append(line).append("\n");
            display.append(AnsiStyle.subtle(line)).append("\n");
        }
        return new LspDiagnosticReport(prompt.toString().trim(), display.toString().trim());
    }

    static int maxDiagnostics() {
        String property = System.getProperty("paicli.lsp.max.diagnostics");
        if (property == null || property.isBlank()) {
            property = System.getenv("PAICLI_LSP_MAX_DIAGNOSTICS");
        }
        if (property == null || property.isBlank()) {
            return DEFAULT_MAX_DIAGNOSTICS;
        }
        try {
            int parsed = Integer.parseInt(property.trim());
            return parsed > 0 ? parsed : DEFAULT_MAX_DIAGNOSTICS;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_DIAGNOSTICS;
        }
    }

    private static String severityLabel(LspSeverity severity) {
        return switch (severity) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "info";
        };
    }

    private static String colorize(LspSeverity severity, String text) {
        return switch (severity) {
            case ERROR -> AnsiStyle.error(text);
            case WARNING -> AnsiStyle.codeLabel(text);
            case INFO -> AnsiStyle.subtle(text);
        };
    }
}
