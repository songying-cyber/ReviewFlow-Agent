package com.paicli.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpInitializeResult(
        String protocolVersion,
        McpCapabilities capabilities,
        ServerInfo serverInfo
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServerInfo(String name, String version) {
    }
}
