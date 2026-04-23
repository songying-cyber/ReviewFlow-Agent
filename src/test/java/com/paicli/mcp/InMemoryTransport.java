package com.paicli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.transport.McpTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 测试专用 transport：把客户端发出的请求按 method 路由到预设的响应函数，
 * 同步触发 listener，模拟同步响应路径。
 */
public class InMemoryTransport implements McpTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final java.util.Map<String, Function<JsonNode, JsonNode>> handlers = new java.util.LinkedHashMap<>();
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private final List<JsonNode> sent = new ArrayList<>();
    private boolean closed;

    public InMemoryTransport handle(String method, Function<JsonNode, JsonNode> handler) {
        handlers.put(method, handler);
        return this;
    }

    public List<JsonNode> sentMessages() {
        return List.copyOf(sent);
    }

    @Override
    public synchronized void send(JsonNode message) throws IOException {
        if (closed) throw new IOException("closed");
        sent.add(message);
        String method = message.path("method").asText("");
        JsonNode idNode = message.get("id");
        if (idNode == null || idNode.isNull()) {
            // notification, no response
            return;
        }
        Function<JsonNode, JsonNode> handler = handlers.get(method);
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", idNode);
        if (handler == null) {
            ObjectNode error = response.putObject("error");
            error.put("code", -32601);
            error.put("message", "method not found: " + method);
        } else {
            JsonNode result = handler.apply(message.path("params"));
            response.set("result", result);
        }
        for (Consumer<JsonNode> listener : listeners) {
            listener.accept(response);
        }
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) listeners.add(listener);
    }

    @Override
    public String transportName() {
        return "in-memory";
    }

    @Override
    public void close() {
        closed = true;
    }
}
