package com.paicli.tool;

public record ToolMetadata(ToolRiskLevel riskLevel, String sideEffectDescription) {
    public ToolMetadata {
        riskLevel = riskLevel == null ? ToolRiskLevel.READ_ONLY : riskLevel;
        sideEffectDescription = sideEffectDescription == null ? "" : sideEffectDescription;
    }

    public static ToolMetadata readOnly(String description) {
        return new ToolMetadata(ToolRiskLevel.READ_ONLY, description);
    }

    public static ToolMetadata lowWrite(String description) {
        return new ToolMetadata(ToolRiskLevel.LOW_WRITE, description);
    }

    public static ToolMetadata mediumWrite(String description) {
        return new ToolMetadata(ToolRiskLevel.MEDIUM_WRITE, description);
    }

    public static ToolMetadata highRisk(String description) {
        return new ToolMetadata(ToolRiskLevel.HIGH_RISK, description);
    }
}
