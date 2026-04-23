package com.paicli.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;

public final class JsonRpcMessage {
    private JsonRpcMessage() {
    }

    public record Request(long id, String method, JsonNode params) {
    }

    public record Response(long id, JsonNode result, Error error) {
    }

    public record Notification(String method, JsonNode params) {
    }

    public record Error(int code, String message, JsonNode data) {
    }
}
