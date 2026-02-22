package com.paicli.agent;

import com.paicli.llm.GLMClient;
import com.paicli.plan.*;
import com.paicli.tool.ToolRegistry;

import java.io.IOException;
import java.util.*;

/**
 * Plan-and-Execute Agent - 先规划后执行
 */
public class PlanExecuteAgent {
    private final GLMClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;

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
        this.llmClient = new GLMClient(apiKey);
        this.toolRegistry = new ToolRegistry();
        this.planner = new Planner(llmClient);
    }

    /**
     * 运行任务（自动判断是否需要规划）
     */
    public String run(String userInput) {
        try {
            // 判断是否需要复杂规划
            if (shouldPlan(userInput)) {
                return runWithPlan(userInput);
            } else {
                // 简单任务直接用ReAct
                return runSimple(userInput);
            }
        } catch (Exception e) {
            return "❌ 执行失败: " + e.getMessage();
        }
    }

    /**
     * 判断是否需要规划
     */
    private boolean shouldPlan(String input) {
        // 启发式判断：包含多个动作或复杂逻辑的任务需要规划
        String lower = input.toLowerCase();
        int actionCount = 0;
        String[] actionKeywords = {"创建", "写", "读", "执行", "编译", "运行", "修改", "删除", "然后", "接着", "再", "最后"};

        for (String keyword : actionKeywords) {
            if (lower.contains(keyword)) actionCount++;
        }

        return actionCount >= 3 || input.length() > 50;
    }

    /**
     * 使用Plan-and-Execute模式执行
     */
    private String runWithPlan(String goal) throws IOException {
        // 1. 创建执行计划
        ExecutionPlan plan = planner.createPlan(goal);
        return executePlan(goal, plan);
    }

    private String executePlan(String goal, ExecutionPlan plan) throws IOException {
        // 显示计划
        System.out.println(plan.visualize());
        System.out.println("🚀 开始执行计划...\n");

        // 2. 执行计划
        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();

        List<String> executionOrder = plan.getExecutionOrder();
        for (String taskId : executionOrder) {
            Task task = plan.getTask(taskId);

            // 检查依赖
            if (!task.isExecutable(plan.getAllTasks().stream()
                    .collect(java.util.stream.Collectors.toMap(Task::getId, t -> t)))) {
                System.out.println("⏭️ 跳过任务（依赖未完成）: " + taskId);
                task.markSkipped();
                continue;
            }

            // 执行任务
            System.out.println("▶️ 执行任务: " + task.getDescription());
            task.markStarted();

            try {
                String result = executeTask(goal, plan, task);
                task.markCompleted(result);
                System.out.println("✅ 完成: " + result.substring(0, Math.min(100, result.length())) + "\n");

            } catch (Exception e) {
                task.markFailed(e.getMessage());
                System.out.println("❌ 失败: " + e.getMessage() + "\n");

                // 尝试重新规划
                if (plan.getProgress() < 0.5) {
                    System.out.println("🔄 尝试重新规划...\n");
                    ExecutionPlan replanned = planner.replan(plan, e.getMessage());
                    return executePlan(goal, replanned);
                } else {
                    finalResult.append("任务 ").append(taskId).append(" 失败: ").append(e.getMessage());
                }
            }
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

    /**
     * 执行单个任务
     */
    private String executeTask(String goal, ExecutionPlan plan, Task task) throws IOException {
        // 构建执行提示
        String prompt = String.format(EXECUTION_PROMPT,
                task.getType(), task.getDescription());

        List<GLMClient.Message> messages = Arrays.asList(
                GLMClient.Message.system(prompt),
                GLMClient.Message.user(buildTaskContext(goal, plan, task))
        );

        // 调用LLM
        GLMClient.ChatResponse response = llmClient.chat(
                messages,
                toolRegistry.getToolDefinitions()
        );

        // 如果有工具调用，执行工具
        if (response.hasToolCalls()) {
            StringBuilder results = new StringBuilder();

            for (GLMClient.ToolCall toolCall : response.toolCalls()) {
                String toolName = toolCall.function().name();
                String toolArgs = toolCall.function().arguments();

                System.out.println("   🔧 调用工具: " + toolName);

                String toolResult = toolRegistry.executeTool(toolName, toolArgs);
                results.append(toolResult).append("\n");
            }

            return results.toString().trim();
        } else {
            // 直接返回分析结果
            return response.content();
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

    /**
     * 简单任务直接用ReAct
     */
    private String runSimple(String userInput) throws IOException {
        System.out.println("💡 简单任务，直接执行...\n");

        // 复用第1期的ReAct逻辑
        List<GLMClient.Message> messages = new ArrayList<>();
        messages.add(GLMClient.Message.system("你是一个智能编程助手，可以调用工具完成任务。"));
        messages.add(GLMClient.Message.user(userInput));

        GLMClient.ChatResponse response = llmClient.chat(
                messages,
                toolRegistry.getToolDefinitions()
        );

        if (response.hasToolCalls()) {
            StringBuilder results = new StringBuilder();

            for (GLMClient.ToolCall toolCall : response.toolCalls()) {
                String toolResult = toolRegistry.executeTool(
                        toolCall.function().name(),
                        toolCall.function().arguments()
                );
                results.append(toolResult).append("\n");
            }

            return results.toString().trim();
        } else {
            return response.content();
        }
    }

    /**
     * 获取执行统计
     */
    public String getStats() {
        return "PlanExecuteAgent 已就绪";
    }
}
