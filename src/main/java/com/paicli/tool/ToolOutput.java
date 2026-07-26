package com.paicli.tool;

import com.paicli.llm.LlmClient;

import java.util.List;

public record ToolOutput(boolean ok, ToolExecutionStatus status, String text, ToolError error,
                         ToolResultMeta meta, List<LlmClient.ContentPart> imageParts) {
    public ToolOutput {
        text = text == null ? "" : text;
        status = status == null ? (ok ? ToolExecutionStatus.SUCCESS : ToolExecutionStatus.FAILED) : status;
        if (!ok && error == null) {
            error = new ToolError(ToolErrorType.EXECUTION_ERROR, false, text, "");
        }
        meta = meta == null ? ToolResultMeta.empty() : meta;
        imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
    }

    public ToolOutput(boolean ok, String text, ToolError error, List<LlmClient.ContentPart> imageParts) {
        this(ok, ok ? ToolExecutionStatus.SUCCESS : ToolExecutionStatus.FAILED, text, error,
                ToolResultMeta.empty(), imageParts);
    }

    public ToolOutput(String text, List<LlmClient.ContentPart> imageParts) {
        this(true, ToolExecutionStatus.SUCCESS, text, null, ToolResultMeta.empty(), imageParts);
    }

    public static ToolOutput text(String text) {
        return new ToolOutput(true, ToolExecutionStatus.SUCCESS, text, null, ToolResultMeta.empty(), List.of());
    }

    public static ToolOutput status(ToolExecutionStatus status, String text, ToolResultMeta meta) {
        ToolExecutionStatus effective = status == null ? ToolExecutionStatus.SUCCESS : status;
        boolean ok = effective == ToolExecutionStatus.SUCCESS
                || effective == ToolExecutionStatus.PARTIAL
                || effective == ToolExecutionStatus.PENDING
                || effective == ToolExecutionStatus.UNKNOWN;
        return new ToolOutput(ok, effective, text, null, meta, List.of());
    }

    public static ToolOutput partial(String text, String reason, String nextAction) {
        return status(ToolExecutionStatus.PARTIAL, text, ToolResultMeta.partial(reason, nextAction));
    }

    public static ToolOutput error(ToolError error) {
        ToolError effective = error == null
                ? new ToolError(ToolErrorType.EXECUTION_ERROR, false, "工具执行失败", "")
                : error;
        return error(effective, ToolExecutionStatus.FAILED);
    }

    public static ToolOutput error(ToolError error, ToolExecutionStatus status) {
        ToolError effective = error == null
                ? new ToolError(ToolErrorType.EXECUTION_ERROR, false, "工具执行失败", "")
                : error;
        ToolExecutionStatus effectiveStatus = status == null ? ToolExecutionStatus.FAILED : status;
        return new ToolOutput(false, effectiveStatus, effective.message(), effective, ToolResultMeta.empty(), List.of());
    }

    public static ToolOutput error(ToolErrorType errorType, boolean recoverable,
                                   String message, String suggestion) {
        return error(new ToolError(errorType, recoverable, message, suggestion));
    }

    public boolean hasImageParts() {
        return !imageParts.isEmpty();
    }
}
