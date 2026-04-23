package com.paicli.mcp.jsonrpc;

public class JsonRpcException extends RuntimeException {
    private final int code;

    public JsonRpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
