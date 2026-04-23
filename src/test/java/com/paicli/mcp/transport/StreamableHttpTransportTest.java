package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StreamableHttpTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void parsesPlainJsonResponseAndDispatchesToListeners() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"));

        StreamableHttpTransport transport = new StreamableHttpTransport(
                server.url("/mcp").toString(), Map.of());
        List<JsonNode> received = new ArrayList<>();
        transport.onReceive(received::add);

        transport.send(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));

        assertEquals(1, received.size());
        assertTrue(received.get(0).path("result").path("ok").asBoolean());
        assertEquals("http", transport.transportName());
    }

    @Test
    void parsesSseStreamWithMultipleDataMessages() throws Exception {
        String sseBody = "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"step\":1}}\n\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"step\":2}}\n\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody));

        StreamableHttpTransport transport = new StreamableHttpTransport(
                server.url("/mcp").toString(), Map.of());
        List<JsonNode> received = new ArrayList<>();
        transport.onReceive(received::add);

        transport.send(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sub\"}"));

        assertEquals(2, received.size());
        assertEquals(1, received.get(0).path("result").path("step").asInt());
        assertEquals(2, received.get(1).path("result").path("step").asInt());
    }

    @Test
    void capturesAndReusesSessionIdHeader() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "session-abc")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}"));

        StreamableHttpTransport transport = new StreamableHttpTransport(
                server.url("/mcp").toString(), Map.of());
        transport.send(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"a\"}"));
        transport.send(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"b\"}"));

        RecordedRequest first = server.takeRequest();
        assertNull(first.getHeader("Mcp-Session-Id"), "首次请求不应携带 session ID");
        RecordedRequest second = server.takeRequest();
        assertEquals("session-abc", second.getHeader("Mcp-Session-Id"),
                "拿到 session ID 后续请求必须带上");
    }

    @Test
    void closeIssuesDeleteWithSessionId() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "session-xyz")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
        server.enqueue(new MockResponse().setResponseCode(200));

        StreamableHttpTransport transport = new StreamableHttpTransport(
                server.url("/mcp").toString(), Map.of());
        transport.send(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"a\"}"));
        transport.close();

        server.takeRequest(); // 跳过 POST
        RecordedRequest deleteRequest = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(deleteRequest, "close 应触发 DELETE 请求");
        assertEquals("DELETE", deleteRequest.getMethod());
        assertEquals("session-xyz", deleteRequest.getHeader("Mcp-Session-Id"));
    }

    @Test
    void closeWithoutSessionIdSkipsDelete() throws Exception {
        // 没 send 过任何请求，自然没有 session
        StreamableHttpTransport transport = new StreamableHttpTransport(
                server.url("/mcp").toString(), Map.of());
        transport.close();
        // server 没收到请求
        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void unsuccessfulResponseThrowsIoException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));

        StreamableHttpTransport transport = new StreamableHttpTransport(
                server.url("/mcp").toString(), Map.of("Authorization", "Bearer fake"));

        IOException ex = assertThrows(IOException.class,
                () -> transport.send(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")));
        assertTrue(ex.getMessage().contains("401"), "异常消息应含状态码: " + ex.getMessage());
    }

    @Test
    void customHeadersArePropagatedToServer() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));

        StreamableHttpTransport transport = new StreamableHttpTransport(
                server.url("/mcp").toString(),
                Map.of("Authorization", "Bearer test-token", "X-Tenant", "acme"));
        transport.send(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}"));

        RecordedRequest req = server.takeRequest();
        assertEquals("Bearer test-token", req.getHeader("Authorization"));
        assertEquals("acme", req.getHeader("X-Tenant"));
        assertEquals("application/json, text/event-stream", req.getHeader("Accept"));
        assertNotNull(req.getHeader("MCP-Protocol-Version"), "必须发送协议版本 header");
    }
}
