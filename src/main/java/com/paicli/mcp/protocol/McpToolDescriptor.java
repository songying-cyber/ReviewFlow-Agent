package com.paicli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolDescriptor(
        String serverName,
        String name,
        String namespacedName,
        String description,
        JsonNode inputSchema
) {
    public static String namespaced(String serverName, String toolName) {
        return "mcp__" + serverName + "__" + toolName;
    }
}
