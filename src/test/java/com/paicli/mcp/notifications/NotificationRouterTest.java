package com.paicli.mcp.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void routesRegisteredNotification() throws Exception {
        NotificationRouter router = new NotificationRouter();
        try {
            CountDownLatch fired = new CountDownLatch(1);
            router.on("notifications/tools/list_changed", params -> fired.countDown());

            router.accept(MAPPER.readTree("""
                    {"jsonrpc":"2.0","method":"notifications/tools/list_changed","params":{}}
                    """));

            assertTrue(fired.await(2, TimeUnit.SECONDS), "handler 应在异步派发后被调用");
        } finally {
            router.close();
        }
    }

    @Test
    void ignoresUnknownNotification() throws Exception {
        NotificationRouter router = new NotificationRouter();
        try {
            AtomicInteger calls = new AtomicInteger();
            router.on("known", params -> calls.incrementAndGet());

            router.accept(MAPPER.readTree("""
                    {"jsonrpc":"2.0","method":"unknown","params":{}}
                    """));
            // 等一小段确认不会异步触发
            Thread.sleep(100);

            assertEquals(0, calls.get());
        } finally {
            router.close();
        }
    }

    @Test
    void ignoresMessagesWithIdBecauseTheyAreRequestsOrResponses() throws Exception {
        NotificationRouter router = new NotificationRouter();
        try {
            AtomicInteger calls = new AtomicInteger();
            router.on("notifications/tools/list_changed", params -> calls.incrementAndGet());

            router.accept(MAPPER.readTree("""
                    {"jsonrpc":"2.0","id":1,"method":"notifications/tools/list_changed","params":{}}
                    """));
            Thread.sleep(100);

            assertEquals(0, calls.get());
        } finally {
            router.close();
        }
    }

    @Test
    void dispatchesMultipleNotificationsInOrder() throws Exception {
        NotificationRouter router = new NotificationRouter();
        try {
            java.util.List<Integer> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(3);
            router.on("tick", params -> {
                seen.add(params.path("n").asInt());
                latch.countDown();
            });

            for (int i = 1; i <= 3; i++) {
                router.accept(MAPPER.readTree(
                        "{\"jsonrpc\":\"2.0\",\"method\":\"tick\",\"params\":{\"n\":" + i + "}}"));
            }

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals(java.util.List.of(1, 2, 3), seen);
        } finally {
            router.close();
        }
    }
}
