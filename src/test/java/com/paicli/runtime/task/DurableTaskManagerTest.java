package com.paicli.runtime.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class DurableTaskManagerTest {

    @Test
    void runsEnqueuedTaskAndPersistsResult(@TempDir Path tempDir) throws Exception {
        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("tasks.db"),
                prompt -> "done:" + prompt,
                1)) {
            manager.start();

            DurableTask task = manager.enqueue("hello");
            DurableTask completed = waitForTerminal(manager, task.id());

            assertEquals(TaskStatus.COMPLETED, completed.status());
            assertEquals("done:hello", completed.result());
            assertTrue(manager.list(10).stream().anyMatch(t -> t.id().equals(task.id())));
        }
    }

    @Test
    void recoversRunningTasksAsEnqueued(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("tasks.db");
        try (DurableTaskManager manager = new DurableTaskManager(db, prompt -> "never", 1)) {
            DurableTask task = manager.enqueue("resume me");
            markRunning(manager, task.id());
        }

        try (DurableTaskManager recovered = new DurableTaskManager(db, prompt -> "ok", 1)) {
            assertEquals(TaskStatus.ENQUEUED, recovered.find(recovered.list(1).get(0).id()).orElseThrow().status());
        }
    }

    @Test
    void cancelsRunningTask(@TempDir Path tempDir) throws Exception {
        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("tasks.db"),
                prompt -> {
                    Thread.sleep(5000);
                    return "late";
                },
                1)) {
            manager.start();
            DurableTask task = manager.enqueue("slow");
            waitUntilStatus(manager, task.id(), TaskStatus.RUNNING);

            assertTrue(manager.cancel(task.id()));
            DurableTask canceled = waitForTerminal(manager, task.id());

            assertEquals(TaskStatus.CANCELED, canceled.status());
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
        fail("task did not finish in time");
        return null;
    }

    private static void waitUntilStatus(DurableTaskManager manager, String id, TaskStatus status) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (manager.find(id).orElseThrow().status() == status) {
                return;
            }
            Thread.sleep(20);
        }
        fail("task did not reach status " + status);
    }

    private static void markRunning(DurableTaskManager manager, String id) throws Exception {
        var field = DurableTaskManager.class.getDeclaredField("connection");
        field.setAccessible(true);
        java.sql.Connection connection = (java.sql.Connection) field.get(manager);
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "UPDATE runtime_tasks SET status = 'running' WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }
}
