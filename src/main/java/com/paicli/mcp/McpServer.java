package com.paicli.mcp;

import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.protocol.McpToolDescriptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class McpServer {
    private final String name;
    private final McpServerConfig config;
    private volatile McpServerStatus status = McpServerStatus.DISABLED;
    private volatile McpClient client;
    private volatile List<McpToolDescriptor> tools = List.of();
    private volatile String errorMessage;
    private volatile Instant startedAt;

    public McpServer(String name, McpServerConfig config) {
        this.name = name;
        this.config = config;
        if (config.isDisabled()) {
            this.status = McpServerStatus.DISABLED;
        }
    }

    public String name() {
        return name;
    }

    public McpServerConfig config() {
        return config;
    }

    public McpServerStatus status() {
        return status;
    }

    public void status(McpServerStatus status) {
        this.status = status;
    }

    public McpClient client() {
        return client;
    }

    public void client(McpClient client) {
        this.client = client;
    }

    public List<McpToolDescriptor> tools() {
        return tools;
    }

    public void tools(List<McpToolDescriptor> tools) {
        this.tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public String errorMessage() {
        return errorMessage;
    }

    public void errorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public void markStarted() {
        this.startedAt = Instant.now();
    }

    public Duration uptime() {
        if (startedAt == null || status != McpServerStatus.READY) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, Instant.now());
    }

    public Long processId() {
        return client == null ? null : client.processId();
    }

    public String transportName() {
        if (client != null) {
            return client.transportName();
        }
        return config.transportName();
    }

    public List<String> logs() {
        return client == null ? List.of() : client.stderrLines();
    }

    public void close() {
        if (client != null) {
            client.close();
            client = null;
        }
        tools = List.of();
    }
}
