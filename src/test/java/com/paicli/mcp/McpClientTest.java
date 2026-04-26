package com.paicli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.mcp.resources.McpResourceContent;
import com.paicli.mcp.resources.McpResourceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void initializeSendsHandshakeAndInitializedNotification() throws Exception {
        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", params -> MAPPER.createObjectNode());
        McpClient client = new McpClient("demo", transport);

        client.initialize();

        List<JsonNode> sent = transport.sentMessages();
        assertEquals(2, sent.size(), "initialize 需要发出请求 + initialized 通知");
        assertEquals("initialize", sent.get(0).path("method").asText());
        assertTrue(sent.get(0).has("id"), "初始化请求必须有 id");
        assertEquals("notifications/initialized", sent.get(1).path("method").asText());
        assertFalse(sent.get(1).has("id"), "initialized 是 notification，不应有 id");
        client.close();
    }

    @Test
    void listToolsConvertsServerToolsToNamespacedDescriptors() throws Exception {
        String toolsResponseJson = """
                {
                  "tools": [
                    {"name": "echo", "description": "echo back",
                     "inputSchema": {"type":"object","properties":{"text":{"type":"string"}}}},
                    {"name": "ping",
                     "inputSchema": {"type":"object"}}
                  ]
                }
                """;
        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", p -> MAPPER.createObjectNode())
                .handle("tools/list", p -> readJson(toolsResponseJson));
        McpClient client = new McpClient("filesystem", transport);
        client.initialize();

        List<McpToolDescriptor> tools = client.listTools();
        assertEquals(2, tools.size());
        assertEquals("echo", tools.get(0).name());
        assertEquals("filesystem", tools.get(0).serverName());
        assertEquals("mcp__filesystem__echo", tools.get(0).namespacedName());
        assertEquals("echo back", tools.get(0).description());
        assertEquals("object", tools.get(0).inputSchema().path("type").asText());
        client.close();
    }

    @Test
    void listToolsSanitizesSchemaRefAndAnyOf() throws Exception {
        String toolsResponseJson = """
                {
                  "tools": [
                    {"name": "weird",
                     "inputSchema": {
                       "$schema": "http://json-schema.org/draft-07/schema#",
                       "$ref": "#/defs/foo",
                       "type": "object",
                       "anyOf": [
                         {"type": "string"},
                         {"type": "number"}
                       ]
                     }}
                  ]
                }
                """;
        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", p -> MAPPER.createObjectNode())
                .handle("tools/list", p -> readJson(toolsResponseJson));
        McpClient client = new McpClient("svc", transport);
        client.initialize();

        List<McpToolDescriptor> tools = client.listTools();
        JsonNode schema = tools.get(0).inputSchema();

        assertFalse(schema.has("$schema"), "$schema 应被删除");
        assertFalse(schema.has("$ref"), "$ref 应被删除");
        assertFalse(schema.has("anyOf"), "anyOf 应被降级，移出顶层");
        assertEquals("object", schema.path("type").asText());
        // anyOf 信息应该融到 description 里
        String desc = schema.path("description").asText("");
        assertTrue(desc.contains("anyOf"), "降级后应在 description 里说明 anyOf 备选: " + desc);
        client.close();
    }

    @Test
    void callToolReturnsFormattedTextContent() throws Exception {
        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", p -> MAPPER.createObjectNode())
                .handle("tools/call", p -> readJson("""
                        {"content": [
                            {"type": "text", "text": "result line 1"},
                            {"type": "text", "text": "result line 2"}
                        ], "isError": false}
                        """));
        McpClient client = new McpClient("demo", transport);
        client.initialize();

        String result = client.callTool("echo", "{\"text\":\"hi\"}");
        assertTrue(result.contains("result line 1"));
        assertTrue(result.contains("result line 2"));
        assertFalse(result.startsWith("MCP 工具返回错误"));
        client.close();
    }

    @Test
    void callToolWrapsIsErrorWithExplicitPrefix() throws Exception {
        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", p -> MAPPER.createObjectNode())
                .handle("tools/call", p -> readJson("""
                        {"content": [{"type": "text", "text": "no such file"}], "isError": true}
                        """));
        McpClient client = new McpClient("demo", transport);
        client.initialize();

        String result = client.callTool("read_file", "{\"path\":\"x\"}");
        assertTrue(result.startsWith("MCP 工具返回错误"), "isError=true 应前置错误标识: " + result);
        assertTrue(result.contains("no such file"));
        client.close();
    }

    @Test
    void callToolFallsBackForNonTextContent() throws Exception {
        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", p -> MAPPER.createObjectNode())
                .handle("tools/call", p -> readJson("""
                        {"content": [
                            {"type": "image", "data": "AAA", "mimeType": "image/png"},
                            {"type": "resource", "resource": {"uri": "file://x"}}
                        ], "isError": false}
                        """));
        McpClient client = new McpClient("demo", transport);
        client.initialize();

        String result = client.callTool("snap", "{}");
        assertTrue(result.contains("[此工具返回了 image"));
        assertTrue(result.contains("[此工具返回了 resource"));
        client.close();
    }

    @Test
    void closeIsBestEffortAndDoesNotBlockOnServerSilence() throws Exception {
        // close 不再发 shutdown notification（server 卡死时会让 PaiCLI 退出阻塞）。
        // 关闭语义改由 transport 层承担：stdio = stdin EOF + destroy；HTTP = DELETE session。
        // 这里验证 close 不会因为 server 不响应 shutdown 而 throw / hang。
        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", p -> MAPPER.createObjectNode());
        McpClient client = new McpClient("demo", transport);
        client.initialize();
        int before = transport.sentMessages().size();

        long start = System.currentTimeMillis();
        client.close();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 2000, "close 应秒级返回，不应阻塞");
        // 应该没有新消息被发出
        assertEquals(before, transport.sentMessages().size(),
                "close 不再发 shutdown 通知，sent 列表不应增长");
    }

    @Test
    void listResourcesConvertsServerResources() throws Exception {
        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", p -> readJson("""
                        {"capabilities":{"resources":{"listChanged":true}}}
                        """))
                .handle("resources/list", p -> readJson("""
                        {"resources":[
                          {"uri":"file://README.md","name":"README.md","description":"docs","mimeType":"text/markdown","size":42}
                        ]}
                        """));
        McpClient client = new McpClient("fs", transport);
        client.initialize();

        List<McpResourceDescriptor> resources = client.listResources();

        assertTrue(client.supportsResources());
        assertEquals(1, resources.size());
        assertEquals("fs", resources.get(0).serverName());
        assertEquals("file://README.md", resources.get(0).uri());
        assertEquals("text/markdown", resources.get(0).mimeType());
        client.close();
    }

    @Test
    void listResourcesTreatsMethodNotFoundAsEmptyList() throws Exception {
        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", p -> MAPPER.createObjectNode());
        McpClient client = new McpClient("fs", transport);
        client.initialize();

        assertTrue(client.listResources().isEmpty());
        client.close();
    }

    @Test
    void readResourceReturnsTextContents() throws Exception {
        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", p -> MAPPER.createObjectNode())
                .handle("resources/read", p -> readJson("""
                        {"contents":[{"uri":"file://README.md","mimeType":"text/markdown","text":"hello"}]}
                        """));
        McpClient client = new McpClient("fs", transport);
        client.initialize();

        List<McpResourceContent> contents = client.readResource("file://README.md");

        assertEquals(1, contents.size());
        assertEquals("hello", contents.get(0).text());
        assertTrue(McpClient.formatResourceContents(contents).contains("<resource uri=\"file://README.md\""));
        client.close();
    }

    @Test
    void listPromptsFormatsPromptSummaries() throws Exception {
        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", p -> readJson("""
                        {"capabilities":{"prompts":{"listChanged":true}}}
                        """))
                .handle("prompts/list", p -> readJson("""
                        {"prompts":[{"name":"review","title":"Review","description":"Review code"}]}
                        """));
        McpClient client = new McpClient("svc", transport);
        client.initialize();

        List<String> prompts = client.listPrompts();

        assertTrue(client.supportsPrompts());
        assertEquals(List.of("Review (review) - Review code"), prompts);
        client.close();
    }

    private static JsonNode readJson(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
