package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public interface McpTransport extends AutoCloseable {
    void send(JsonNode message) throws IOException;

    void onReceive(Consumer<JsonNode> listener);

    default List<String> stderrLines() {
        return List.of();
    }

    default Long processId() {
        return null;
    }

    default String transportName() {
        return "unknown";
    }

    @Override
    void close();
}
