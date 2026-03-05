package com.paicli.agent;

import com.paicli.llm.GLMClient;
import com.paicli.plan.*;
import com.paicli.tool.ToolRegistry;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent - 先规划后执行
 */
public class PlanExecuteAgent {
    private record TaskExecutionResult(Task task, String result, Exception error) {
        static TaskExecutionResult success(Task task, String result) {
            return new TaskExecutionResult(task, result, null);
        }

        static TaskExecutionResult failure(Task task, Exception error) {
            return new TaskExecutionResult(task, null, error);
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

    // 执行提示词
    private static final String EXECUTION_PROMPT = """
            你是一个任务执行专家。请根据当前任务和上下文，选择合适的工具或生成回复。

            当前任务类型：%s
            任务描述：%s

            可用工具：
            1. read_file - 读取文件内容，参数：{"path": "文件路径"}
            2. write_file - 写入文件内容，参数：{"path": "文件路径", "content": "内容"}
            3. execute_command - 执行命令，参数：{"command": "命令"}
            4. create_project - 创建项目，参数：{"name": "名称", "type": "java|python|node"}

            如果是ANALYSIS或VERIFICATION类型任务，请直接输出分析结果，不需要调用工具。

            请用中文回复。
            """;

    public PlanExecuteAgent(String apiKey) {
        this(apiKey, (goal, plan) -> PlanReviewDecision.execute());
    }

    public PlanExecuteAgent(String apiKey, PlanReviewHandler reviewHandler) {
        this.llmClient = new GLMClient(apiKey);
        this.toolRegistry = new ToolRegistry();
        this.planner = new Planner(llmClient);
        this.reviewHandler = reviewHandler == null ? (goal, plan) -> PlanReviewDecision.execute() : reviewHandler;
    }

    /**
     * 运行任务（自动判断是否需要规划）
     */
    public String run(String userInput) {
        try {
            return runWithPlan(userInput);
        } catch (Exception e) {
            return "❌ 执行失败: " + e.getMessage();
        }
    }

/**
     * 使用Plan-and-Execute模式执行
     */
    private String runWithPlan(String goal) throws IOException {
        ExecutionPlan plan = planner.createPlan(goal);
        return reviewAndExecutePlan(plan);
    }

    private String reviewAndExecutePlan(ExecutionPlan plan) throws IOException {
        while (true) {
            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
            if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                return executePlan(plan);
            }

            if (decision.action() == PlanReviewAction.CANCEL) {
                return "⏹️ 已取消本次计划执行。";
            }

            String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
            if (feedback.isEmpty()) {
                return executePlan(plan);
            }

            System.out.println("📝 已收到补充要求，正在重新规划...\n");
            plan = planner.createPlan(plan.getGoal() + "\n补充要求：" + feedback);
        }
    }

    private String executePlan(ExecutionPlan plan) throws IOException {
        System.out.println("🚀 开始执行计划...\n");

        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();

        while (true) {
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break;
            }

            List<TaskExecutionResult> batchResults = executeTaskBatch(plan, executableTasks);
            for (TaskExecutionResult batchResult : batchResults) {
                Task task = batchResult.task();

                if (!batchResult.failed()) {
                    task.markCompleted(batchResult.result());
                    System.out.println("✅ 完成 [" + task.getId() + "]: "
                            + batchResult.result().substring(0, Math.min(100, batchResult.result().length())) + "\n");
                    continue;
                }

                Exception error = batchResult.error();
                task.markFailed(error.getMessage());
                System.out.println("❌ 失败 [" + task.getId() + "]: " + error.getMessage() + "\n");

                if (plan.getProgress() < 0.5) {
                    System.out.println("🔄 尝试重新规划...\n");
                    ExecutionPlan replanned = planner.replan(plan, error.getMessage());
                    return reviewAndExecutePlan(replanned);
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

        if (finalResult.isEmpty()) {
            finalResult.append(buildFinalResult(plan));
        }

        // 3. 完成
        if (plan.hasFailed()) {
            plan.markFailed();
            return "⚠️ 计划部分完成，有任务失败。\n" + finalResult;
        } else {
            plan.markCompleted();
            return "✅ 计划执行完成！\n" + finalResult;
        }
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

    private List<TaskExecutionResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks) {
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            System.out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
            task.markStarted();

            try {
                return List.of(TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task)));
            } catch (Exception e) {
                return List.of(TaskExecutionResult.failure(task, e));
            }
        }

        String parallelTaskIds = executableTasks.stream()
                .map(Task::getId)
                .collect(Collectors.joining(", "));
        System.out.println("⚡ 本轮并行执行 " + executableTasks.size() + " 个任务: " + parallelTaskIds);

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(executableTasks.size(), 4));
        try {
            List<Future<TaskExecutionResult>> futures = new ArrayList<>();
            for (Task task : executableTasks) {
                System.out.println("▶️ 并行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();
                futures.add(executor.submit(() -> {
                    try {
                        return TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task));
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
    private String executeTask(String goal, ExecutionPlan plan, Task task) throws IOException {
        String prompt = String.format(EXECUTION_PROMPT,
                task.getType(), task.getDescription());

        List<GLMClient.Message> messages = new ArrayList<>(Arrays.asList(
                GLMClient.Message.system(prompt),
                GLMClient.Message.user(buildTaskContext(goal, plan, task))
        ));

        StringBuilder allResults = new StringBuilder();
        int iteration = 0;

        while (iteration < MAX_TASK_ITERATIONS) {
            iteration++;

            GLMClient.ChatResponse response = llmClient.chat(
                    messages,
                    toolRegistry.getToolDefinitions()
            );

            if (!response.hasToolCalls()) {
                // 没有工具调用，返回最终结果
                if (!allResults.isEmpty() && (response.content() == null || response.content().isBlank())) {
                    return allResults.toString().trim();
                }
                return response.content();
            }

            // 有工具调用：执行工具并将结果回灌到消息历史
            messages.add(GLMClient.Message.assistant(response.content(), response.toolCalls()));

            for (GLMClient.ToolCall toolCall : response.toolCalls()) {
                String toolName = toolCall.function().name();
                String toolArgs = toolCall.function().arguments();

                System.out.println("   🔧 调用工具: " + toolName);

                String toolResult = toolRegistry.executeTool(toolName, toolArgs);
                allResults.append(toolResult).append("\n");
                messages.add(GLMClient.Message.tool(toolCall.id(), toolResult));
            }
        }

        return allResults.toString().trim();
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

    private String buildFinalResult(ExecutionPlan plan) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
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
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
    }

}
