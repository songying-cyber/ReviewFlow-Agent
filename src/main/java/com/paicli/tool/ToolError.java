package com.paicli.tool;

public record ToolError(
        ToolErrorType errorType,
        boolean recoverable,
        String message,
        String suggestion
) {
    public ToolError {
        errorType = errorType == null ? ToolErrorType.EXECUTION_ERROR : errorType;
        message = message == null ? "" : message;
        suggestion = suggestion == null ? "" : suggestion;
    }
}
