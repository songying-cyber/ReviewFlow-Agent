package com.paicli.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPolicyTest {

    private final NetworkPolicy policy = new NetworkPolicy();

    @Test
    void rejectsBlankUrl() {
        assertNotNull(policy.checkUrl(null));
        assertNotNull(policy.checkUrl(""));
        assertNotNull(policy.checkUrl("   "));
    }

    @Test
    void rejectsFileScheme() {
        String reason = policy.checkUrl("file:///etc/passwd");
        assertNotNull(reason);
        assertTrue(reason.contains("scheme"));
    }

    @Test
    void rejectsFtpScheme() {
        String reason = policy.checkUrl("ftp://example.com/data");
        assertNotNull(reason);
        assertTrue(reason.contains("scheme"));
    }

    @Test
    void rejectsLocalhostByName() {
        assertNotNull(policy.checkUrl("http://localhost:8080/api"));
        assertNotNull(policy.checkUrl("https://my.localhost/secret"));
    }

    @Test
    void rejectsLoopbackByIp() {
        assertNotNull(policy.checkUrl("http://127.0.0.1/admin"));
    }

    @Test
    void rejectsAnyLocal() {
        assertNotNull(policy.checkUrl("http://0.0.0.0/"));
    }

    @Test
    void rejectsSiteLocal() {
        // 192.168.x.x 是 site-local
        assertNotNull(policy.checkUrl("http://192.168.0.1/"));
    }

    @Test
    void allowsPublicHttps() {
        assertNull(policy.checkUrl("https://example.com/path"));
        assertNull(policy.checkUrl("https://paicoding.com"));
    }

    @Test
    void rateLimitTriggersAfterMaxPerWindow() {
        NetworkPolicy bucket = new NetworkPolicy(60_000L, 3);
        assertNull(bucket.acquire());
        assertNull(bucket.acquire());
        assertNull(bucket.acquire());
        String denied = bucket.acquire();
        assertNotNull(denied);
        assertTrue(denied.contains("过于频繁"));
    }
}
