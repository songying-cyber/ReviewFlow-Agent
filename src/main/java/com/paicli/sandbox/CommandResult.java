package com.paicli.sandbox;

public record CommandResult(
        String text,
        SandboxAuditMetadata sandboxMetadata
) {
    public CommandResult {
        text = text == null ? "" : text;
        sandboxMetadata = sandboxMetadata == null ? SandboxAuditMetadata.off() : sandboxMetadata;
    }
}
