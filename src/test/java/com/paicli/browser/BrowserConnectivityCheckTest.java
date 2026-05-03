package com.paicli.browser;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserConnectivityCheckTest {

    @Test
    void probeSucceedsWhenJsonVersionResponds() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            server.start();

            BrowserConnectivityCheck.ProbeResult result = new BrowserConnectivityCheck().probe(server.getPort());

            assertTrue(result.ok());
            assertEquals("http://127.0.0.1:" + server.getPort(), result.browserUrl());
            assertEquals("/json/version", server.takeRequest().getPath());
        }
    }

    @Test
    void probeFailsForHttpError() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(404));
            server.start();

            BrowserConnectivityCheck.ProbeResult result = new BrowserConnectivityCheck().probe(server.getPort());

            assertFalse(result.ok());
            assertTrue(result.message().contains("HTTP 404"));
        }
    }

    @Test
    void probeRejectsInvalidPort() {
        BrowserConnectivityCheck.ProbeResult result = new BrowserConnectivityCheck().probe(80);

        assertFalse(result.ok());
        assertTrue(result.message().contains("1024-65535"));
    }
}
