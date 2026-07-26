package com.paicli.runtime.task;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.snapshot.RestoreResult;
import com.paicli.snapshot.SideGitManager;

public class DurableTaskManager implements Closeable, DurableToolExecutionStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WORKFLOW_VERSION = "durable-workflow-v1";
    private static final Duration LEASE_DURATION = Duration.ofMinutes(15);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);
    private static final Duration SCHEDULER_INTERVAL = Duration.ofSeconds(2);
    private static final Duration QUEUE_POLL_TIMEOUT = Duration.ofMillis(500);

    private final Path dbPath;
    private final TaskRunner runner;
    private final int workerCount;
    private final TaskQueue taskQueue;
    private final String ownerId = "worker_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final Connection connection;
    private final Map<String, Thread> runningTasks = new ConcurrentHashMap<>();
    private final Map<String, CancellationToken> runningTokens = new ConcurrentHashMap<>();
    private ExecutorService workers;
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledExecutorService schedulerExecutor;
    private volatile boolean running;

    public DurableTaskManager(Path dbPath, TaskRunner runner, int workerCount) throws SQLException {
        this(dbPath, runner, workerCount, new LocalTaskQueue());
    }

    public DurableTaskManager(Path dbPath, TaskRunner runner, int workerCount, TaskQueue taskQueue) throws SQLException {
        this.dbPath = dbPath;
        this.runner = runner;
        this.workerCount = Math.max(1, workerCount);
        this.taskQueue = taskQueue == null ? new LocalTaskQueue() : taskQueue;
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (Exception e) {
            throw new SQLException("无法创建任务数据库目录: " + e.getMessage(), e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        configureConnection();
        initTables();
        recoverRunningTasks();
    }

    public static DurableTaskManager openDefault(TaskRunner runner) throws SQLException {
        return new DurableTaskManager(defaultDbPath(), runner, workerCount(), TaskQueueFactory.createDefault());
    }

    public static Path defaultDbPath() {
        String configured = System.getProperty("paicli.task.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PAICLI_TASK_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = Path.of(System.getProperty("user.home"), ".paicli", "tasks").toString();
        }
        return Path.of(configured).resolve("tasks.db");
    }

    private static int workerCount() {
        String configured = System.getProperty("paicli.task.workers");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PAICLI_TASK_WORKERS");
        }
        if (configured == null || configured.isBlank()) {
            return 2;
        }
        try {
            return Math.max(1, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        schedulerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "paicli-task-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        schedulerExecutor.scheduleAtFixedRate(
                this::dispatchRunnableTasks,
                0,
                SCHEDULER_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
        workers = Executors.newFixedThreadPool(workerCount, r -> {
            Thread thread = new Thread(r, "paicli-task-worker");
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < workerCount; i++) {
            workers.submit(this::workerLoop);
        }
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "paicli-task-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatExecutor.scheduleAtFixedRate(
                this::heartbeatOwnedTasks,
                HEARTBEAT_INTERVAL.toMillis(),
                HEARTBEAT_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public synchronized DurableTask enqueue(String prompt) {
        return enqueue(prompt, Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString());
    }

    public synchronized DurableTask enqueue(String prompt, String projectPath) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("任务内容不能为空");
        }
        String id = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO runtime_tasks (
                    id, status, prompt, project_path, current_node_id, workflow_version,
                    attempt, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, id);
            ps.setString(2, TaskStatus.ENQUEUED.value());
            ps.setString(3, prompt.trim());
            ps.setString(4, projectPath == null || projectPath.isBlank()
                    ? Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString()
                    : projectPath);
            ps.setString(5, "react-loop");
            ps.setString(6, WORKFLOW_VERSION);
            ps.setInt(7, 0);
            ps.setString(8, now);
            ps.setString(9, now);
            ps.executeUpdate();
            checkpoint(id, "react-loop", "enqueued", "{\"status\":\"enqueued\"}");
            taskQueue.publish(id);
            notifyAll();
            return find(id).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("提交后台任务失败: " + e.getMessage(), e);
        }
    }

    public synchronized List<DurableTask> list(int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        List<DurableTask> tasks = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM runtime_tasks
                ORDER BY created_at DESC
                LIMIT ?
                """)) {
            ps.setInt(1, bounded);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tasks.add(fromRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取后台任务失败: " + e.getMessage(), e);
        }
        return tasks;
    }

    public synchronized Optional<DurableTask> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM runtime_tasks WHERE id = ?")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(fromRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取后台任务失败: " + e.getMessage(), e);
        }
    }

    public synchronized boolean cancel(String id) {
        Optional<DurableTask> current = find(id);
        if (current.isEmpty() || current.get().terminal()) {
            return false;
        }
        markStatus(id, TaskStatus.CANCEL_REQUESTED, current.get().startedAt());
        CancellationToken token = runningTokens.get(id);
        if (token != null) {
            token.cancel();
        }
        Thread thread = runningTasks.remove(id);
        if (thread != null) {
            thread.interrupt();
        } else {
            markTerminal(id, TaskStatus.CANCELED, current.get().result(), "用户取消", current.get().startedAt());
        }
        notifyAll();
        return true;
    }

    public synchronized boolean pause(String id) {
        Optional<DurableTask> current = find(id);
        if (current.isEmpty() || current.get().terminal()) {
            return false;
        }
        if (current.get().status() == TaskStatus.ENQUEUED) {
            markStatus(id, TaskStatus.PAUSED, current.get().startedAt());
        } else if (current.get().status() == TaskStatus.RUNNING) {
            markStatus(id, TaskStatus.PAUSE_REQUESTED, current.get().startedAt());
        }
        notifyAll();
        return true;
    }

    public synchronized boolean resume(String id) {
        Optional<DurableTask> current = find(id);
        if (current.isEmpty()) {
            return false;
        }
        TaskStatus status = current.get().status();
        if (status != TaskStatus.PAUSED && status != TaskStatus.PAUSE_REQUESTED) {
            return false;
        }
        resetForRun(id, false);
        taskQueue.publish(id);
        notifyAll();
        return true;
    }

    public synchronized boolean retry(String id) {
        Optional<DurableTask> current = find(id);
        if (current.isEmpty()) {
            return false;
        }
        TaskStatus status = current.get().status();
        if (status != TaskStatus.FAILED && status != TaskStatus.CANCELED && status != TaskStatus.COMPENSATED) {
            return false;
        }
        resetForRun(id, true);
        taskQueue.publish(id);
        notifyAll();
        return true;
    }

    public synchronized String compensate(String id) {
        Optional<DurableTask> current = find(id);
        if (current.isEmpty()) {
            return "❌ 未找到后台任务: " + id;
        }
        DurableTask task = current.get();
        if (task.status() == TaskStatus.COMPENSATED) {
            return "✅ 任务已补偿: " + id;
        }
        if (task.status() == TaskStatus.RUNNING || task.status() == TaskStatus.ENQUEUED) {
            return "❌ 任务仍在运行或排队，不能补偿: " + id;
        }
        markStatus(id, TaskStatus.COMPENSATING, task.startedAt());
        List<DurableToolExecution> executions = succeededReversibleTools(id);
        if (executions.isEmpty()) {
            markTerminal(id, TaskStatus.COMPENSATED, task.result(), "没有可自动补偿的工具副作用", task.startedAt());
            return "✅ 没有可自动补偿的工具副作用: " + id;
        }
        List<String> restored = new ArrayList<>();
        try {
            SideGitManager manager = new SideGitManager(Path.of(task.projectPath()));
            for (DurableToolExecution execution : executions) {
                String snapshot = beforeSnapshotFromCompensation(execution);
                if (snapshot == null || snapshot.isBlank()) {
                    continue;
                }
                RestoreResult result = manager.restoreCommit(snapshot);
                if (!result.success()) {
                    throw new IllegalStateException(result.message());
                }
                markToolCompensated(execution.id());
                restored.add(execution.toolName() + "@" + snapshot.substring(0, Math.min(8, snapshot.length())));
            }
            markTerminal(id, TaskStatus.COMPENSATED, task.result(), "已补偿: " + String.join(", ", restored), task.startedAt());
            return "✅ 已补偿后台任务 " + id + ": " + String.join(", ", restored);
        } catch (Exception e) {
            markTerminal(id, TaskStatus.FAILED, task.result(), "补偿失败: " + e.getMessage(), task.startedAt());
            return "❌ 补偿失败: " + e.getMessage();
        }
    }

    public Path dbPath() {
        return dbPath;
    }

    private void workerLoop() {
        while (running) {
            DurableTask task = null;
            try {
                Optional<String> nextTaskId = taskQueue.poll(QUEUE_POLL_TIMEOUT);
                if (nextTaskId.isEmpty()) {
                    continue;
                }
                task = claim(nextTaskId.get());
                if (task == null) {
                    continue;
                }
                String taskId = task.id();
                runningTasks.put(taskId, Thread.currentThread());
                Instant startedAt = Instant.now();
                CancellationToken token = CancellationContext.startRun();
                runningTokens.put(taskId, token);
                DurableRunContext.bind(new ManagerRunController(taskId));
                try {
                    String result = runner.run(task.prompt());
                    synchronized (this) {
                        DurableTask latest = find(taskId).orElse(null);
                        if (!ownsTask(taskId)) {
                            continue;
                        }
                        if (latest != null && (latest.status() == TaskStatus.CANCEL_REQUESTED || latest.status() == TaskStatus.CANCELED)) {
                            markTerminalIfOwned(taskId, TaskStatus.CANCELED, result, "用户取消", startedAt);
                        } else if (latest != null && latest.status() != TaskStatus.CANCELED) {
                            markTerminalIfOwned(taskId, TaskStatus.COMPLETED, result, null, startedAt);
                        }
                    }
                } catch (TaskPausedException e) {
                    synchronized (this) {
                        if (ownsTask(taskId)) {
                            markStatus(taskId, TaskStatus.PAUSED, startedAt);
                            checkpoint(taskId, "react-loop", "paused", "{\"status\":\"paused\"}");
                        }
                    }
                } catch (TaskLeaseLostException e) {
                    // Another instance reclaimed the lease. Stop this worker without writing terminal state.
                } catch (InterruptedException e) {
                    Thread.interrupted();
                    synchronized (this) {
                        if (ownsTask(taskId)) {
                            markTerminalIfOwned(taskId, TaskStatus.CANCELED, "", "任务线程被中断", startedAt);
                        }
                    }
                } catch (Exception e) {
                    synchronized (this) {
                        DurableTask latest = find(taskId).orElse(null);
                        if (latest != null && ownsTask(taskId) && latest.status() != TaskStatus.CANCELED) {
                            markTerminalIfOwned(taskId, TaskStatus.FAILED, "", e.getMessage(), startedAt);
                        }
                    }
                } finally {
                    DurableRunContext.clear();
                    CancellationContext.clear(token);
                    runningTasks.remove(taskId);
                    runningTokens.remove(taskId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // Worker loop must stay alive; individual failures are recorded on the task row when possible.
            }
        }
    }

    private synchronized void dispatchRunnableTasks() {
        if (!running) {
            return;
        }
        try {
            requeueExpiredLeases();
            for (String taskId : runnableTaskIds(workerCount * 4)) {
                taskQueue.publish(taskId);
            }
        } catch (Exception ignored) {
            // Scheduler wake-ups are best-effort; DB state remains the source of truth.
        }
    }

    private void configureConnection() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA busy_timeout = 5000");
            stmt.execute("PRAGMA journal_mode = WAL");
        }
    }

    private synchronized DurableTask claim(String taskId) throws SQLException {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        connection.setAutoCommit(false);
        try {
            requeueExpiredLeases();
            String now = Instant.now().toString();
            String leaseUntil = Instant.now().plus(LEASE_DURATION).toString();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE runtime_tasks
                    SET status = ?, started_at = ?, updated_at = ?, owner_id = ?, lease_until = ?
                    WHERE id = ? AND status = ?
                    """)) {
                update.setString(1, TaskStatus.RUNNING.value());
                update.setString(2, now);
                update.setString(3, now);
                update.setString(4, ownerId);
                update.setString(5, leaseUntil);
                update.setString(6, taskId.trim());
                update.setString(7, TaskStatus.ENQUEUED.value());
                if (update.executeUpdate() == 0) {
                    connection.rollback();
                    return null;
                }
            }
            connection.commit();
            return find(taskId.trim()).orElse(null);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private synchronized void markTerminal(String id, TaskStatus status, String result, String error, Instant startedAt) {
        String now = Instant.now().toString();
        long durationMs = startedAt == null ? 0 : Math.max(0, Instant.now().toEpochMilli() - startedAt.toEpochMilli());
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET status = ?, result = ?, error = ?, finished_at = ?, duration_ms = ?, updated_at = ?,
                    owner_id = NULL, lease_until = NULL
                WHERE id = ?
                """)) {
            ps.setString(1, status.value());
            ps.setString(2, result == null ? "" : result);
            ps.setString(3, error);
            ps.setString(4, now);
            ps.setLong(5, durationMs);
            ps.setString(6, now);
            ps.setString(7, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新后台任务失败: " + e.getMessage(), e);
        }
    }

    private synchronized boolean markTerminalIfOwned(String id, TaskStatus status, String result, String error, Instant startedAt) {
        String now = Instant.now().toString();
        long durationMs = startedAt == null ? 0 : Math.max(0, Instant.now().toEpochMilli() - startedAt.toEpochMilli());
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET status = ?, result = ?, error = ?, finished_at = ?, duration_ms = ?, updated_at = ?,
                    owner_id = NULL, lease_until = NULL
                WHERE id = ?
                  AND owner_id = ?
                  AND lease_until IS NOT NULL
                  AND lease_until > ?
                  AND status IN (?, ?, ?)
                """)) {
            ps.setString(1, status.value());
            ps.setString(2, result == null ? "" : result);
            ps.setString(3, error);
            ps.setString(4, now);
            ps.setLong(5, durationMs);
            ps.setString(6, now);
            ps.setString(7, id);
            ps.setString(8, ownerId);
            ps.setString(9, now);
            ps.setString(10, TaskStatus.RUNNING.value());
            ps.setString(11, TaskStatus.PAUSE_REQUESTED.value());
            ps.setString(12, TaskStatus.CANCEL_REQUESTED.value());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("更新后台任务失败: " + e.getMessage(), e);
        }
    }

    private synchronized void markStatus(String id, TaskStatus status, Instant startedAt) {
        String now = Instant.now().toString();
        boolean releaseLease = status == TaskStatus.PAUSED || status == TaskStatus.COMPENSATED;
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET status = ?, updated_at = ?,
                    owner_id = CASE WHEN ? THEN NULL ELSE owner_id END,
                    lease_until = CASE WHEN ? THEN NULL ELSE lease_until END
                WHERE id = ?
                """)) {
            ps.setString(1, status.value());
            ps.setString(2, now);
            ps.setBoolean(3, releaseLease);
            ps.setBoolean(4, releaseLease);
            ps.setString(5, id);
            ps.executeUpdate();
            checkpoint(id, "react-loop", status.value(), "{\"status\":\"" + status.value() + "\"}");
        } catch (SQLException e) {
            throw new IllegalStateException("更新后台任务状态失败: " + e.getMessage(), e);
        }
    }

    private synchronized void resetForRun(String id, boolean incrementAttempt) {
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET status = ?, result = '', error = NULL, started_at = NULL, finished_at = NULL,
                    updated_at = ?, owner_id = NULL, lease_until = NULL,
                    attempt = attempt + ?
                WHERE id = ?
                """)) {
            ps.setString(1, TaskStatus.ENQUEUED.value());
            ps.setString(2, now);
            ps.setInt(3, incrementAttempt ? 1 : 0);
            ps.setString(4, id);
            ps.executeUpdate();
            checkpoint(id, "react-loop", incrementAttempt ? "retry" : "resume",
                    "{\"status\":\"enqueued\"}");
        } catch (SQLException e) {
            throw new IllegalStateException("重置后台任务失败: " + e.getMessage(), e);
        }
    }

    private void initTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_tasks (
                        id TEXT PRIMARY KEY,
                        status TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        result TEXT,
                        error TEXT,
                        created_at TEXT NOT NULL,
                        started_at TEXT,
                        finished_at TEXT,
                        updated_at TEXT,
                        duration_ms INTEGER DEFAULT 0
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_tasks_status ON runtime_tasks(status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_tasks_created ON runtime_tasks(created_at)");
            ensureColumn("runtime_tasks", "project_path", "TEXT");
            ensureColumn("runtime_tasks", "current_node_id", "TEXT DEFAULT 'react-loop'");
            ensureColumn("runtime_tasks", "workflow_version", "TEXT DEFAULT '" + WORKFLOW_VERSION + "'");
            ensureColumn("runtime_tasks", "attempt", "INTEGER DEFAULT 0");
            ensureColumn("runtime_tasks", "owner_id", "TEXT");
            ensureColumn("runtime_tasks", "lease_until", "TEXT");
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE runtime_tasks
                    SET project_path = COALESCE(project_path, ?),
                        current_node_id = COALESCE(current_node_id, 'react-loop'),
                        workflow_version = COALESCE(workflow_version, ?),
                        updated_at = COALESCE(updated_at, created_at)
                    """)) {
                ps.setString(1, Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString());
                ps.setString(2, WORKFLOW_VERSION);
                ps.executeUpdate();
            }
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_checkpoints (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        task_id TEXT NOT NULL,
                        node_id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        state_json TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_checkpoints_task ON runtime_checkpoints(task_id, id)");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_tool_executions (
                        id TEXT PRIMARY KEY,
                        workflow_id TEXT NOT NULL,
                        node_id TEXT NOT NULL,
                        tool_call_id TEXT,
                        idempotency_key TEXT NOT NULL UNIQUE,
                        tool_name TEXT NOT NULL,
                        args_json TEXT NOT NULL,
                        status TEXT NOT NULL,
                        result TEXT,
                        error TEXT,
                        side_effect_level TEXT,
                        before_snapshot_id TEXT,
                        after_snapshot_id TEXT,
                        before_hash_json TEXT,
                        after_hash_json TEXT,
                        compensation_json TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_tool_executions_workflow ON runtime_tool_executions(workflow_id, id)");
        }
    }

    private synchronized void recoverRunningTasks() throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET status = ?, owner_id = NULL, lease_until = NULL, updated_at = ?
                WHERE status IN (?, ?)
                  AND (lease_until IS NULL OR lease_until < ?)
                """)) {
            ps.setString(1, TaskStatus.ENQUEUED.value());
            ps.setString(2, now);
            ps.setString(3, TaskStatus.RUNNING.value());
            ps.setString(4, TaskStatus.COMPENSATING.value());
            ps.setString(5, now);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET status = ?, owner_id = NULL, lease_until = NULL, updated_at = ?
                WHERE status = ?
                """)) {
            ps.setString(1, TaskStatus.PAUSED.value());
            ps.setString(2, now);
            ps.setString(3, TaskStatus.PAUSE_REQUESTED.value());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET status = ?, owner_id = NULL, lease_until = NULL, updated_at = ?, finished_at = ?
                WHERE status = ?
                """)) {
            ps.setString(1, TaskStatus.CANCELED.value());
            ps.setString(2, now);
            ps.setString(3, now);
            ps.setString(4, TaskStatus.CANCEL_REQUESTED.value());
            ps.executeUpdate();
        }
        for (String taskId : runnableTaskIds(workerCount * 4)) {
            taskQueue.publish(taskId);
        }
    }

    private DurableTask fromRow(ResultSet rs) throws SQLException {
        return new DurableTask(
                rs.getString("id"),
                TaskStatus.from(rs.getString("status")),
                rs.getString("prompt"),
                rs.getString("result"),
                rs.getString("error"),
                rs.getString("project_path"),
                rs.getString("current_node_id"),
                rs.getString("workflow_version"),
                rs.getInt("attempt"),
                rs.getString("owner_id"),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("started_at")),
                parseInstant(rs.getString("finished_at")),
                parseInstant(rs.getString("updated_at")),
                parseInstant(rs.getString("lease_until")),
                rs.getLong("duration_ms")
        );
    }

    public synchronized void checkpoint(String taskId, String nodeId, String kind, String stateJson) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        String normalizedNodeId = nodeId == null || nodeId.isBlank() ? "react-loop" : nodeId;
        String normalizedKind = kind == null || kind.isBlank() ? "checkpoint" : kind;
        String normalizedStateJson = stateJson == null || stateJson.isBlank() ? "{}" : stateJson;
        String now = Instant.now().toString();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE runtime_tasks SET current_node_id = ?, updated_at = ?,
                        lease_until = CASE WHEN status = ? THEN ? ELSE lease_until END
                    WHERE id = ? AND (status <> ? OR owner_id = ?)
                    """)) {
                update.setString(1, normalizedNodeId);
                update.setString(2, now);
                update.setString(3, TaskStatus.RUNNING.value());
                update.setString(4, Instant.now().plus(LEASE_DURATION).toString());
                update.setString(5, taskId);
                update.setString(6, TaskStatus.RUNNING.value());
                update.setString(7, ownerId);
                if (update.executeUpdate() == 0) {
                    throw new TaskLeaseLostException("任务 lease 已转移: " + taskId);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO runtime_checkpoints (task_id, node_id, kind, state_json, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, taskId);
                ps.setString(2, normalizedNodeId);
                ps.setString(3, normalizedKind);
                ps.setString(4, normalizedStateJson);
                ps.setString(5, now);
                ps.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
            throw new IllegalStateException("写入任务 checkpoint 失败: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    @Override
    public synchronized Optional<DurableToolExecution> findToolExecutionById(String id) {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM runtime_tool_executions WHERE id = ?
                """)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toolExecutionFromRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取工具执行记录失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized Optional<DurableToolExecution> findToolExecutionByKey(String idempotencyKey) {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM runtime_tool_executions WHERE idempotency_key = ?
                """)) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toolExecutionFromRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取工具执行记录失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void recordToolRunning(DurableToolExecution execution) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR IGNORE INTO runtime_tool_executions (
                    id, workflow_id, node_id, tool_call_id, idempotency_key, tool_name, args_json,
                    status, result, error, side_effect_level, before_snapshot_id, after_snapshot_id,
                    before_hash_json, after_hash_json, compensation_json, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindToolExecution(ps, execution);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("记录工具执行开始失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void recordToolFinished(String idempotencyKey, String status, String result, String error,
                                                String beforeSnapshotId, String afterSnapshotId,
                                                String beforeHashJson, String afterHashJson,
                                                String compensationJson) {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tool_executions
                SET status = ?, result = ?, error = ?, before_snapshot_id = COALESCE(?, before_snapshot_id),
                    after_snapshot_id = ?, before_hash_json = COALESCE(?, before_hash_json),
                    after_hash_json = ?, compensation_json = ?, updated_at = ?
                WHERE idempotency_key = ?
                """)) {
            ps.setString(1, status);
            ps.setString(2, result == null ? "" : result);
            ps.setString(3, error);
            ps.setString(4, beforeSnapshotId);
            ps.setString(5, afterSnapshotId);
            ps.setString(6, beforeHashJson);
            ps.setString(7, afterHashJson);
            ps.setString(8, compensationJson);
            ps.setString(9, Instant.now().toString());
            ps.setString(10, idempotencyKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("记录工具执行完成失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<DurableToolExecution> succeededReversibleTools(String workflowId) {
        List<DurableToolExecution> executions = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM runtime_tool_executions
                WHERE workflow_id = ? AND status = 'succeeded' AND compensation_json IS NOT NULL
                ORDER BY created_at DESC
                """)) {
            ps.setString(1, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    executions.add(toolExecutionFromRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取可补偿工具失败: " + e.getMessage(), e);
        }
        return executions;
    }

    private void ensureColumn(String table, String column, String definition) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, column)) {
            if (rs.next()) {
                return;
            }
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private int requeueExpiredLeases() throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET status = ?, owner_id = NULL, lease_until = NULL, updated_at = ?
                WHERE status = ? AND lease_until IS NOT NULL AND lease_until < ?
                  AND (owner_id IS NULL OR owner_id <> ?)
                """)) {
            ps.setString(1, TaskStatus.ENQUEUED.value());
            ps.setString(2, now);
            ps.setString(3, TaskStatus.RUNNING.value());
            ps.setString(4, now);
            ps.setString(5, ownerId);
            return ps.executeUpdate();
        }
    }

    private List<String> runnableTaskIds(int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 100));
        List<String> taskIds = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id FROM runtime_tasks
                WHERE status = ?
                ORDER BY created_at ASC
                LIMIT ?
                """)) {
            ps.setString(1, TaskStatus.ENQUEUED.value());
            ps.setInt(2, bounded);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    taskIds.add(rs.getString("id"));
                }
            }
        }
        return taskIds;
    }

    private synchronized void heartbeatOwnedTasks() {
        if (!running || runningTasks.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET lease_until = ?, updated_at = ?
                WHERE status = ? AND owner_id = ?
                """)) {
            String now = Instant.now().toString();
            ps.setString(1, Instant.now().plus(LEASE_DURATION).toString());
            ps.setString(2, now);
            ps.setString(3, TaskStatus.RUNNING.value());
            ps.setString(4, ownerId);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    private synchronized boolean ownsTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT owner_id, lease_until, status FROM runtime_tasks WHERE id = ?
                """)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                String status = rs.getString("status");
                String owner = rs.getString("owner_id");
                Instant leaseUntil = parseInstant(rs.getString("lease_until"));
                if (!TaskStatus.RUNNING.value().equals(status)
                        && !TaskStatus.PAUSE_REQUESTED.value().equals(status)
                        && !TaskStatus.CANCEL_REQUESTED.value().equals(status)) {
                    return owner == null || ownerId.equals(owner);
                }
                return ownerId.equals(owner)
                        && leaseUntil != null
                        && leaseUntil.isAfter(Instant.now());
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private void bindToolExecution(PreparedStatement ps, DurableToolExecution execution) throws SQLException {
        ps.setString(1, execution.id());
        ps.setString(2, execution.workflowId());
        ps.setString(3, execution.nodeId());
        ps.setString(4, execution.toolCallId());
        ps.setString(5, execution.idempotencyKey());
        ps.setString(6, execution.toolName());
        ps.setString(7, execution.argsJson());
        ps.setString(8, execution.status());
        ps.setString(9, execution.result());
        ps.setString(10, execution.error());
        ps.setString(11, execution.sideEffectLevel());
        ps.setString(12, execution.beforeSnapshotId());
        ps.setString(13, execution.afterSnapshotId());
        ps.setString(14, execution.beforeHashJson());
        ps.setString(15, execution.afterHashJson());
        ps.setString(16, execution.compensationJson());
        ps.setString(17, execution.createdAt() == null ? Instant.now().toString() : execution.createdAt().toString());
        ps.setString(18, execution.updatedAt() == null ? Instant.now().toString() : execution.updatedAt().toString());
    }

    private DurableToolExecution toolExecutionFromRow(ResultSet rs) throws SQLException {
        return new DurableToolExecution(
                rs.getString("id"),
                rs.getString("workflow_id"),
                rs.getString("node_id"),
                rs.getString("tool_call_id"),
                rs.getString("idempotency_key"),
                rs.getString("tool_name"),
                rs.getString("args_json"),
                rs.getString("status"),
                rs.getString("result"),
                rs.getString("error"),
                rs.getString("side_effect_level"),
                rs.getString("before_snapshot_id"),
                rs.getString("after_snapshot_id"),
                rs.getString("before_hash_json"),
                rs.getString("after_hash_json"),
                rs.getString("compensation_json"),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("updated_at"))
        );
    }

    private String beforeSnapshotFromCompensation(DurableToolExecution execution) {
        if (execution.compensationJson() == null || execution.compensationJson().isBlank()) {
            return execution.beforeSnapshotId();
        }
        try {
            JsonNode node = MAPPER.readTree(execution.compensationJson());
            String snapshot = node.path("beforeSnapshotId").asText("");
            return snapshot.isBlank() ? execution.beforeSnapshotId() : snapshot;
        } catch (Exception e) {
            return execution.beforeSnapshotId();
        }
    }

    @Override
    public synchronized void markToolCompensated(String executionId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tool_executions SET status = 'compensated', updated_at = ? WHERE id = ?
                """)) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, executionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("标记工具补偿失败: " + e.getMessage(), e);
        }
    }

    private final class ManagerRunController implements DurableRunContext.Controller {
        private final String taskId;

        private ManagerRunController(String taskId) {
            this.taskId = taskId;
        }

        @Override
        public String workflowId() {
            return taskId;
        }

        @Override
        public String nodeId() {
            return "react-loop";
        }

        @Override
        public void checkpoint(String kind, String stateJson) {
            DurableTaskManager.this.checkpoint(taskId, "react-loop", kind, stateJson);
        }

        @Override
        public boolean pauseRequested() {
            return DurableTaskManager.this.find(taskId)
                    .map(task -> task.status() == TaskStatus.PAUSE_REQUESTED)
                    .orElse(false);
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }

    @Override
    public synchronized void close() {
        running = false;
        notifyAll();
        if (workers != null) {
            workers.shutdownNow();
            try {
                workers.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        if (schedulerExecutor != null) {
            schedulerExecutor.shutdownNow();
        }
        try {
            taskQueue.close();
        } catch (Exception ignored) {
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
