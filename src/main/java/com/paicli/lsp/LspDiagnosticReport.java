package com.paicli.lsp;

public record LspDiagnosticReport(String promptText, String displayText) {
    public static final LspDiagnosticReport EMPTY = new LspDiagnosticReport("", "");

    public boolean isEmpty() {
        return promptText == null || promptText.isBlank();
    }
}
