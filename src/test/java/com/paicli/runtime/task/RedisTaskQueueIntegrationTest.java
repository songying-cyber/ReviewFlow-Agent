package com.paicli.runtime.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RedisTaskQueueIntegrationTest {

    @Test
    void redisQueuePublishesAndPollsTaskIds(@TempDir Path tempDir) throws Exception {
        assumeTrue(redisServerAvailable(), "redis-server not found");
        int port = freePort();
        try (RedisServerProcess redis = RedisServerProcess.start(port, tempDir);
             RedisTaskQueue queue = new RedisTaskQueue("redis://localhost:" + port + "/0", "paicli:test:queue")) {

            queue.publish("task_redis_1");
            Optional<String> taskId = queue.poll(Duration.ofSeconds(2));

            assertTrue(taskId.isPresent());
            assertEquals("task_redis_1", taskId.get());
        }
    }

    @Test
    void durableTaskManagerUsesRedisWakeupButDbClaimDedupes(@TempDir Path tempDir) throws Exception {
        assumeTrue(redisServerAvailable(), "redis-server not found");
        int port = freePort();
        AtomicInteger runs = new AtomicInteger();
        try (RedisServerProcess redis = RedisServerProcess.start(port, tempDir);
             RedisTaskQueue queue = new RedisTaskQueue("redis://localhost:" + port + "/0", "paicli:test:durable");
             DurableTaskManager manager = new DurableTaskManager(
                     tempDir.resolve("tasks.db"),
                     prompt -> {
                         runs.incrementAndGet();
                         return "done:" + prompt;
                     },
                     1,
                     queue)) {
            manager.start();

            DurableTask task = manager.enqueue("redis-dedupe");
            queue.publish(task.id());
            queue.publish(task.id());
            DurableTask completed = waitForTerminal(manager, task.id());
            Thread.sleep(150);

            assertEquals(TaskStatus.COMPLETED, completed.status());
            assertEquals("done:redis-dedupe", completed.result());
            assertEquals(1, runs.get());
        }
    }

    private static DurableTask waitForTerminal(DurableTaskManager manager, String id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            DurableTask task = manager.find(id).orElseThrow();
            if (task.terminal()) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("task did not finish in time");
    }

    private static boolean redisServerAvailable() {
        try {
            Process process = new ProcessBuilder("redis-server", "--version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class RedisServerProcess implements AutoCloseable {
        private final Process process;

        private RedisServerProcess(Process process) {
            this.process = process;
        }

        static RedisServerProcess start(int port, Path dir) throws Exception {
            Process process = new ProcessBuilder(
                    "redis-server",
                    "--port", String.valueOf(port),
                    "--bind", "127.0.0.1",
                    "--save", "",
                    "--appendonly", "no",
                    "--dir", dir.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            RedisServerProcess server = new RedisServerProcess(process);
            server.waitUntilReady(port);
            return server;
        }

        private void waitUntilReady(int port) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new IllegalStateException("redis-server exited early");
                }
                try (RedisTaskQueue queue = new RedisTaskQueue("redis://localhost:" + port + "/0", "paicli:test:ping")) {
                    return;
                } catch (Exception ignored) {
                    Thread.sleep(50);
                }
            }
            throw new IllegalStateException("redis-server did not become ready");
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
