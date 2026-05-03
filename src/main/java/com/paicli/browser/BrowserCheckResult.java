package com.paicli.browser;

public record BrowserCheckResult(
        boolean blocked,
        String reason,
        boolean requiresPerCallApproval,
        String sensitiveNotice,
        BrowserAuditMetadata metadata
) {
    public static BrowserCheckResult allow(BrowserAuditMetadata metadata) {
        return new BrowserCheckResult(false, null, false, null, metadata);
    }

    public static BrowserCheckResult requireApproval(String notice, BrowserAuditMetadata metadata) {
        return new BrowserCheckResult(false, null, true, notice, metadata);
    }

    public static BrowserCheckResult block(String reason, BrowserAuditMetadata metadata) {
        return new BrowserCheckResult(true, reason, false, null, metadata);
    }
}
