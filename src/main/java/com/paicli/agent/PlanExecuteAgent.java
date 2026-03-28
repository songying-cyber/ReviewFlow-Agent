package com.paicli.agent;

import com.paicli.llm.GLMClient;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.*;
import com.paicli.util.AnsiStyle;
import com.paicli.tool.ToolRegistry;
import com.paicli.util.TerminalMarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent - 先规划后执行
 */
public class PlanExecuteAgent {
    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);
    private record PlanRunOutcome(String result, boolean persistAssistantMessage) {
        static PlanRunOutcome executed(String result) {
            return new PlanRunOutcome(result, true);
        }

        static PlanRunOutcome canceled(String result) {
            return new PlanRunOutcome(result, false);
        }

        static PlanRunOutcome failed(String result) {
            return new PlanRunOutcome(result, true);
        }
    }

    private record TaskRunResult(String result, boolean streamedOutput) {
        static TaskRunResult of(String result, boolean streamedOutput) {
            return new TaskRunResult(result, streamedOutput);
        }
    }

    private record TaskExecutionResult(Task task, String result, boolean streamedOutput, Exception error) {
        static TaskExecutionResult success(Task task, TaskRunResult taskRunResult) {
            return new TaskExecutionResult(task, taskRunResult.result(), taskRunResult.streamedOutput(), null);
        }

        static TaskExecutionResult failure(Task task, Exception error) {
            return new TaskExecutionResult(task, null, false, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    public enum PlanReviewAction {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() {
            return new PlanReviewDecision(PlanReviewAction.EXECUTE, null);
        }

        public static PlanReviewDecision supplement(String feedback) {
            return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback);
        }

        public static PlanReviewDecision cancel() {
            return new PlanReviewDecision(PlanReviewAction.CANCEL, null);
        }
    }

    private final GLMClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final PlanReviewHandler reviewHandler;
    private final MemoryManager memoryManager;

    // 执行提示词
    private static final String EXECUTION_PROMPT = """
            你是一个任务执行专家。请根据当前任务和上下文，选择合适的工具或生成回复。

            当前任务类型：%s
            任务描述：%s

            可用工具：
            1. read_file - 读取文件内容，参数：{"path": "文件路径"}
            2. write_file - 写入文件内容，参数：{"path": "文件路径", "content": "内容"}
            3. list_dir - 列出目录内容，参数：{"path": "目录路径"}
            4. execute_command - 执行命令，参数：{"command": "命令"}
            5. create_project - 创建项目，参数：{"name": "名称", "type": "java|python|node"}
            6. search_code - 语义检索代码库，参数：{"query": "自然语言描述", "top_k": 5}

            如果任务涉及理解代码库（如分析代码结构、查找实现位置），请优先使用 search_code 工具。
            对于当前项目内的文件，请优先使用 read_file 或 list_dir，不要用 execute_command 扫描 /、~ 或整个文件系统。
            execute_command 只适合在当前项目目录执行短时命令。
            如果是ANALYSIS或VERIFICATION类型任务，请直接输出分析结果，不需要调用工具。

            请用中文回复。
            """;

    public PlanExecuteAgent(String apiKey) {
        this(apiKey, (goal, plan) -> PlanReviewDecision.execute());
    }

    public PlanExecuteAgent(String apiKey, PlanReviewHandler reviewHandler) {
        this(new GLMClient(apiKey), new ToolRegistry(), null, null, reviewHandler);
    }

    public PlanExecuteAgent(String apiKey, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(new GLMClient(apiKey), toolRegistry, null, memoryManager, reviewHandler);
    }

    PlanExecuteAgent(GLMClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry != null ? toolRegistry : new ToolRegistry();
        this.planner = planner != null ? planner : new Planner(llmClient);
        this.reviewHandler = reviewHandler == null ? (goal, plan) -> PlanReviewDecision.execute() : reviewHandler;
        this.memoryManager = memoryManager != null ? memoryManager : new MemoryManager(llmClient);
    }

    /**
     * 运行任务（自动判断是否需要规划）
     */
    public String run(String userInput) {
        log.info("Plan run started: inputLength={}", userInput == null ? 0 : userInput.length());
        memoryManager.addUserMessage(userInput);
        StreamState streamState = new StreamState();
        try {
            PlanRunOutcome outcome = runWithPlan(userInput, streamState);
            if (outcome.persistAssistantMessage() && outcome.result() != null && !outcome.result().isBlank()) {
                memoryManager.addAssistantMessage("[计划结果] " + outcome.result());
            }
            if (streamState.hasStreamedOutput() && (outcome.result() == null || outcome.result().isBlank())) {
                return "";
            }
            return outcome.result();
        } catch (Exception e) {
            log.error("Plan run failed", e);
            String errorMessage = "❌ 执行失败: " + e.getMessage();
            memoryManager.addAssistantMessage(errorMessage);
            return errorMessage;
        }
    }

/**
     * 使用Plan-and-Execute模式执行
     */
    private PlanRunOutcome runWithPlan(String goal, StreamState streamState) throws IOException {
        ExecutionPlan plan = planner.createPlan(goal);
        return reviewAndExecutePlan(plan, streamState);
    }

    private PlanRunOutcome reviewAndExecutePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        while (true) {
            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
            if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                return PlanRunOutcome.executed(executePlan(plan, streamState));
            }

            if (decision.action() == PlanReviewAction.CANCEL) {
                return PlanRunOutcome.canceled("⏹️ 已取消本次计划执行。");
            }

            String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
            if (feedback.isEmpty()) {
                return PlanRunOutcome.executed(executePlan(plan, streamState));
            }

            System.out.println("📝 已收到补充要求，正在重新规划...\n");
            plan = planner.createPlan(plan.getGoal() + "\n补充要求：" + feedback);
        }
    }

    private String executePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        log.info("Executing plan: goal='{}', taskCount={}", plan.getGoal(), plan.getAllTasks().size());
        System.out.println("🚀 开始执行计划...\n");

        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();
        Map<String, Boolean> streamedTaskOutputs = new HashMap<>();

        while (true) {
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break;
            }

            List<TaskExecutionResult> batchResults = executeTaskBatch(plan, executableTasks, streamState);
            for (TaskExecutionResult batchResult : batchResults) {
                Task task = batchResult.task();

                if (!batchResult.failed()) {
                    task.markCompleted(batchResult.result());
                    streamedTaskOutputs.put(task.getId(), batchResult.streamedOutput());
                    log.info("Task completed: {} status={} resultChars={}",
                            task.getId(), task.getStatus(), batchResult.result() == null ? 0 : batchResult.result().length());
                    if (batchResult.streamedOutput() || batchResult.result() == null || batchResult.result().isBlank()) {
                        System.out.println("✅ 完成 [" + task.getId() + "]\n");
                    } else {
                        System.out.println("✅ 完成 [" + task.getId() + "]: "
                                + batchResult.result().substring(0, Math.min(100, batchResult.result().length())) + "\n");
                    }
                    continue;
                }

                Exception error = batchResult.error();
                task.markFailed(error.getMessage());
                log.warn("Task failed: {} error={}", task.getId(), error.getMessage());
                System.out.println("❌ 失败 [" + task.getId() + "]: " + error.getMessage() + "\n");

                if (plan.getProgress() < 0.5) {
                    System.out.println("🔄 尝试重新规划...\n");
                    ExecutionPlan replanned = planner.replan(plan, error.getMessage());
                    return reviewAndExecutePlan(replanned, streamState).result();
                }

                if (!finalResult.isEmpty()) {
                    finalResult.append("\n");
                }
                finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(error.getMessage());
            }
        }

        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            return "⚠️ 计划未能继续推进，存在未满足依赖的任务。";
        }

        String planSummary = finalResult.isEmpty()
                ? buildFinalResult(plan, streamedTaskOutputs)
                : finalResult.toString();

        if (plan.hasFailed()) {
            plan.markFailed();
            if (planSummary.isBlank()) {
                return "⚠️ 计划部分完成，有任务失败。";
            }
            return "⚠️ 计划部分完成，有任务失败。\n" + planSummary;
        }

        plan.markCompleted();
        if (planSummary.isBlank()) {
            return "✅ 计划执行完成！";
        }
        return "✅ 计划执行完成！\n" + planSummary;
    }

    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    private List<TaskExecutionResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks,
                                                       StreamState streamState) {
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            log.info("Executing single task: {} type={}", task.getId(), task.getType());
            System.out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
            task.markStarted();

            try {
                return List.of(TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, streamState)));
            } catch (Exception e) {
                return List.of(TaskExecutionResult.failure(task, e));
            }
        }

        String parallelTaskIds = executableTasks.stream()
                .map(Task::getId)
                .collect(Collectors.joining(", "));
        log.info("Executing parallel batch: {}", parallelTaskIds);
        System.out.println("⚡ 本轮并行执行 " + executableTasks.size() + " 个任务: " + parallelTaskIds);

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(executableTasks.size(), 4));
        try {
            List<Future<TaskExecutionResult>> futures = new ArrayList<>();
            for (Task task : executableTasks) {
                System.out.println("▶️ 并行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();
                futures.add(executor.submit(() -> {
                    try {
                        return TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, streamState));
                    } catch (Exception e) {
                        return TaskExecutionResult.failure(task, e);
                    }
                }));
            }

            List<TaskExecutionResult> results = new ArrayList<>();
            for (Future<TaskExecutionResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), e));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    Exception error = cause instanceof Exception exception
                            ? exception
                            : new RuntimeException(cause);
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), error));
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static final int MAX_TASK_ITERATIONS = 5;

    /**
     * 执行单个任务（支持多轮工具调用）
     */
    private TaskRunResult executeTask(String goal, ExecutionPlan plan, Task task, StreamState streamState) throws IOException {
        String prompt = String.format(EXECUTION_PROMPT,
                task.getType(), task.getDescription());

        // 注入长期记忆上下文
        String memoryContext = memoryManager.buildContextForQuery(task.getDescription(), 300);
        String taskInput = buildTaskContext(goal, plan, task);
        if (!memoryContext.isEmpty()) {
            taskInput = taskInput + "\n\n" + memoryContext;
        }

        List<GLMClient.Message> messages = new ArrayList<>(Arrays.asList(
                GLMClient.Message.system(prompt),
                GLMClient.Message.user(taskInput)
        ));

        StringBuilder allResults = new StringBuilder();
        int iteration = 0;
        TaskStreamRenderer streamRenderer = new TaskStreamRenderer(task.getId(), streamState);

        while (iteration < MAX_TASK_ITERATIONS) {
            iteration++;

            GLMClient.ChatResponse response = llmClient.chat(
                    messages,
                    toolRegistry.getToolDefinitions(),
                    streamRenderer
            );
            log.info("Task {} iteration {} response: toolCalls={}, reasoningChars={}, contentChars={}",
                    task.getId(),
                    iteration,
                    response.toolCalls() == null ? 0 : response.toolCalls().size(),
                    response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                    response.content() == null ? 0 : response.content().length());

            if (!response.hasToolCalls()) {
                // 没有工具调用，返回最终结果
                memoryManager.recordTokenUsage(response.inputTokens(), response.outputTokens());
                if (!allResults.isEmpty() && (response.content() == null || response.content().isBlank())) {
                    String toolOnlyResult = allResults.toString().trim();
                    if (!toolOnlyResult.isBlank()) {
                        memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "] " + toolOnlyResult);
                    }
                    streamRenderer.finish();
                    return TaskRunResult.of(toolOnlyResult, streamRenderer.hasStreamedOutput());
                }
                if (response.content() != null && !response.content().isBlank()) {
                    memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "] " + response.content());
                }
                streamRenderer.finish();
                return TaskRunResult.of(response.content(), streamRenderer.hasStreamedOutput());
            }

            // 有工具调用：执行工具并将结果回灌到消息历史
            messages.add(GLMClient.Message.assistant(
                    response.reasoningContent(),
                    response.content(),
                    response.toolCalls()
            ));

            // 在工具执行前 flush 并重置流式渲染器：避免 Markdown renderer pending 文本
            // 被 HITL 提示"跨过"导致 🧠 / 🤖 标题与内容错位
            streamRenderer.resetBetweenIterations();

            for (GLMClient.ToolCall toolCall : response.toolCalls()) {
                String toolName = toolCall.function().name();
                String toolArgs = toolCall.function().arguments();
                log.info("Task {} calling tool {}", task.getId(), toolName);
                log.debug("Task {} tool args [{}]: {}", task.getId(), toolName, toolArgs);

                String toolResult = toolRegistry.executeTool(toolName, toolArgs);
                log.debug("Task {} tool result preview [{}]: {}", task.getId(), toolName, preview(toolResult, 300));
                memoryManager.addToolResult(toolName, toolResult);
                allResults.append(toolResult).append("\n");
                messages.add(GLMClient.Message.tool(toolCall.id(), toolResult));
            }
        }

        String fallbackResult = allResults.toString().trim();
        if (!fallbackResult.isBlank()) {
            memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "] " + fallbackResult);
        }
        streamRenderer.finish();
        return TaskRunResult.of(fallbackResult, streamRenderer.hasStreamedOutput());
    }

    private String preview(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private static final class StreamState {
        private volatile boolean streamedOutput;

        private void markStreamed() {
            this.streamedOutput = true;
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }
    }

    private static final class TaskStreamRenderer implements GLMClient.StreamListener {
        private final String taskId;
        private final StreamState streamState;
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean streamedOutput;

        private TaskStreamRenderer(String taskId, StreamState streamState) {
            this.taskId = taskId;
            this.streamState = streamState;
        }

        @Override
        public synchronized void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!reasoningStarted) {
                System.out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                reasoningRenderer = new TerminalMarkdownRenderer(System.out);
                reasoningStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            }
            reasoningRenderer.append(delta);
            System.out.flush();
        }

        @Override
        public synchronized void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (!reasoningStarted) {
                    System.out.println(AnsiStyle.section("🤖 任务结果 [" + taskId + "]"));
                } else {
                    System.out.println();
                    System.out.println(AnsiStyle.section("🤖 任务结果 [" + taskId + "]"));
                }
                contentRenderer = new TerminalMarkdownRenderer(System.out);
                contentStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            }
            contentRenderer.append(delta);
            System.out.flush();
        }

        private synchronized void finish() {
            if (streamedOutput) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                if (contentRenderer != null) {
                    contentRenderer.finish();
                }
                System.out.println("\n");
            }
        }

        /**
         * 两次 iteration 之间（通常是一次 tool-call 分支完成后）调用：收尾当前渲染器并重置状态，
         * 让下一轮迭代能重新打印 🧠 / 🤖 标题，避免标题和内容被 HITL / 工具执行中断而错位。
         */
        private synchronized void resetBetweenIterations() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            reasoningStarted = false;
            contentStarted = false;
            if (streamedOutput) {
                System.out.println();
            }
        }

        private synchronized boolean hasStreamedOutput() {
            return streamedOutput;
        }
    }

    private String buildTaskContext(String goal, ExecutionPlan plan, Task task) {
        StringBuilder context = new StringBuilder();
        context.append("总目标：").append(goal).append("\n");
        context.append("当前任务：").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务：无\n");
        } else {
            context.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep == null) {
                    continue;
                }
                context.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getStatus())
                        .append("\n");
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    context.append(dep.getResult()).append("\n");
                }
            }
        }

        context.append("请执行此任务。如果是ANALYSIS或VERIFICATION类型，请基于以上上下文直接给出结果。");
        return context.toString();
    }

    private String buildFinalResult(ExecutionPlan plan, Map<String, Boolean> streamedTaskOutputs) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
            if (Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId()))) {
                continue;
            }
            if (task.getResult() == null || task.getResult().isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append("[").append(task.getId()).append("] ").append(task.getResult());
        }

        if (!result.isEmpty()) {
            return result.toString();
        }

        return plan.getAllTasks().stream()
                .filter(task -> !Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId())))
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
    }

}
