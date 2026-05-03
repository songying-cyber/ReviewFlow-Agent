package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.context.ContextProfile;
import com.paicli.context.TokenUsageFormatter;
import com.paicli.memory.MemoryManager;
import com.paicli.runtime.CancellationContext;
import com.paicli.util.AnsiStyle;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import com.paicli.util.TerminalMarkdownRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Agent 核心类 - 实现 ReAct 循环
 */
public class Agent {
    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    private LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory;
    private final MemoryManager memoryManager;
    private Supplier<String> externalContextSupplier = () -> "";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    // 系统提示词
    private static final String SYSTEM_PROMPT = """
            你是一个智能编程 Agent PaiCLI，可以帮助用户完成各种任务。

            你可以使用以下工具来完成任务：
            1. read_file - 读取文件内容
            2. write_file - 写入文件内容
            3. list_dir - 列出目录内容
            4. execute_command - 执行Shell命令
            5. create_project - 创建新项目结构
            6. search_code - 语义检索代码库，参数：{"query": "自然语言描述", "top_k": 5}
            7. web_search - 搜索互联网获取实时信息（最新版本、官方文档、技术资讯等），参数：{"query": "搜索关键词", "top_k": 5}
            8. web_fetch - 抓取已知 URL 并返回正文 Markdown，参数：{"url": "https://...", "max_chars": 8000}
            9. mcp__{server}__{tool} - MCP server 动态提供的外部工具，具体参数以工具 schema 为准

            当需要操作文件、执行命令或创建项目时，请使用工具调用。
            使用工具后，根据工具返回的结果继续思考下一步行动。
            对于当前项目内的文件和代码，请优先使用 read_file、list_dir、search_code。
            execute_command 只适合在当前项目目录执行短时命令（如 git status、mvn test），不要用它扫描 /、~ 或整个文件系统。
            安全策略硬规则（HITL 之外的兜底，无法绕过，请提前规避）：
            - read_file / write_file / list_dir / create_project 的路径必须在项目根之内，绝对路径或 .. 越界会被拒绝
            - write_file 单文件 5MB 上限
            - execute_command 禁止 sudo、rm -rf 全盘或用户目录、mkfs、dd 写裸设备、fork bomb、curl|sh、find /、chmod 777 /、shutdown
            - 若调用被策略拒绝（结果以 "🛡️ 策略拒绝" 开头），不要原样重试，改用项目内相对路径或更安全的方式
            - MCP 工具来自外部 server，默认会触发 HITL 审批与审计；除非任务确实需要该 server 能力，否则优先使用内置工具
            - 长上下文模式下，system prompt 可能包含 MCP resources 索引（仅 URI / 描述，不含正文）；需要正文时再读取对应 resource
            同一轮返回多个工具调用时，系统会并行执行这些工具；如果工具之间有依赖关系，请分多轮调用。
            如果需要同时检查多个已知且互不依赖的文件或目录（例如同时读取 pom.xml、README.md、ROADMAP.md，
            或同时列出 src/main/java、src/test/java、src/main/resources），请在同一轮返回多个 read_file/list_dir 工具调用。

            工具选择优先级：
            - 代码库相关问题（"这个类是干什么的"、"哪里用了某个功能"）→ search_code，不要走 web_search
            - 训练数据已知的稳定知识（语法、稳定 API、基础概念）→ 直接回答，不要联网
            - 时效性 / 最新信息 / 不确定的事实 → web_search 找入口，找到 URL 后再 web_fetch 拿全文
            - 已经有具体 URL → 直接 web_fetch，不要再 web_search 一次
            - web_fetch 拿到空正文（提示 SPA / 防爬墙）→ 自动 fallback 到浏览器 MCP，不要重复 web_fetch

            工具选择 - 网页内容获取：
            - 静态 / SSR 页面（博客、官方文档、wiki、GitHub README）→ web_fetch
            - SPA / React / Vue / 客户端渲染、需要 JS 才有内容 → 浏览器 MCP（mcp__chrome-devtools__navigate_page + take_snapshot）
            - 防爬墙、需要登录态、需要表单交互（点击/输入/提交）→ 浏览器 MCP
            - 微信公众号文章 (mp.weixin.qq.com)、知乎专栏、推特、小红书等 → 浏览器 MCP（这些站点 web_fetch 通常拿不到正文）
            - 已知 URL → 直接 web_fetch 试一次，失败再用浏览器 MCP

            工具选择 - 浏览器操作：
            - 优先 mcp__chrome-devtools__take_snapshot（结构化 DOM 文本，LLM 能直接理解）
            - 不要默认使用 take_screenshot，除非用户明确要看页面截图或做 UI 验收
            - 表单填写优先 mcp__chrome-devtools__fill_form，一次性填多字段
            - 等待异步加载使用 mcp__chrome-devtools__wait_for（指定文本或选择器出现）
            - 控制台错误排查使用 list_console_messages；网络请求查看使用 list_network_requests + get_network_request
            - 如果页面需要带登录态的调试 Chrome，而当前浏览器返回登录页，应提示用户先用调试端口启动 Chrome 并执行 /browser connect
            - shared 模式下敏感页面的点击、填写、脚本执行等改写操作会强制单步 HITL；close_page 只能关闭 PaiCLI 自己创建的 tab

            如果提供了相关记忆，请参考其中的信息来辅助决策。

            请用中文回复用户。
            """;

    public Agent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry());
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        this.memoryManager = new MemoryManager(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        conversationHistory.add(LlmClient.Message.system(SYSTEM_PROMPT));
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.memoryManager.setLlmClient(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    /**
     * 运行 Agent 循环
     */
    public String run(String userInput) {
        log.info("ReAct run started: inputLength={}", userInput == null ? 0 : userInput.length());
        // 存入短期记忆
        memoryManager.addUserMessage(userInput);

        // 检索相关长期记忆，注入到 system prompt
        ContextProfile contextProfile = memoryManager.getContextProfile();
        String memoryContext = memoryManager.buildContextForQuery(userInput, contextProfile.memoryContextTokens());
        updateSystemPromptWithMemory(memoryContext);

        // 添加用户输入到历史（保持原文，不污染 user message）
        conversationHistory.add(LlmClient.Message.user(userInput));
        StringBuilder reasoningTranscript = new StringBuilder();
        StreamRenderer streamRenderer = new StreamRenderer();

        long startNanos = System.nanoTime();
        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);

        // 主退出条件 = LLM 自己决定（不再调用工具就返回）；
        // budget 仅在 token 用尽 / 检测到死循环 / 超出硬轮数时兜底。
        while (true) {
            if (CancellationContext.isCancelled()) {
                log.info("ReAct run cancelled before iteration");
                return "⏹️ 已取消当前任务。";
            }
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                String statsLine = formatTokenStats(budget, startNanos);
                String description = budget.describeExit(exitReason);
                log.warn("ReAct run exhausted budget: reason={}, iteration={}, tokens={}/{}",
                        exitReason, budget.iteration(),
                        budget.totalInputTokens() + budget.totalOutputTokens(), budget.tokenBudget());
                return "❌ " + description + "\n\n" + statsLine;
            }

            int iteration = budget.beginIteration();

            try {
                // 调用 LLM
                LlmClient.ChatResponse response = llmClient.chat(
                        conversationHistory,
                        toolRegistry.getToolDefinitions(),
                        streamRenderer
                );
                if (CancellationContext.isCancelled()) {
                    log.info("ReAct run cancelled after LLM response");
                    return "⏹️ 已取消当前任务。";
                }

                budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

                // 如果有工具调用
                if (response.hasToolCalls()) {
                    appendReasoning(reasoningTranscript, response.reasoningContent());
                    log.info("LLM requested {} tool call(s) in iteration {}", response.toolCalls().size(), iteration);
                    budget.recordToolCalls(response.toolCalls());
                    printToolCalls(System.out, response.toolCalls());
                    // 添加助手消息（包含工具调用）
                    conversationHistory.add(LlmClient.Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls()
                    ));

                    // 在工具执行前就 flush 本轮流式渲染器，避免 TerminalMarkdownRenderer
                    // 内部 pending 缓冲区（仅按换行 flush）里的文本被 HITL 提示"跨过"
                    // 造成标题和内容错位。重置后下一轮迭代的 reasoning/content 会重新打印标题。
                    streamRenderer.resetBetweenIterations();

                    List<ToolExecutionResult> toolResults = executeToolCalls(response.toolCalls(), iteration);
                    for (ToolExecutionResult toolResult : toolResults) {
                        memoryManager.addToolResult(toolResult.name(), toolResult.result());
                        conversationHistory.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
                    }

                    // 继续循环，让 LLM 根据工具结果继续思考
                    continue;
                }

                // 没有工具调用，直接返回结果
                appendReasoning(reasoningTranscript, response.reasoningContent());
                conversationHistory.add(LlmClient.Message.assistant(
                        response.reasoningContent(),
                        response.content()
                ));

                // 存入记忆
                memoryManager.addAssistantMessage(response.content());

                // 记录 token 使用
                memoryManager.recordTokenUsage(budget.totalInputTokens(), budget.totalOutputTokens(), budget.totalCachedInputTokens());
                log.info("ReAct run finished: inputTokens={}, outputTokens={}, reasoningChars={}, answerChars={}",
                        budget.totalInputTokens(),
                        budget.totalOutputTokens(),
                        response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                        response.content() == null ? 0 : response.content().length());
                if (log.isDebugEnabled()) {
                    log.debug("Assistant answer preview: {}", preview(response.content(), 500));
                }

                String statsLine = formatTokenStats(budget, startNanos);

                if (streamRenderer.hasStreamedOutput()) {
                    streamRenderer.finish();
                    System.out.println(statsLine);
                    return "";
                }
                return formatUserFacingResponse(reasoningTranscript.toString(), response.content())
                        + "\n\n" + statsLine;

            } catch (IOException e) {
                log.error("LLM call failed in ReAct loop", e);
                return "❌ 调用 LLM 失败: " + e.getMessage();
            }
        }
    }

    /**
     * 清空对话历史（保留系统提示），不影响长期记忆
     */
    public void clearHistory() {
        LlmClient.Message systemMsg = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemMsg);

        // 清空短期记忆
        memoryManager.clearShortTerm();
    }

    /**
     * 将记忆上下文注入到 system prompt 中（替换 conversationHistory[0]）
     */
    private void updateSystemPromptWithMemory(String memoryContext) {
        String externalContext = buildExternalContext();
        if ((memoryContext == null || memoryContext.isEmpty()) && externalContext.isEmpty()) {
            // 恢复原始 system prompt
            conversationHistory.set(0, LlmClient.Message.system(SYSTEM_PROMPT));
        } else {
            StringBuilder enrichedPrompt = new StringBuilder(SYSTEM_PROMPT);
            if (memoryContext != null && !memoryContext.isEmpty()) {
                enrichedPrompt.append("\n").append(memoryContext);
            }
            if (!externalContext.isEmpty()) {
                enrichedPrompt.append("\n").append(externalContext);
            }
            conversationHistory.set(0, LlmClient.Message.system(enrichedPrompt.toString()));
        }
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context", e);
            return "";
        }
    }

    /**
     * 获取对话历史（用于调试）
     */
    public List<LlmClient.Message> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public String getContextStatus() {
        com.paicli.context.ContextProfile profile = memoryManager.getContextProfile();
        int window = profile.maxContextWindow();
        int triggerTokens = profile.compressionTriggerTokens();

        // 分类估算 token 占用
        int systemTokens = 0, userTokens = 0, assistantTokens = 0, toolTokens = 0;
        int systemCount = 0, userCount = 0, assistantCount = 0, toolCount = 0;
        for (LlmClient.Message msg : conversationHistory) {
            int t = com.paicli.memory.TokenBudget.estimateMessagesTokens(java.util.List.of(msg));
            switch (msg.role()) {
                case "system" -> { systemTokens += t; systemCount++; }
                case "user" -> { userTokens += t; userCount++; }
                case "assistant" -> { assistantTokens += t; assistantCount++; }
                case "tool" -> { toolTokens += t; toolCount++; }
            }
        }
        int messagesTokens = userTokens + assistantTokens + toolTokens;
        int toolsSchemaTokens = estimateToolsSchemaTokens();
        int total = systemTokens + messagesTokens + toolsSchemaTokens;
        double ratio = window > 0 ? (double) total / window : 0;
        int triggerRemaining = Math.max(0, triggerTokens - total);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 Context Usage   %s   window: %s%n",
                modelLabel(), formatTokens(window)));
        sb.append("\n  ").append(progressBar(ratio, 30))
                .append(String.format("  %d%%  (%s / %s)%n",
                        (int) Math.round(ratio * 100), formatTokens(total), formatTokens(window)));
        sb.append("\n  当前占用细分:\n");
        sb.append(formatLine("System prompt",      systemTokens,    window, systemCount));
        sb.append(formatLine("Tools schema",       toolsSchemaTokens, window, -1));
        sb.append(formatLine("Conversation",       messagesTokens, window,
                userCount + assistantCount + toolCount));
        sb.append("    ─────────────────────────────────\n");
        sb.append(String.format("    合计:              %8s  (%4.1f%%)%n",
                formatTokens(total), ratio * 100));
        sb.append(String.format("%n  压缩阈值: %s (%d%%)   距压缩还有: %s%n",
                formatTokens(triggerTokens),
                (int) (profile.compressionTriggerRatio() * 100),
                formatTokens(triggerRemaining)));
        sb.append("  MCP resources 自动索引: ")
                .append(profile.mcpResourceIndexEnabled() ? "开启" : "关闭（window 不足 32k）")
                .append("\n");
        sb.append("  prompt cache: ").append(profile.promptCacheMode()).append("\n");
        sb.append("\n");
        sb.append(memoryManager.getSystemStatus());
        return sb.toString();
    }

    private String modelLabel() {
        if (llmClient == null) return "(no model)";
        return llmClient.getModelName() + " (" + llmClient.getProviderName() + ")";
    }

    private int estimateToolsSchemaTokens() {
        try {
            return com.paicli.memory.MemoryEntry.estimateTokens(
                    new ObjectMapper().writeValueAsString(toolRegistry.getToolDefinitions()));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String formatLine(String label, int tokens, int window, int count) {
        double pct = window > 0 ? (double) tokens / window * 100 : 0;
        String countLabel = count >= 0 ? String.format("  [%d 条]", count) : "";
        return String.format("    %-18s %8s  (%4.1f%%)%s%n",
                label + ":", formatTokens(tokens), pct, countLabel);
    }

    private static String progressBar(double ratio, int width) {
        ratio = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(ratio * width);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        bar.append("]");
        return bar.toString();
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000)     return String.format("%.1fk", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    /**
     * 获取工具注册表（用于同步项目路径等配置）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    private String formatTokenStats(AgentBudget budget, long startNanos) {
        return TokenUsageFormatter.format(
                llmClient,
                budget.totalInputTokens(),
                budget.totalOutputTokens(),
                budget.totalCachedInputTokens(),
                startNanos);
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

    private List<ToolExecutionResult> executeToolCalls(List<LlmClient.ToolCall> toolCalls, int iteration) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Scheduling tool: {} (iteration={})", toolName, iteration);
            log.debug("Tool args [{}]: {}", toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Executing {} tool calls in parallel (iteration={})", invocations.size(), iteration);
        }
        List<ToolExecutionResult> results = toolRegistry.executeTools(invocations);
        for (ToolExecutionResult result : results) {
            log.debug("Tool result preview [{}]: {}", result.name(), preview(result.result(), 300));
        }
        return results;
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
        return "🧠 思考过程:\n" + normalizedReasoning + "\n\n🤖 回复:\n" + normalizedAnswer;
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

    private static void printToolCalls(PrintStream out, List<LlmClient.ToolCall> toolCalls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<LlmClient.ToolCall> calls = group.getValue();
            out.println(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (LlmClient.ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    out.println(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
    }

    private static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "search_code" -> "🔍 搜索代码 " + count + " 次";
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            default -> toolName != null && toolName.startsWith("mcp__")
                    ? formatMcpLabel(toolName, count)
                    : "🔧 " + toolName + " × " + count;
        };
    }

    private static String formatMcpLabel(String toolName, int count) {
        String[] parts = toolName.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
        return count == 1
                ? "🔌 调用 MCP 工具 " + display
                : "🔌 调用 MCP 工具 " + display + " × " + count;
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON_MAPPER.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                default -> null;
            };
            if (key == null) {
                return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
            }
            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }

    /**
     * 流式输出渲染器，将 reasoning_content 与 content 分区展示。
     *
     * 服务器可能把 reasoning_content 切成多段下发，甚至在 content 开始之后追加 reasoning；
     * 终端是线性的，无法回头修改已写出的文字。渲染策略：
     *
     * 1. 在 content 出现之前，只要 reasoning 有实质内容（非空白），就立刻流式打印在"🧠 思考过程"下
     *    同一次用户输入只打印一次"🧠 思考过程"标题；工具调用后的后续推理继续归在同一块下
     * 2. 仅空白的 reasoning delta 会先暂存，不触发标题——避免出现"空的思考过程"
     * 3. content 一出现就收尾 reasoning 区，打印"🤖 回复"标题并流式输出 content
     *    （故意使用"回复"而不是"最终结果"：当模型在调用工具前先 narrate 一段时，"最终结果"会误导用户
     *    认为下面的内容就是答案；"回复"在 narration 和真正最终回答两种情况下都准确）
     * 4. 如果 content 启动之后又收到 reasoning（服务器把思考内容追加在答案之后），
     *    缓冲到 lateReasoning，最终在 finish() 用"🧠 补充思考"标题独立展示，不会污染回复区
     */
    private static final class StreamRenderer implements LlmClient.StreamListener {
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningHeadingPrinted;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean streamedOutput;

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (contentStarted) {
                // content 已开始，无法回头；缓冲到"补充思考"
                lateReasoning.append(delta);
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;  // 还没攒出实质内容，等
                }
                if (!containsLineBreak(pendingReasoning)) {
                    return;  // 避免先打印一个空标题，等有完整行或迭代切换时再 flush
                }
                printReasoningHeadingIfNeeded();
                reasoningRenderer = new TerminalMarkdownRenderer(System.out);
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
            } else {
                reasoningRenderer.append(delta);
            }
            System.out.flush();
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    System.out.println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    // 有实质 reasoning 但之前没攒够阈值就被 content 打断：先补打思考过程
                    printReasoningHeadingIfNeeded();
                    TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(System.out);
                    r.append(pendingReasoning.toString());
                    r.finish();
                    System.out.println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                System.out.println(AnsiStyle.section("🤖 回复"));
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

        /**
         * 在一次迭代（通常是一次 tool-call 分支执行完后）和下一次迭代之间调用，
         * 收尾当前的 reasoning/content 渲染器并重置所有状态。
         *
         * 这样下一轮迭代的 reasoning/content 到达时会重新初始化渲染器，
         * 避免出现"上一轮的标题在屏幕高处、下一轮的内容出现在 HITL 块下方"的错位。
         */
        private void resetBetweenIterations() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            } else {
                flushPendingReasoning();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            // 迭代间的 late reasoning 当场 flush（独立一段「补充思考」），不拖到 run() 结束
            String late = lateReasoning.toString().trim();
            if (!late.isEmpty()) {
                System.out.println();
                System.out.println(AnsiStyle.heading("🧠 补充思考"));
                TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(System.out);
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            pendingReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            if (streamedOutput) {
                System.out.println();
            }
        }

        private void finish() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
            } else {
                flushPendingReasoning();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
            }
            String late = lateReasoning.toString().trim();
            if (!late.isEmpty()) {
                System.out.println();
                System.out.println(AnsiStyle.heading("🧠 补充思考"));
                TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(System.out);
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            if (streamedOutput) {
                System.out.println();
            }
        }

        private boolean containsLineBreak(CharSequence content) {
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch == '\n' || ch == '\r') {
                    return true;
                }
            }
            return false;
        }

        private void flushPendingReasoning() {
            String pending = pendingReasoning.toString();
            if (pending.isBlank()) {
                pendingReasoning.setLength(0);
                return;
            }
            printReasoningHeadingIfNeeded();
            TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(System.out);
            renderer.append(pending);
            renderer.finish();
            pendingReasoning.setLength(0);
            streamedOutput = true;
        }

        private void printReasoningHeadingIfNeeded() {
            if (!reasoningHeadingPrinted) {
                System.out.println(AnsiStyle.heading("🧠 思考过程"));
                reasoningHeadingPrinted = true;
            }
        }
    }
}
