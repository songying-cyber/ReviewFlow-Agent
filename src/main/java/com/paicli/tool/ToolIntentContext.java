package com.paicli.tool;

public record ToolIntentContext(String userRequest, String executionContext) {
    public ToolIntentContext {
        userRequest = userRequest == null ? "" : userRequest;
        executionContext = executionContext == null ? "" : executionContext;
    }

    public static ToolIntentContext empty() {
        return new ToolIntentContext("", "");
    }

    public boolean isBlank() {
        return userRequest.isBlank() && executionContext.isBlank();
    }
}
