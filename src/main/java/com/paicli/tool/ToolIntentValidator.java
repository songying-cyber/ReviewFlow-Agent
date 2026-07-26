package com.paicli.tool;

import java.util.Optional;

public interface ToolIntentValidator {
    Optional<ToolError> validate(ToolIntentContext context, ToolRegistry.ToolInvocation invocation,
                                 ToolMetadata metadata);

    static ToolIntentValidator disabled() {
        return (context, invocation, metadata) -> Optional.empty();
    }
}
