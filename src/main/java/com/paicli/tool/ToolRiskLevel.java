package com.paicli.tool;

public enum ToolRiskLevel {
    READ_ONLY,
    LOW_WRITE,
    MEDIUM_WRITE,
    HIGH_RISK;

    public boolean requiresApprovalByDefault() {
        return compareTo(MEDIUM_WRITE) >= 0;
    }

    public boolean shouldAuditByDefault() {
        return compareTo(LOW_WRITE) >= 0;
    }

    public boolean allowsApproveAll() {
        return this != HIGH_RISK;
    }
}
