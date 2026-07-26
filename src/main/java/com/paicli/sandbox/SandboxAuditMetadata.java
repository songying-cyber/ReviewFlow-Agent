package com.paicli.sandbox;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SandboxAuditMetadata(
        boolean enabled,
        boolean used,
        boolean required,
        String runtime,
        @JsonProperty("auto_allowed") boolean autoAllowed,
        @JsonProperty("unsandboxed_reason") String unsandboxedReason,
        String network,
        @JsonProperty("profile_path") String profilePath,
        List<String> violations
) {
    public SandboxAuditMetadata {
        runtime = runtime == null ? "" : runtime;
        unsandboxedReason = unsandboxedReason == null ? "" : unsandboxedReason;
        network = network == null ? "" : network;
        profilePath = profilePath == null ? "" : profilePath;
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public static SandboxAuditMetadata off() {
        return new SandboxAuditMetadata(false, false, false, "", false,
                "sandbox_disabled", "", "", List.of());
    }
}
