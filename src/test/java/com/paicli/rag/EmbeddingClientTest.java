package com.paicli.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingClientTest {

    @Test
    void testDefaultConfiguration() {
        EmbeddingClient client = new EmbeddingClient();
        assertEquals("ollama", client.getProvider());
        assertEquals("nomic-embed-text:latest", client.getModel());
    }

    @Test
    void testCustomConfiguration() {
        EmbeddingClient client = new EmbeddingClient("zhipu", "embedding-3",
                "https://open.bigmodel.cn/api/paas/v4", "test-key");
        assertEquals("zhipu", client.getProvider());
        assertEquals("embedding-3", client.getModel());
    }

    @Test
    void testEmptyInputReturnsEmptyArray() throws Exception {
        EmbeddingClient client = new EmbeddingClient();
        assertEquals(0, client.embed("").length);
        assertEquals(0, client.embed(null).length);
    }
}
