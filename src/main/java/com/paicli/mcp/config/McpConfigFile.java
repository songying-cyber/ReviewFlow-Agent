package com.paicli.mcp.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class McpConfigFile {
    private Map<String, McpServerConfig> mcpServers = new LinkedHashMap<>();

    public Map<String, McpServerConfig> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(Map<String, McpServerConfig> mcpServers) {
        this.mcpServers = mcpServers == null ? new LinkedHashMap<>() : mcpServers;
    }
}
