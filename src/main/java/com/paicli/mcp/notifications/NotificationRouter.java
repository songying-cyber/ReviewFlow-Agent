package com.paicli.mcp.notifications;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 路由 server → client 的通知到注册的 handler。
 *
 * **关键约束**：handler 在独立 daemon executor 里执行，**不在 transport 的 stdout reader 线程里同步执行**。
 * 否则 handler 内部如果要发 JSON-RPC 请求并等响应，自己等自己的响应，stdout reader 被阻塞读不到响应 → 死锁。
 * 典型场景：server-everything 启动后立即推送 tools/list_changed，handler 调 tools/list 重拉，
 * stdout reader 线程被挂在 handler.apply 里，tools/list 响应进 buffer 但没人读，最终请求超时。
 */
public class NotificationRouter implements Consumer<JsonNode>, AutoCloseable {
    private final Map<String, Consumer<JsonNode>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService dispatcher;

    public NotificationRouter() {
        AtomicInteger threadId = new AtomicInteger();
        this.dispatcher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "paicli-mcp-notifications-" + threadId.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public void on(String method, Consumer<JsonNode> handler) {
        if (method == null || method.isBlank() || handler == null) {
            return;
        }
        handlers.put(method, handler);
    }

    @Override
    public void accept(JsonNode message) {
        if (message == null || message.has("id")) {
            return;
        }
        String method = message.path("method").asText("");
        Consumer<JsonNode> handler = handlers.get(method);
        if (handler == null) {
            return;
        }
        JsonNode params = message.path("params");
        // 异步派发，避免在 transport reader 线程里同步执行 handler 引发自我死锁
        try {
            dispatcher.submit(() -> {
                try {
                    handler.accept(params);
                } catch (Exception ignored) {
                    // 通知 handler 失败是 best-effort，不能影响 transport 流
                }
            });
        } catch (Exception ignored) {
            // executor 已关停（PaiCLI 退出中），忽略
        }
    }

    @Override
    public void close() {
        dispatcher.shutdownNow();
    }
}
