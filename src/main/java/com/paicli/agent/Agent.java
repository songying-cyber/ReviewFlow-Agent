package com.paicli.agent;

import com.paicli.llm.GLMClient;
import com.paicli.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 核心类 - 实现 ReAct 循环
 */
public class Agent {
    private final GLMClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<GLMClient.Message> conversationHistory;
    private static final int MAX_ITERATIONS = 10;

    // 系统提示词
    private static final String SYSTEM_PROMPT = """
            你是一个智能编程助手，可以帮助用户完成各种任务。

            你可以使用以下工具来完成任务：
            1. read_file - 读取文件内容
            2. write_file - 写入文件内容
            3. list_dir - 列出目录内容
            4. execute_command - 执行Shell命令
            5. create_project - 创建新项目结构

            当需要操作文件、执行命令或创建项目时，请使用工具调用。
            使用工具后，根据工具返回的结果继续思考下一步行动。

            请用中文回复用户。
            """;

    public Agent(String apiKey) {
        this.llmClient = new GLMClient(apiKey);
        this.toolRegistry = new ToolRegistry();
        this.conversationHistory = new ArrayList<>();

        // 添加系统提示
        conversationHistory.add(GLMClient.Message.system(SYSTEM_PROMPT));
    }

    /**
     * 运行 Agent 循环
     */
    public String run(String userInput) {
        // 添加用户输入到历史
        conversationHistory.add(GLMClient.Message.user(userInput));

        System.out.println("🤔 思考中...\n");

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            try {
                // 调用 LLM
                GLMClient.ChatResponse response = llmClient.chat(
                        conversationHistory,
                        toolRegistry.getToolDefinitions()
                );

                // 如果有工具调用
                if (response.hasToolCalls()) {
                    // 添加助手消息（包含工具调用）
                    conversationHistory.add(GLMClient.Message.assistant(
                            response.content(),
                            response.toolCalls()
                    ));

                    // 执行每个工具调用
                    for (GLMClient.ToolCall toolCall : response.toolCalls()) {
                        String toolName = toolCall.function().name();
                        String toolArgs = toolCall.function().arguments();

                        System.out.println("🔧 执行工具: " + toolName);
                        System.out.println("   参数: " + toolArgs);

                        // 执行工具
                        String toolResult = toolRegistry.executeTool(toolName, toolArgs);

                        System.out.println("   结果: " + toolResult.substring(0, Math.min(200, toolResult.length()))
                                + (toolResult.length() > 200 ? "..." : "") + "\n");

                        // 添加工具结果到历史
                        conversationHistory.add(GLMClient.Message.tool(toolCall.id(), toolResult));
                    }

                    // 继续循环，让 LLM 根据工具结果继续思考
                    continue;

                } else {
                    // 没有工具调用，直接返回结果
                    conversationHistory.add(GLMClient.Message.assistant(response.content()));

                    // 打印 token 使用情况
                    System.out.printf("📊 Token使用: 输入=%d, 输出=%d%n\n",
                            response.inputTokens(), response.outputTokens());

                    return response.content();
                }

            } catch (IOException e) {
                return "❌ 调用 LLM 失败: " + e.getMessage();
            }
        }

        return "❌ 达到最大迭代次数限制，任务未完成";
    }

    /**
     * 清空对话历史（保留系统提示）
     */
    public void clearHistory() {
        GLMClient.Message systemMsg = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemMsg);
    }

    /**
     * 获取对话历史（用于调试）
     */
    public List<GLMClient.Message> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }
}
