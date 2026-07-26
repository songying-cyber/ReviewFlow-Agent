package com.paicli.tool;

public record ToolResultMeta(
        boolean partial,
        String reason,
        String operationId,
        String nextAction
) {
    public ToolResultMeta {
        reason = reason == null ? "" : reason;
        operationId = operationId == null ? "" : operationId;
        nextAction = nextAction == null ? "" : nextAction;
    }

    public static ToolResultMeta empty() {
        return new ToolResultMeta(false, "", "", "");
    }

    public static ToolResultMeta partial(String reason, String nextAction) {
        return new ToolResultMeta(true, reason, "", nextAction);
    }
}
