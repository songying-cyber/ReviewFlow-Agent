package com.paicli.sandbox;

public record SandboxDecision(
        boolean sandboxEnabled,
        boolean useSandbox,
        boolean denied,
        boolean autoAllowed,
        String denyReason,
        String unsandboxedReason,
        String runtime
) {
    public SandboxDecision {
        denyReason = denyReason == null ? "" : denyReason;
        unsandboxedReason = unsandboxedReason == null ? "" : unsandboxedReason;
        runtime = runtime == null ? "" : runtime;
    }

    public static SandboxDecision disabled() {
        return new SandboxDecision(false, false, false, false, "",
                "sandbox_disabled", "");
    }

    public static SandboxDecision denied(String reason) {
        return new SandboxDecision(true, false, true, false, reason,
                "", "macos-seatbelt");
    }
}
