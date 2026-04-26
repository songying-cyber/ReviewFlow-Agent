package com.paicli.mcp.resources;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpResourceCacheTest {
    @Test
    void returnsCachedResourcesForFreshServer() {
        McpResourceCache cache = new McpResourceCache();
        cache.put("fs", List.of(resource("fs", "file://README.md")));

        assertEquals(1, cache.get("fs").size());
        assertEquals("file://README.md", cache.get("fs").get(0).uri());
    }

    @Test
    void invalidatedServerReturnsEmptyUntilRefreshed() {
        McpResourceCache cache = new McpResourceCache();
        cache.put("fs", List.of(resource("fs", "file://README.md")));

        cache.invalidateServer("fs");

        assertTrue(cache.get("fs").isEmpty());
        assertTrue(cache.isServerStale("fs"));
    }

    @Test
    void tracksStaleResourceUris() {
        McpResourceCache cache = new McpResourceCache();
        cache.invalidateResource("fs", "file://README.md");

        assertTrue(cache.isResourceStale("fs", "file://README.md"));
        cache.markResourceFresh("fs", "file://README.md");
        assertFalse(cache.isResourceStale("fs", "file://README.md"));
    }

    @Test
    void allReturnsSortedFreshResourcesOnly() {
        McpResourceCache cache = new McpResourceCache();
        cache.put("z", List.of(resource("z", "repo://b")));
        cache.put("a", List.of(resource("a", "file://a")));
        cache.invalidateServer("z");

        List<McpResourceDescriptor> all = cache.all();

        assertEquals(1, all.size());
        assertEquals("a", all.get(0).serverName());
    }

    private static McpResourceDescriptor resource(String server, String uri) {
        return new McpResourceDescriptor(server, uri, "name", "", "", "text/plain", null);
    }
}
