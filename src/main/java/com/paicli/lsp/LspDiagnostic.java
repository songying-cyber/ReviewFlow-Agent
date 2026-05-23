package com.paicli.lsp;

public record LspDiagnostic(
        LspSeverity severity,
        String filePath,
        int line,
        int column,
        String message,
        String source
) {
    public LspDiagnostic {
        severity = severity == null ? LspSeverity.INFO : severity;
        filePath = filePath == null ? "" : filePath;
        line = Math.max(1, line);
        column = Math.max(1, column);
        message = message == null ? "" : message;
        source = source == null || source.isBlank() ? "lsp" : source;
    }
}
