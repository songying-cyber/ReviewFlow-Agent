package com.paicli.agent;

import com.paicli.llm.GLMClient;
import com.paicli.memory.MemoryManager;
import com.paicli.util.AnsiStyle;
import com.paicli.tool.ToolRegistry;
import com.paicli.util.TerminalMarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 核心类 - 实现 ReAct 循环
 */
public class Agent {
    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    private final GLMClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<GLMClient.Message> conversationHistory;
    private final MemoryManager memoryManager;
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
            6. search_code - 语义检索代码库，参数：{"query": "自然语言描述", "top_k": 5}

            当需要操作文件、执行命令或创建项目时，请使用工具调用。
            使用工具后，根据工具返回的结果继续思考下一步行动。

            如果用户询问与代码库相关的问题（如"这个类是干什么的"、"哪里用了某个功能"），
            请优先使用 search_code 工具检索相关代码，再基于检索结果回答。

            如果提供了相关记忆，请参考其中的信息来辅助决策。

            请用中文回复用户。
            """;

    public Agent(String apiKey) {
        this.llmClient = new GLMClient(apiKey);
        this.toolRegistry = new ToolRegistry();
        this.conversationHistory = new ArrayList<>();
        this.memoryManager = new MemoryManager(llmClient);

        // 添加系统提示
        conversationHistory.add(GLMClient.Message.system(SYSTEM_PROMPT));
    }

    /**
     * 运行 Agent 循环
     */
    public String run(String userInput) {
        log.info("ReAct run started: inputLength={}", userInput == null ? 0 : userInput.length());
        // 存入短期记忆
        memoryManager.addUserMessage(userInput);

        // 检索相关长期记忆，注入到 system prompt
        String memoryContext = memoryManager.buildContextForQuery(userInput, 500);
        updateSystemPromptWithMemory(memoryContext);

        // 添加用户输入到历史（保持原文，不污染 user message）
        conversationHistory.add(GLMClient.Message.user(userInput));
        StringBuilder reasoningTranscript = new StringBuilder();
        StreamRenderer streamRenderer = new StreamRenderer();

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            try {
                // 调用 LLM
                GLMClient.ChatResponse response = llmClient.chat(
                        conversationHistory,
                        toolRegistry.getToolDefinitions(),
                        streamRenderer
                );

                // 如果有工具调用
                if (response.hasToolCalls()) {
                    appendReasoning(reasoningTranscript, response.reasoningContent());
                    log.info("LLM requested {} tool call(s) in iteration {}", response.toolCalls().size(), iteration);
                    // 添加助手消息（包含工具调用）
                    conversationHistory.add(GLMClient.Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls()
                    ));

                    // 执行每个工具调用
                    for (GLMClient.ToolCall toolCall : response.toolCalls()) {
                        log.info("Executing tool: {} (iteration={})", toolCall.function().name(), iteration);
                        log.debug("Tool args [{}]: {}", toolCall.function().name(), toolCall.function().arguments());
                        String toolArgs = toolCall.function().arguments();

                        // 执行工具
                        String toolResult = toolRegistry.executeTool(toolCall.function().name(), toolArgs);
                        log.debug("Tool result preview [{}]: {}", toolCall.function().name(),
                                preview(toolResult, 300));

                        // 存入记忆
                        memoryManager.addToolResult(toolCall.function().name(), toolResult);

                        // 添加工具结果到历史
                        conversationHistory.add(GLMClient.Message.tool(toolCall.id(), toolResult));
                    }

                    // 继续循环，让 LLM 根据工具结果继续思考
                    continue;

                } else {
                    // 没有工具调用，直接返回结果
                    appendReasoning(reasoningTranscript, response.reasoningContent());
                    conversationHistory.add(GLMClient.Message.assistant(
                            response.reasoningContent(),
                            response.content()
                    ));

                    // 存入记忆
                    memoryManager.addAssistantMessage(response.content());

                    // 记录 token 使用
                    memoryManager.recordTokenUsage(response.inputTokens(), response.outputTokens());
                    log.info("ReAct run finished: inputTokens={}, outputTokens={}, reasoningChars={}, answerChars={}",
                            response.inputTokens(),
                            response.outputTokens(),
                            response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                            response.content() == null ? 0 : response.content().length());
                    if (log.isDebugEnabled()) {
                        log.debug("Assistant answer preview: {}", preview(response.content(), 500));
                    }

                    if (streamRenderer.hasStreamedOutput()) {
                        streamRenderer.finish();
                        return "";
                    }
                    return formatUserFacingResponse(reasoningTranscript.toString(), response.content());
                }

            } catch (IOException e) {
                log.error("LLM call failed in ReAct loop", e);
                return "❌ 调用 LLM 失败: " + e.getMessage();
            }
        }

        log.warn("ReAct run reached max iterations: {}", MAX_ITERATIONS);
        return "❌ 达到最大迭代次数限制，任务未完成";
    }

    /**
     * 清空对话历史（保留系统提示），并提取关键事实到长期记忆
     */
    public void clearHistory() {
        // 先保存当前对话的关键事实
        memoryManager.extractAndSaveFacts();

        GLMClient.Message systemMsg = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemMsg);

        // 清空短期记忆
        memoryManager.getShortTermMemory().clear();
    }

    /**
     * 将记忆上下文注入到 system prompt 中（替换 conversationHistory[0]）
     */
    private void updateSystemPromptWithMemory(String memoryContext) {
        if (memoryContext == null || memoryContext.isEmpty()) {
            // 恢复原始 system prompt
            conversationHistory.set(0, GLMClient.Message.system(SYSTEM_PROMPT));
        } else {
            String enrichedPrompt = SYSTEM_PROMPT + "\n" + memoryContext;
            conversationHistory.set(0, GLMClient.Message.system(enrichedPrompt));
        }
    }

    /**
     * 获取对话历史（用于调试）
     */
    public List<GLMClient.Message> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /**
     * 获取工具注册表（用于同步项目路径等配置）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    private void appendReasoning(StringBuilder reasoningTranscript, String reasoningContent) {
        if (reasoningContent == null || reasoningContent.isBlank()) {
            return;
        }
        if (!reasoningTranscript.isEmpty()) {
            reasoningTranscript.append("\n\n");
        }
        reasoningTranscript.append(reasoningContent.trim());
    }

    private String formatUserFacingResponse(String reasoningContent, String answer) {
        String normalizedReasoning = reasoningContent == null ? "" : reasoningContent.trim();
        String normalizedAnswer = answer == null ? "" : answer.trim();

        if (normalizedReasoning.isEmpty()) {
            return normalizedAnswer;
        }
        if (normalizedAnswer.isEmpty()) {
            return "🧠 思考过程:\n" + normalizedReasoning;
        }
        return "🧠 思考过程:\n" + normalizedReasoning + "\n\n🤖 最终结果:\n" + normalizedAnswer;
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

    private static final class StreamRenderer implements GLMClient.StreamListener {
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean streamedOutput;

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!reasoningStarted) {
                System.out.println(AnsiStyle.heading("🧠 思考过程"));
                reasoningRenderer = new TerminalMarkdownRenderer(System.out);
                reasoningStarted = true;
                streamedOutput = true;
            }
            reasoningRenderer.append(delta);
            System.out.flush();
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (!reasoningStarted) {
                    System.out.println(AnsiStyle.section("🤖 最终结果"));
                } else {
                    System.out.println();
                    System.out.println(AnsiStyle.section("🤖 最终结果"));
                }
                contentRenderer = new TerminalMarkdownRenderer(System.out);
                contentStarted = true;
                streamedOutput = true;
            }
            contentRenderer.append(delta);
            System.out.flush();
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void finish() {
            if (streamedOutput) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                if (contentRenderer != null) {
                    contentRenderer.finish();
                }
                System.out.println();
            }
        }
    }
}
