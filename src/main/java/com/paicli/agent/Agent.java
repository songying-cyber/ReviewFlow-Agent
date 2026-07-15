package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmTraceLogger;
import com.paicli.context.ContextProfile;
import com.paicli.context.TokenUsageFormatter;
import com.paicli.lsp.LspDiagnosticReport;
import com.paicli.memory.ConversationHistoryCompactor;
import com.paicli.memory.ExplicitMemoryHints;
import com.paicli.memory.MemoryManager;
import com.paicli.prompt.PromptAssembler;
import com.paicli.prompt.PromptContext;
import com.paicli.prompt.PromptMode;
import com.paicli.prompt.ProjectMemoryLoader;
import com.paicli.render.PlainRenderer;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import com.paicli.runtime.CancellationContext;
import com.paicli.skill.SkillContextBuffer;
import com.paicli.skill.SkillIndexFormatter;
import com.paicli.skill.SkillRegistry;
import com.paicli.util.AnsiStyle;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import com.paicli.util.TerminalMarkdownRenderer;
import com.paicli.image.ImageReferenceParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
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
    private final ConversationHistoryCompactor historyCompactor;
    private Supplier<String> externalContextSupplier = () -> "";
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private Renderer renderer;
    private Supplier<Boolean> hitlEnabledSupplier = () -> false;
    private boolean returnFinalResponseWhenStreamed;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();

    public Agent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry());
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        this.memoryManager = new MemoryManager(llmClient);
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(memoryManager::storeFact);
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.memoryManager.setLlmClient(llmClient);
        this.historyCompactor.setLlmClient(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    public void setReturnFinalResponseWhenStreamed(boolean returnFinalResponseWhenStreamed) {
        this.returnFinalResponseWhenStreamed = returnFinalResponseWhenStreamed;
    }

    /**
     * 注入 HITL 启用状态的快照源，用于状态栏 / StatusInfo 显示。
     * Main 启动后用 {@code reactAgent.setHitlEnabledSupplier(hitlHandler::isEnabled)} 接进来。
     */
    public void setHitlEnabledSupplier(Supplier<Boolean> supplier) {
        this.hitlEnabledSupplier = supplier == null ? () -> false : supplier;
    }

    /**
     * 获取渲染器；首次调用时如果未设置，懒加载一个 {@link PlainRenderer} 兜底，
     * 保证旧调用方（构造 Agent 后没有 setRenderer 的代码、单测等）行为不变。
     */
    private Renderer renderer() {
        if (renderer == null) {
            renderer = new PlainRenderer();
        }
        return renderer;
    }

    /**
     * 运行 Agent 循环
     */
    public String run(String userInput) {
        log.info("ReAct run started: inputLength={}", userInput == null ? 0 : userInput.length());
        pruneHistoricalImagePayloads();
        // 存入短期记忆
        memoryManager.addUserMessage(userInput);
        storeExplicitBrowserMemoryHint(userInput);

        // 检索相关长期记忆，注入到 system prompt
        ContextProfile contextProfile = memoryManager.getContextProfile();
        String memoryContext = memoryManager.buildContextForQuery(userInput, contextProfile.memoryContextTokens());
        updateSystemPromptWithMemory(memoryContext);

        // 添加用户输入到历史（如有 skill body 注入，前置到原文之前）
        String userMessageContent = prependSkillBodies(userInput);
        conversationHistory.add(ImageReferenceParser.userMessage(
                userMessageContent,
                Path.of(toolRegistry.getProjectPath())));
        StringBuilder reasoningTranscript = new StringBuilder();
        StreamRenderer streamRenderer = new StreamRenderer(renderer());

        long startNanos = System.nanoTime();
        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);
        pushStatus(budget, startNanos, "running");

        // 主退出条件 = LLM 自己决定（不再调用工具就返回）；
        // budget 仅在 token 用尽 / 检测到死循环 / 超出硬轮数时兜底。
        while (true) {
            if (CancellationContext.isCancelled()) {
                log.info("ReAct run cancelled before iteration");
                pushStatus(budget, startNanos, "idle");
                return "⏹️ 已取消当前任务。";
            }
            // 调 LLM 前评估 conversationHistory 是否接近 window 上限；超阈值就把早期消息压缩成摘要。
            // 这是与第 3 期 Memory 短期记忆压缩并行的另一道压缩——后者只压 shortTermMemory，
            // 真正决定下一轮 LLM input token 的是这里。
            injectPendingLspDiagnostics();
            maybeCompactHistory();
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                String description = budget.describeExit(exitReason);
                log.warn("ReAct run exhausted budget: reason={}, iteration={}, tokens={}/{}",
                        exitReason, budget.iteration(),
                        budget.totalInputTokens() + budget.totalOutputTokens(), budget.tokenBudget());
                pushStatus(budget, startNanos, "idle");
                return "❌ " + description;
            }

            int iteration = budget.beginIteration();

            try {
                List<LlmClient.Tool> toolDefinitions = llmClient.supportsTools()
                        ? toolRegistry.getToolDefinitions()
                        : null;
                logRequestContext("react iteration=" + iteration, toolDefinitions);
                streamRenderer.beginThinking();
                // 调用 LLM
                LlmClient.ChatResponse response = llmClient.chat(
                        conversationHistory,
                        toolDefinitions,
                        streamRenderer
                );
                LlmTraceLogger.logReasoning(log, "react iteration=" + iteration, llmClient, response.reasoningContent());
                if (CancellationContext.isCancelled()) {
                    log.info("ReAct run cancelled after LLM response");
                    streamRenderer.finish();
                    pushStatus(budget, startNanos, "idle");
                    return "⏹️ 已取消当前任务。";
                }

                budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

                // 如果有工具调用
                if (response.hasToolCalls()) {
                    appendReasoning(reasoningTranscript, response.reasoningContent());
                    log.info("LLM requested {} tool call(s) in iteration {}", response.toolCalls().size(), iteration);
                    budget.recordToolCalls(response.toolCalls());
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
                    renderer().appendToolCalls(response.toolCalls());

                    List<ToolExecutionResult> toolResults = executeToolCalls(response.toolCalls(), iteration);
                    for (ToolExecutionResult toolResult : toolResults) {
                        memoryManager.addToolResult(toolResult.name(), toolResult.result());
                        conversationHistory.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
                    }
                    appendImageToolMessages(toolResults);
                    pushStatus(budget, startNanos, "running");

                    // 继续循环，让 LLM 根据工具结果继续思考
                    continue;
                }

                // 没有工具调用，直接返回结果
                appendReasoning(reasoningTranscript, response.reasoningContent());
                conversationHistory.add(LlmClient.Message.assistant(response.content()));

                // 存入记忆
                memoryManager.addAssistantMessage(response.content());

                // 记录 token 使用
                memoryManager.recordTokenUsage(budget.totalInputTokens(), budget.totalOutputTokens(), budget.totalCachedInputTokens());
                pushStatus(budget, startNanos, "idle");
                log.info("ReAct run finished: inputTokens={}, outputTokens={}, reasoningChars={}, answerChars={}",
                        budget.totalInputTokens(),
                        budget.totalOutputTokens(),
                        response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                        response.content() == null ? 0 : response.content().length());
                if (log.isDebugEnabled()) {
                    log.debug("Assistant answer preview: {}", preview(response.content(), 500));
                }

                if (streamRenderer.hasStreamedOutput()) {
                    streamRenderer.finish();
                    return returnFinalResponseWhenStreamed ? (response.content() == null ? "" : response.content().trim()) : "";
                }
                streamRenderer.clearThinkingPanel();
                return formatUserFacingResponse(reasoningTranscript.toString(), response.content());

            } catch (IOException e) {
                log.error("LLM call failed in ReAct loop", e);
                streamRenderer.finish();
                return "❌ 调用 LLM 失败: " + e.getMessage();
            }
        }
    }

    /**
     * 清空对话历史并重建基础系统提示，不影响长期记忆条目
     */
    public void clearHistory() {
        conversationHistory.clear();
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));

        // 清空短期记忆
        memoryManager.clearShortTerm();
        if (skillContextBuffer != null) {
            skillContextBuffer.clear();
        }
    }

    /**
     * 手动压缩当前 ReAct 对话历史，不等待上下文窗口阈值触发。
     */
    public CompactionResult compactHistoryNow() {
        long beforeTokens = estimateCurrentContextTokens();
        try {
            boolean compacted = historyCompactor.compactNow(conversationHistory);
            return new CompactionResult(compacted, beforeTokens, estimateCurrentContextTokens(), null);
        } catch (Exception e) {
            log.warn("manual conversationHistory compaction failed", e);
            return new CompactionResult(false, beforeTokens, estimateCurrentContextTokens(), e.getMessage());
        }
    }

    public record CompactionResult(boolean compacted, long beforeTokens, long afterTokens, String error) {
    }

    /** 当前状态栏快照：ctx 表示下一轮请求仍会携带的上下文估算，不含累计 in/out 用量。 */
    public StatusInfo currentStatus(String phase) {
        String normalizedPhase = phase == null || phase.isBlank() ? "idle" : phase;
        String model = llmClient == null ? "—" : llmClient.getModelName();
        long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
        boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
        long contextTokens = estimateCurrentContextTokens();
        if ("idle".equals(normalizedPhase)) {
            return StatusInfo.idle(model, contextWindow, contextTokens, hitl);
        }
        return StatusInfo.active(model, contextWindow, contextTokens, hitl, normalizedPhase);
    }

    /**
     * 将记忆上下文注入到 system prompt 中（替换 conversationHistory[0]）
     */
    private void updateSystemPromptWithMemory(String memoryContext) {
        conversationHistory.set(0, LlmClient.Message.system(buildSystemPrompt(memoryContext)));
    }

    private String buildSystemPrompt(String memoryContext) {
        return promptAssembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .projectMemoryContext(buildProjectMemoryContext())
                .memoryContext(memoryContext)
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .toolsEnabled(llmClient == null || llmClient.supportsTools())
                .build());
    }

    private void maybeCompactHistory() {
        if (historyCompactor == null) return;
        int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
        try {
            boolean compacted = historyCompactor.compactIfNeeded(conversationHistory, trigger);
            if (compacted) {
                renderer().stream().println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
        } catch (Exception e) {
            log.warn("conversationHistory compaction failed", e);
        }
    }

    private void pruneHistoricalImagePayloads() {
        int messageCount = 0;
        int imageCount = 0;
        for (int i = 0; i < conversationHistory.size(); i++) {
            LlmClient.Message message = conversationHistory.get(i);
            int images = message.imagePartCount();
            if (images <= 0) {
                continue;
            }
            conversationHistory.set(i, message.withoutImageContent());
            messageCount++;
            imageCount += images;
        }
        if (imageCount > 0) {
            log.info("Pruned historical image payloads before new ReAct turn: messages={}, images={}",
                    messageCount, imageCount);
        }
    }

    private void injectPendingLspDiagnostics() {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        conversationHistory.add(LlmClient.Message.user(report.promptText()));
        renderer().stream().println(report.displayText());
        log.info("Injected LSP diagnostics into ReAct conversation");
    }

    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkills());
        } catch (Exception e) {
            log.warn("Failed to build skill index", e);
            return "";
        }
    }

    private String prependSkillBodies(String userInput) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return userInput;
        }
        String drained = skillContextBuffer.drain();
        if (drained.isEmpty()) return userInput;
        return drained + "\n用户输入：\n" + userInput;
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

    private String buildProjectMemoryContext() {
        try {
            return ProjectMemoryLoader.createDefault(Path.of(toolRegistry.getProjectPath())).loadForPrompt();
        } catch (Exception e) {
            log.warn("Failed to load PAI.md project memory", e);
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

    private void storeExplicitBrowserMemoryHint(String userInput) {
        List<String> recentTexts = conversationHistory.stream()
                .map(LlmClient.Message::content)
                .filter(content -> content != null && !content.isBlank())
                .toList();
        String fact = ExplicitMemoryHints.browserLoginFact(userInput, recentTexts);
        if (fact != null && !fact.isBlank()) {
            memoryManager.storeFact(fact, "global");
        }
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

    private long estimateCurrentContextTokens() {
        long messageTokens = com.paicli.memory.TokenBudget.estimateMessagesTokens(conversationHistory);
        return Math.max(0L, messageTokens + estimateToolsSchemaTokens());
    }

    private void logRequestContext(String scope, List<LlmClient.Tool> tools) {
        if (!log.isInfoEnabled()) {
            return;
        }
        int systemTokens = 0;
        int userTokens = 0;
        int assistantTokens = 0;
        int toolMessageTokens = 0;
        int imageParts = 0;
        int messages = 0;
        StringBuilder imageDetails = new StringBuilder();
        for (int messageIndex = 0; messageIndex < conversationHistory.size(); messageIndex++) {
            LlmClient.Message msg = conversationHistory.get(messageIndex);
            messages++;
            int tokens = com.paicli.memory.TokenBudget.estimateMessagesTokens(List.of(msg));
            imageParts += msg.imagePartCount();
            appendImageDetails(imageDetails, msg, messageIndex);
            switch (msg.role()) {
                case "system" -> systemTokens += tokens;
                case "user" -> userTokens += tokens;
                case "assistant" -> assistantTokens += tokens;
                case "tool" -> toolMessageTokens += tokens;
                default -> {
                }
            }
        }
        int toolsSchemaTokens = 0;
        int toolCount = tools == null ? 0 : tools.size();
        if (tools != null && !tools.isEmpty()) {
            try {
                toolsSchemaTokens = com.paicli.memory.MemoryEntry.estimateTokens(
                        new ObjectMapper().writeValueAsString(tools));
            } catch (Exception e) {
                log.debug("Failed to estimate tools schema tokens", e);
            }
        }
        int estimatedTotal = systemTokens + userTokens + assistantTokens + toolMessageTokens + toolsSchemaTokens;
        log.info("LLM request context [{}]: messages={}, images={}, systemTokens={}, userTokens={}, assistantTokens={}, toolMessageTokens={}, tools={}, toolsSchemaTokens={}, estimatedTotal={}",
                scope, messages, imageParts, systemTokens, userTokens, assistantTokens, toolMessageTokens,
                toolCount, toolsSchemaTokens, estimatedTotal);
        if (!imageDetails.isEmpty()) {
            log.info("LLM request images [{}]: {}", scope, imageDetails);
        }
    }

    private void appendImageDetails(StringBuilder sb, LlmClient.Message msg, int messageIndex) {
        if (msg == null || !msg.hasContentParts()) {
            return;
        }
        for (int partIndex = 0; partIndex < msg.contentParts().size(); partIndex++) {
            LlmClient.ContentPart part = msg.contentParts().get(partIndex);
            if (part == null || !part.isImage()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            String payload = "image_url".equals(part.type()) ? part.imageUrl() : part.imageBase64();
            sb.append("#").append(messageIndex)
                    .append(".").append(partIndex)
                    .append(" role=").append(msg.role())
                    .append(" type=").append(part.type())
                    .append(" mime=").append(part.mimeType() == null ? "-" : part.mimeType())
                    .append(" payloadChars=").append(payload == null ? 0 : payload.length())
                    .append(" sha256=").append(shortSha256(payload));
        }
    }

    private String shortSha256(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
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

    /** 把当前预算/耗时/HITL 状态推送给 renderer 状态栏。 */
    private void pushStatus(AgentBudget budget, long startNanos, String phase) {
        try {
            String model = llmClient == null ? "—" : llmClient.getModelName();
            long totalTokens = budget == null ? 0L
                    : (long) (budget.totalInputTokens() + budget.totalOutputTokens());
            long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
            boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            String cost = budget == null ? null : TokenUsageFormatter.estimatedCostCny(
                    llmClient,
                    budget.totalInputTokens(),
                    budget.totalOutputTokens(),
                    budget.totalCachedInputTokens());
            renderer().updateStatus(StatusInfo.tokens(
                    model,
                    contextWindow,
                    estimateCurrentContextTokens(),
                    budget == null ? 0L : budget.totalInputTokens(),
                    budget == null ? 0L : budget.totalOutputTokens(),
                    budget == null ? 0L : budget.totalCachedInputTokens(),
                    cost,
                    hitl,
                    elapsed,
                    phase == null || phase.isBlank()
                            ? (totalTokens > 0 || elapsed > 0 ? "running" : "idle")
                            : phase));
        } catch (Exception e) {
            log.debug("status push failed", e);
        }
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
            emitToolResultSummary(result);
        }
        return results;
    }

    private void emitToolResultSummary(ToolExecutionResult result) {
        if (result == null || result.name() == null) {
            return;
        }
        String summary = switch (result.name()) {
            case "web_search" -> webSearchSummary(result);
            case "web_fetch" -> webFetchSummary(result);
            default -> "";
        };
        if (!summary.isBlank()) {
            renderer().stream().println(AnsiStyle.subtle("  → " + summary));
        }
    }

    private String webSearchSummary(ToolExecutionResult result) {
        String text = result.result() == null ? "" : result.result();
        boolean stepSearch = isStepSearchResult(text);
        if (text.startsWith("搜索失败") || text.startsWith("⚠️") || text.contains("未找到相关结果")) {
            return compactOneLine(text, 120);
        }
        long count = text.lines().filter(line -> line.matches("^\\d+\\.\\s+.*")).count();
        String query = extractJsonArg(result.argumentsJson(), "query");
        String label = query.isBlank() ? "搜索结果" : "搜索 \"" + query + "\"";
        if (stepSearch) {
            label = "StepSearch · " + label;
        }
        return count > 0
                ? label + " 返回 " + count + " 条结果"
                : label + " 已返回结果";
    }

    private String webFetchSummary(ToolExecutionResult result) {
        String text = result.result() == null ? "" : result.result();
        boolean stepSearch = isStepSearchResult(text);
        String url = extractJsonArg(result.argumentsJson(), "url");
        String target = url.isBlank() ? "页面" : compactOneLine(url.replaceFirst("^https?://", ""), 80);
        String verb = stepSearch ? "StepSearch · 抓取 " : "抓取 ";
        if (text.startsWith("抓取失败") || text.startsWith("❌")) {
            return verb + target + " 失败: " + compactOneLine(text, 100);
        }
        String title = text.lines()
                .filter(line -> line.startsWith("📄 标题:"))
                .map(line -> line.substring("📄 标题:".length()).trim())
                .findFirst()
                .orElse("");
        String length = text.lines()
                .filter(line -> line.startsWith("📏 正文"))
                .findFirst()
                .orElse("");
        if (!title.isBlank() && !length.isBlank()) {
            return verb + target + " 完成: " + title + " · " + length.replace("📏 ", "");
        }
        if (!title.isBlank()) {
            return verb + target + " 完成: " + title;
        }
        return verb + target + " 完成";
    }

    private boolean isStepSearchResult(String text) {
        return text != null && text.startsWith("🔍 [StepSearch]")
                || text != null && text.startsWith("🌐 [StepSearch]");
    }

    private String extractJsonArg(String json, String key) {
        if (json == null || json.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        try {
            return new ObjectMapper().readTree(json).path(key).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String compactOneLine(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String value = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("")
                .replaceAll("\\s+", " ");
        return value.length() > maxLength ? value.substring(0, Math.max(0, maxLength - 3)) + "..." : value;
    }

    private void appendImageToolMessages(List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return;
        }
        for (ToolExecutionResult result : toolResults) {
            if (!result.hasImageParts()) {
                continue;
            }
            List<LlmClient.ContentPart> parts = new ArrayList<>();
            parts.add(LlmClient.ContentPart.text("工具 " + result.name() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
            parts.addAll(result.imageParts());
            conversationHistory.add(LlmClient.Message.user(parts));
        }
    }

    private String formatUserFacingResponse(String reasoningContent, String answer) {
        String normalizedReasoning = reasoningContent == null ? "" : reasoningContent.trim();
        String normalizedAnswer = answer == null ? "" : answer.trim();

        if (!renderer().rendersReasoning() || normalizedReasoning.isEmpty()) {
            return normalizedAnswer;
        }
        if (normalizedAnswer.isEmpty()) {
            return "🧠 思考过程:\n" + normalizedReasoning;
        }
        return "🧠 思考过程:\n" + normalizedReasoning + "\n\n▪ " + normalizedAnswer;
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

    /**
     * 流式输出渲染器，将 reasoning_content 与 content 分区展示。
     *
     * 服务器可能把 reasoning_content 切成多段下发，甚至在 content 开始之后追加 reasoning；
     * 终端是线性的，无法回头修改已写出的文字。渲染策略：
     *
     * 1. 在 content 出现之前，只要 reasoning 有实质内容（非空白），就立刻流式打印在"🧠 思考过程"下
     *    同一次用户输入只打印一次"🧠 思考过程"标题；工具调用后的后续推理继续归在同一块下
     * 2. 仅空白的 reasoning delta 会先暂存，不触发标题——避免出现"空的思考过程"
     * 3. content 一出现就收尾 reasoning 区，用低调标记进入正文并流式输出 content
     * 4. 如果 content 启动之后又收到 reasoning（服务器把思考内容追加在答案之后），
     *    缓冲到 lateReasoning，最终在 finish() 用"🧠 补充思考"标题独立展示，不会污染回复区
     */
    private static final class StreamRenderer implements LlmClient.StreamListener {
        private final Renderer renderer;
        private final PrintStream boundOut;  // null 表示延迟读取 System.out（保持旧测试兼容）
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder visibleReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningHeadingPrinted;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean thinkingQuotePrinted;
        private boolean streamedOutput;

        StreamRenderer() {
            this.renderer = null;
            this.boundOut = null;
        }

        StreamRenderer(PrintStream out) {
            this.renderer = null;
            this.boundOut = out;
        }

        StreamRenderer(Renderer renderer) {
            this.renderer = renderer;
            this.boundOut = renderer == null ? null : renderer.stream();
        }

        private PrintStream out() {
            return boundOut != null ? boundOut : System.out;
        }

        private boolean hasThinkingPanel() {
            return renderer != null && renderer.supportsThinkingPanel();
        }

        private boolean rendersReasoning() {
            return renderer == null || renderer.rendersReasoning();
        }

        private void beginThinking() {
            if (hasThinkingPanel()) {
                renderer.beginThinking("Thinking");
            }
        }

        private void clearThinkingPanel() {
            if (hasThinkingPanel()) {
                renderer.endThinking();
                pendingReasoning.setLength(0);
            }
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!rendersReasoning()) {
                return;
            }
            if (contentStarted) {
                // content 已开始，无法回头；缓冲到"补充思考"
                lateReasoning.append(delta);
                return;
            }
            visibleReasoning.append(delta);
            if (hasThinkingPanel()) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                renderer.appendThinking(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
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
                reasoningRenderer = newMarkdownRenderer();
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
            } else {
                if (hasThinkingPanel()) {
                    renderer.appendThinking(delta);
                } else {
                    reasoningRenderer.append(delta);
                }
            }
            out().flush();
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (hasThinkingPanel()) {
                    finishThinkingPanelAndPrintQuote();
                } else if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out().println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    printReasoningHeadingIfNeeded();
                    TerminalMarkdownRenderer r = newMarkdownRenderer();
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out().println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                out().print(AnsiStyle.answerMarker() + " ");
                contentRenderer = newMarkdownRenderer();
                contentStarted = true;
                streamedOutput = true;
            }
            contentRenderer.append(delta);
            if (renderer != null) {
                renderer.appendAssistantContentDelta(delta);
            }
            out().flush();
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void resetBetweenIterations() {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            }
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            } else if (!hasThinkingPanel()) {
                flushPendingReasoning();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            if (renderer != null) {
                renderer.finishAssistantContent();
            }
            String late = lateReasoning.toString().trim();
            if (rendersReasoning() && !late.isEmpty()) {
                out().println();
                out().println(AnsiStyle.heading("🧠 补充思考"));
                TerminalMarkdownRenderer r = newMarkdownRenderer();
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            pendingReasoning.setLength(0);
            visibleReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            thinkingQuotePrinted = false;
            if (streamedOutput) {
                out().println();
            }
        }

        private void finish() {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            }
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
            } else if (!hasThinkingPanel()) {
                flushPendingReasoning();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
            }
            if (renderer != null) {
                renderer.finishAssistantContent();
            }
            String late = lateReasoning.toString().trim();
            if (rendersReasoning() && !late.isEmpty()) {
                out().println();
                out().println(AnsiStyle.heading("🧠 补充思考"));
                TerminalMarkdownRenderer r = newMarkdownRenderer();
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            if (streamedOutput) {
                out().println();
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
            TerminalMarkdownRenderer renderer = newMarkdownRenderer();
            renderer.append(pending);
            renderer.finish();
            pendingReasoning.setLength(0);
            streamedOutput = true;
        }

        private TerminalMarkdownRenderer newMarkdownRenderer() {
            if (renderer != null) {
                return new TerminalMarkdownRenderer(out(), renderer::terminalColumns);
            }
            return new TerminalMarkdownRenderer(out());
        }

        private void finishThinkingPanelAndPrintQuote() {
            if (!hasThinkingPanel()) {
                return;
            }
            if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                renderer.appendThinking(pendingReasoning.toString());
            }
            renderer.endThinking();
            pendingReasoning.setLength(0);
            printThinkingQuoteIfNeeded();
        }

        private void printThinkingQuoteIfNeeded() {
            if (thinkingQuotePrinted) {
                return;
            }
            if (!rendersReasoning()) {
                return;
            }
            String reasoning = visibleReasoning.toString()
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim();
            if (reasoning.isEmpty()) {
                return;
            }
            out().println(AnsiStyle.thinking("Thinking..."));
            for (String line : reasoning.split("\\R+")) {
                String normalized = line.replaceAll("\\s+", " ").trim();
                if (!normalized.isEmpty()) {
                    out().println(AnsiStyle.subtle("│ " + normalized));
                }
            }
            out().println();
            thinkingQuotePrinted = true;
            streamedOutput = true;
        }

        private void printReasoningHeadingIfNeeded() {
            if (!reasoningHeadingPrinted) {
                if (!rendersReasoning()) {
                    return;
                }
                out().println(AnsiStyle.heading("🧠 思考过程"));
                reasoningHeadingPrinted = true;
            }
        }
    }
}
