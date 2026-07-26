package com.paicli.runtime.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.paicli.tool.ToolExecutionStatus;
import com.paicli.tool.ToolRegistry.ToolInvocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    void duplicateQueueMessagesOnlyRunTaskOnce(@TempDir Path tempDir) throws Exception {
        LocalTaskQueue queue = new LocalTaskQueue();
        AtomicInteger runs = new AtomicInteger();
        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("tasks.db"),
                prompt -> {
                    runs.incrementAndGet();
                    return "done:" + prompt;
                },
                1,
                queue)) {
            manager.start();

            DurableTask task = manager.enqueue("dedupe");
            queue.publish(task.id());
            queue.publish(task.id());
            DurableTask completed = waitForTerminal(manager, task.id());
            Thread.sleep(150);

            assertEquals(TaskStatus.COMPLETED, completed.status());
            assertEquals(1, runs.get());
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
    void doesNotRecoverActiveLeasedTaskFromAnotherInstance(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("tasks.db");
        String taskId;
        try (DurableTaskManager manager = new DurableTaskManager(db, prompt -> "never", 1)) {
            DurableTask task = manager.enqueue("owned elsewhere");
            taskId = task.id();
            markRunningWithLease(manager, taskId, "other-owner", Instant.now().plus(Duration.ofMinutes(5)));
        }

        try (DurableTaskManager recovered = new DurableTaskManager(db, prompt -> "ok", 1)) {
            DurableTask task = recovered.find(taskId).orElseThrow();
            assertEquals(TaskStatus.RUNNING, task.status());
            assertEquals("other-owner", task.ownerId());
            assertNotNull(task.leaseUntil());
        }
    }

    @Test
    void recoversExpiredLeasedTaskAsEnqueued(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("tasks.db");
        String taskId;
        try (DurableTaskManager manager = new DurableTaskManager(db, prompt -> "never", 1)) {
            DurableTask task = manager.enqueue("expired lease");
            taskId = task.id();
            markRunningWithLease(manager, taskId, "dead-owner", Instant.now().minus(Duration.ofMinutes(5)));
        }

        try (DurableTaskManager recovered = new DurableTaskManager(db, prompt -> "ok", 1)) {
            DurableTask task = recovered.find(taskId).orElseThrow();
            assertEquals(TaskStatus.ENQUEUED, task.status());
            assertNull(task.ownerId());
            assertNull(task.leaseUntil());
        }
    }

    @Test
    void workerDoesNotWriteTerminalStateAfterLeaseTransfer(@TempDir Path tempDir) throws Exception {
        CountDownLatch runnerStarted = new CountDownLatch(1);
        CountDownLatch releaseRunner = new CountDownLatch(1);
        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("tasks.db"),
                prompt -> {
                    runnerStarted.countDown();
                    assertTrue(releaseRunner.await(5, TimeUnit.SECONDS));
                    return "late-result";
                },
                1)) {
            manager.start();
            DurableTask task = manager.enqueue("race");
            assertTrue(runnerStarted.await(5, TimeUnit.SECONDS));
            waitUntilStatus(manager, task.id(), TaskStatus.RUNNING);

            markRunningWithLease(manager, task.id(), "other-owner", Instant.now().plus(Duration.ofMinutes(5)));
            releaseRunner.countDown();
            waitUntilNoLocalWorker(manager, task.id());

            DurableTask latest = manager.find(task.id()).orElseThrow();
            assertEquals(TaskStatus.RUNNING, latest.status());
            assertEquals("other-owner", latest.ownerId());
            assertTrue(latest.result() == null || latest.result().isBlank());
            assertNull(latest.finishedAt());
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

    @Test
    void durableToolRegistryReplaysSuccessfulWrite(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("db/tasks.db"),
                prompt -> "unused",
                1)) {
            DurableToolRegistry registry = new DurableToolRegistry(manager, "wf_replay", "react-loop");
            registry.setProjectPath(project.toString());
            ToolInvocation invocation = new ToolInvocation(
                    "call_1",
                    "write_file",
                    "{\"path\":\"a.txt\",\"content\":\"hello\",\"write_mode\":\"overwrite\"}");

            var first = registry.executeTools(List.of(invocation)).get(0);
            var second = registry.executeTools(List.of(new ToolInvocation(
                    "call_2",
                    "write_file",
                    "{\"content\":\"hello\",\"write_mode\":\"overwrite\",\"path\":\"a.txt\"}"))).get(0);

            assertEquals("文件已写入: a.txt", first.result());
            assertEquals("文件已写入: a.txt", second.result());
            assertEquals(0, second.elapsedMillis());
            assertFalse(first.meta().operationId().isBlank());
            assertEquals(first.meta().operationId(), second.meta().operationId());
            assertEquals(1, manager.succeededReversibleTools("wf_replay").size());
        }
    }

    @Test
    void durableToolRegistryQueriesOperationStatus(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("db/tasks.db"),
                prompt -> "unused",
                1)) {
            DurableToolRegistry registry = new DurableToolRegistry(manager, "wf_status", "react-loop");
            registry.setProjectPath(project.toString());
            var write = registry.executeTools(List.of(new ToolInvocation(
                    "call_1",
                    "write_file",
                    "{\"path\":\"a.txt\",\"content\":\"hello\",\"write_mode\":\"overwrite\"}"))).get(0);

            String operationId = write.meta().operationId();
            var status = registry.executeTools(List.of(new ToolInvocation(
                    "status_1",
                    "tool_status",
                    "{\"operation_id\":\"" + operationId + "\"}"))).get(0);

            assertEquals(ToolExecutionStatus.SUCCESS, status.status());
            assertEquals(operationId, status.meta().operationId());
            assertTrue(status.result().contains("operation_id: " + operationId));
            assertTrue(status.result().contains("status: succeeded"));
        }
    }

    @Test
    void durableToolRegistryCompensatesSingleOperation(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(project.resolve("a.txt"), "before");
        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("db/tasks.db"),
                prompt -> "unused",
                1)) {
            DurableToolRegistry registry = new DurableToolRegistry(manager, "wf_compensate_one", "react-loop");
            registry.setProjectPath(project.toString());
            var write = registry.executeTools(List.of(new ToolInvocation(
                    "call_1",
                    "write_file",
                    "{\"path\":\"a.txt\",\"content\":\"after\",\"write_mode\":\"overwrite\"}"))).get(0);
            assertEquals("after", Files.readString(project.resolve("a.txt")));

            String operationId = write.meta().operationId();
            var compensation = registry.executeTools(List.of(new ToolInvocation(
                    "compensate_1",
                    "tool_compensate",
                    "{\"operation_id\":\"" + operationId + "\"}"))).get(0);

            assertEquals(ToolExecutionStatus.SUCCESS, compensation.status());
            assertEquals("before", Files.readString(project.resolve("a.txt")));
            assertEquals("compensated", manager.findToolExecutionById(operationId).orElseThrow().status());
        }
    }

    @Test
    void compensatesWriteFileUsingSnapshot(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(project.resolve("a.txt"), "before");
        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("db/tasks.db"),
                prompt -> "unused",
                1)) {
            DurableTask task = manager.enqueue("change file", project.toString());
            DurableToolRegistry registry = new DurableToolRegistry(manager, task.id(), "react-loop");
            registry.setProjectPath(project.toString());
            registry.executeTools(List.of(new ToolInvocation(
                    "call_1",
                    "write_file",
                    "{\"path\":\"a.txt\",\"content\":\"after\",\"write_mode\":\"overwrite\"}")));
            assertEquals("after", Files.readString(project.resolve("a.txt")));

            assertTrue(manager.cancel(task.id()));
            String result = manager.compensate(task.id());

            assertTrue(result.startsWith("✅ 已补偿后台任务"));
            assertEquals("before", Files.readString(project.resolve("a.txt")));
            assertEquals(TaskStatus.COMPENSATED, manager.find(task.id()).orElseThrow().status());
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

    @SuppressWarnings("unchecked")
    private static void waitUntilNoLocalWorker(DurableTaskManager manager, String id) throws Exception {
        var field = DurableTaskManager.class.getDeclaredField("runningTasks");
        field.setAccessible(true);
        Map<String, Thread> runningTasks = (Map<String, Thread>) field.get(manager);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (!runningTasks.containsKey(id)) {
                return;
            }
            Thread.sleep(20);
        }
        fail("local worker did not release task " + id);
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

    private static void markRunningWithLease(DurableTaskManager manager, String id, String ownerId, Instant leaseUntil) throws Exception {
        var field = DurableTaskManager.class.getDeclaredField("connection");
        field.setAccessible(true);
        java.sql.Connection connection = (java.sql.Connection) field.get(manager);
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "UPDATE runtime_tasks SET status = 'running', owner_id = ?, lease_until = ? WHERE id = ?")) {
            ps.setString(1, ownerId);
            ps.setString(2, leaseUntil.toString());
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }
}
