package com.paicli.tui;

import com.paicli.agent.Agent;
import com.paicli.agent.AgentOrchestrator;
import com.paicli.agent.PlanExecuteAgent;
import com.paicli.config.PaiCliConfig;
import com.paicli.hitl.HitlHandler;
import com.paicli.llm.LlmClient;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.snapshot.RestoreResult;
import com.paicli.snapshot.SnapshotService;
import com.paicli.snapshot.TurnSnapshot;
import com.paicli.tui.history.ConversationSnapshot;
import com.paicli.tui.pane.CenterPane;
import com.paicli.tui.pane.StatusPane;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Bridges TUI input to the existing Agent runtime.
 */
public final class TuiSessionController implements AutoCloseable {

    private static final Object STDOUT_CAPTURE_LOCK = new Object();

    private final PaiCliConfig config;
    private final LlmClient llmClient;
    private final Agent reactAgent;
    private final HitlHandler hitlHandler;
    private final CenterPane centerPane;
    private final StatusPane statusPane;
    private final Runnable closeWindow;
    private final Runnable showConfigPanel;
    private final Consumer<Runnable> uiExecutor;
    private final ExecutorService executor;
    private final ConversationSnapshot history;

    private volatile Future<?> currentTask;
    private volatile CancellationToken currentToken;

    public TuiSessionController(PaiCliConfig config,
                                LlmClient llmClient,
                                Agent reactAgent,
                                HitlHandler hitlHandler,
                                CenterPane centerPane,
                                StatusPane statusPane,
                                Runnable closeWindow,
                                Runnable showConfigPanel,
                                Consumer<Runnable> uiExecutor) {
        this.config = Objects.requireNonNull(config);
        this.llmClient = Objects.requireNonNull(llmClient);
        this.reactAgent = Objects.requireNonNull(reactAgent);
        this.hitlHandler = Objects.requireNonNull(hitlHandler);
        this.centerPane = Objects.requireNonNull(centerPane);
        this.statusPane = Objects.requireNonNull(statusPane);
        this.closeWindow = Objects.requireNonNull(closeWindow);
        this.showConfigPanel = Objects.requireNonNull(showConfigPanel);
        this.uiExecutor = Objects.requireNonNull(uiExecutor);
        this.history = new ConversationSnapshot(ConversationSnapshot.generateSessionId());
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-tui-agent-runner");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void submit(String rawInput) {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isEmpty()) {
            return;
        }

        if (handleCommand(input)) {
            return;
        }

        if (isTaskRunning()) {
            appendSystem("当前任务仍在运行，请等待完成或输入 /cancel。");
            return;
        }

        appendUser(input);
        currentTask = executor.submit(() -> runAgentTask(input, RunMode.REACT));
    }

    private boolean handleCommand(String input) {
        String lower = input.toLowerCase();
        if ("/exit".equals(lower) || "/quit".equals(lower) || "exit".equals(lower) || "quit".equals(lower)) {
            closeWindow.run();
            return true;
        }
        if ("/cancel".equals(lower) || "cancel".equals(lower)) {
            CancellationToken token = currentToken;
            Future<?> task = currentTask;
            if (token != null && task != null && !task.isDone()) {
                token.cancel();
                task.cancel(true);
                appendSystem("已请求取消当前任务。");
            } else {
                appendSystem("当前没有正在运行的任务。");
            }
            return true;
        }
        if ("/clear".equals(lower) || "clear".equals(lower)) {
            reactAgent.clearHistory();
            hitlHandler.clearApprovedAll();
            ui(() -> centerPane.clear());
            appendSystem("当前对话历史已清空，长期记忆保持不变。");
            return true;
        }
        if ("/context".equals(lower) || "/ctx".equals(lower)) {
            appendSystem(reactAgent.getContextStatus());
            return true;
        }
        if ("/memory".equals(lower) || "/mem".equals(lower)) {
            appendSystem(reactAgent.getMemoryManager().getSystemStatus()
                    + "\n/memory clear - 清空长期记忆\n/save <事实> - 手动保存到长期记忆");
            return true;
        }
        if ("/memory clear".equals(lower) || "/mem clear".equals(lower)) {
            reactAgent.getMemoryManager().clearLongTerm();
            appendSystem("长期记忆已清空。");
            return true;
        }
        if (lower.startsWith("/save ")) {
            String fact = input.substring(6).trim();
            if (fact.isEmpty()) {
                appendSystem("请提供要保存的内容，例如 /save 这个项目使用 Java 17");
            } else {
                reactAgent.getMemoryManager().storeFact(fact);
                appendSystem("已保存到长期记忆: " + fact);
            }
            return true;
        }
        if ("/hitl on".equals(lower)) {
            hitlHandler.setEnabled(true);
            appendSystem("HITL 审批已启用。");
            return true;
        }
        if ("/hitl off".equals(lower)) {
            hitlHandler.setEnabled(false);
            hitlHandler.clearApprovedAll();
            appendSystem("HITL 审批已关闭。");
            return true;
        }
        if ("/hitl".equals(lower)) {
            appendSystem("HITL 当前状态: " + (hitlHandler.isEnabled() ? "启用" : "关闭"));
            return true;
        }
        if ("/snapshot".equals(lower)) {
            appendSystem(formatSnapshots());
            return true;
        }
        if ("/snapshot status".equals(lower)) {
            appendSystem(reactAgent.getToolRegistry().getSnapshotService().status());
            return true;
        }
        if ("/snapshot clean".equals(lower)) {
            appendSystem(reactAgent.getToolRegistry().getSnapshotService().clean());
            return true;
        }
        if (lower.startsWith("/restore ")) {
            appendSystem(restoreSnapshot(input.substring(9).trim()));
            return true;
        }
        if ("/config".equals(lower)) {
            ui(showConfigPanel);
            return true;
        }
        if (lower.startsWith("/plan ")) {
            String task = input.substring(6).trim();
            if (task.isEmpty()) {
                appendSystem("请提供计划任务，例如 /plan 重构这个类。");
                return true;
            }
            if (isTaskRunning()) {
                appendSystem("当前任务仍在运行，请等待完成或输入 /cancel。");
                return true;
            }
            appendUser(input);
            currentTask = executor.submit(() -> runAgentTask(task, RunMode.PLAN));
            return true;
        }
        if (lower.startsWith("/team ")) {
            String task = input.substring(6).trim();
            if (task.isEmpty()) {
                appendSystem("请提供协作任务，例如 /team 检查并修复测试。");
                return true;
            }
            if (isTaskRunning()) {
                appendSystem("当前任务仍在运行，请等待完成或输入 /cancel。");
                return true;
            }
            appendUser(input);
            currentTask = executor.submit(() -> runAgentTask(task, RunMode.TEAM));
            return true;
        }
        if (input.startsWith("/")) {
            appendSystem("""
                    TUI 当前支持命令：
                    /clear, /context, /memory, /memory clear, /save <事实>
                    /hitl, /hitl on, /hitl off
                    /snapshot, /snapshot status, /snapshot clean, /restore <N>
                    /config, /plan <任务>, /team <任务>, /cancel, /exit
                    其余管理命令请暂时在默认 CLI 模式执行。
                    """);
            return true;
        }
        return false;
    }

    private void runAgentTask(String input, RunMode mode) {
        CancellationToken token = CancellationContext.startRun();
        currentToken = token;
        statusPane.startTimer();
        statusPane.updateMode(mode.label);
        appendSystem(mode.label + " 运行中...");
        String output;
        try {
            SnapshotService snapshots = reactAgent.getToolRegistry().getSnapshotService();
            output = captureStdout(() -> snapshots.runTurn(mode.name().toLowerCase(), input, () -> switch (mode) {
                    case REACT -> reactAgent.run(input);
                    case PLAN -> new PlanExecuteAgent(
                            llmClient,
                            reactAgent.getToolRegistry(),
                            reactAgent.getMemoryManager(),
                            (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
                    ).run(input);
                    case TEAM -> new AgentOrchestrator(
                            llmClient,
                            reactAgent.getToolRegistry(),
                            reactAgent.getMemoryManager()
                    ).run(input);
                }));
        } catch (Exception e) {
            output = "执行失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            CancellationContext.clear(token);
            currentToken = null;
            ui(() -> {
                statusPane.stopTimer();
                statusPane.updateMode("ReAct");
            });
        }
        appendAssistant(cleanOutput(output));
    }

    private String formatSnapshots() {
        try {
            List<TurnSnapshot> snapshots = reactAgent.getToolRegistry().getSnapshotService().listSnapshots(20);
            if (snapshots.isEmpty()) {
                return "暂无 Side-Git 快照。";
            }
            StringBuilder sb = new StringBuilder("最近 Side-Git 快照：\n");
            int preTurnIndex = 0;
            for (TurnSnapshot snapshot : snapshots) {
                String restoreHint = "";
                if ("pre-turn".equals(snapshot.phase().label())) {
                    preTurnIndex++;
                    restoreHint = " /restore " + preTurnIndex;
                }
                sb.append(snapshot.shortCommitId())
                        .append(' ')
                        .append(snapshot.phase().label())
                        .append(' ')
                        .append(snapshot.turnId())
                        .append(' ')
                        .append(snapshot.createdAt())
                        .append(restoreHint)
                        .append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "读取快照失败: " + e.getMessage();
        }
    }

    private String restoreSnapshot(String payload) {
        int offset = 1;
        if (payload != null && !payload.isBlank()) {
            try {
                offset = Integer.parseInt(payload.trim());
            } catch (NumberFormatException ignored) {
                offset = 1;
            }
        }
        try {
            RestoreResult result = reactAgent.getToolRegistry().getSnapshotService().restorePreTurn(offset);
            return result.formatForCli();
        } catch (Exception e) {
            return "恢复快照失败: " + e.getMessage();
        }
    }

    private static String captureStdout(ThrowingSupplier<String> supplier) throws Exception {
        synchronized (STDOUT_CAPTURE_LOCK) {
            PrintStream originalOut = System.out;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
                System.setOut(capture);
                String response = supplier.get();
                capture.flush();
                String streamed = buffer.toString(StandardCharsets.UTF_8);
                if (response != null && !response.isBlank()) {
                    return streamed + (streamed.endsWith("\n") || streamed.isBlank() ? "" : "\n") + response;
                }
                return streamed;
            } finally {
                System.setOut(originalOut);
            }
        }
    }

    private boolean isTaskRunning() {
        Future<?> task = currentTask;
        return task != null && !task.isDone();
    }

    private void appendUser(String message) {
        saveHistory("user", message);
        ui(() -> centerPane.onUserMessage(message));
    }

    private void appendSystem(String message) {
        saveHistory("system", message);
        ui(() -> centerPane.appendSystemMessage(cleanOutput(message)));
    }

    private void appendAssistant(String message) {
        saveHistory("assistant", message);
        ui(() -> centerPane.appendAssistantOutput(message == null || message.isBlank()
                ? "任务已完成，无额外输出。"
                : message));
    }

    private void saveHistory(String role, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        try {
            history.append(ConversationSnapshot.MessageRecord.of(role, cleanOutput(message)));
            history.save();
        } catch (Exception e) {
            ui(() -> centerPane.appendSystemMessage("对话历史保存失败: " + e.getMessage()));
        }
    }

    private void ui(Runnable task) {
        uiExecutor.accept(task);
    }

    private static String cleanOutput(String output) {
        if (output == null) {
            return "";
        }
        return output.replaceAll("\\u001B\\[[;\\d]*m", "").trim();
    }

    @Override
    public void close() {
        Future<?> task = currentTask;
        if (task != null && !task.isDone()) {
            CancellationToken token = currentToken;
            if (token != null) {
                token.cancel();
            }
            task.cancel(true);
        }
        executor.shutdownNow();
    }

    private enum RunMode {
        REACT("ReAct"),
        PLAN("Plan"),
        TEAM("Team");

        private final String label;

        RunMode(String label) {
            this.label = label;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
