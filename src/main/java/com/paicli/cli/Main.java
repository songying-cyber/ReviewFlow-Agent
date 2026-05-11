package com.paicli.cli;

import com.paicli.agent.Agent;
import com.paicli.agent.AgentOrchestrator;
import com.paicli.agent.PlanExecuteAgent;
import com.paicli.browser.BrowserAuditMetadata;
import com.paicli.browser.BrowserConnectivityCheck;
import com.paicli.browser.BrowserGuard;
import com.paicli.browser.BrowserMode;
import com.paicli.browser.BrowserSession;
import com.paicli.browser.SensitivePagePolicy;
import com.paicli.config.PaiCliConfig;
import com.paicli.hitl.HitlToolRegistry;
import com.paicli.hitl.TerminalHitlHandler;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClientFactory;
import com.paicli.mcp.McpServer;
import com.paicli.mcp.McpServerManager;
import com.paicli.mcp.McpServerStatus;
import com.paicli.mcp.mention.AtMentionExpander;
import com.paicli.plan.ExecutionPlan;
import com.paicli.rag.CodeIndex;
import com.paicli.hitl.ApprovalPolicy;
import com.paicli.policy.AuditLog;
import com.paicli.rag.CodeRetriever;
import com.paicli.rag.CodeRelation;
import com.paicli.rag.SearchResultFormatter;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.Attributes;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.MaskingCallback;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Reference;
import org.jline.utils.NonBlockingReader;
import org.jline.keymap.KeyMap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * PaiCLI v15.0.0 - Skill-Driven Agent CLI
 * 支持 ReAct、Plan-and-Execute、Memory、RAG、Multi-Agent、HITL、并行工具调用、多模型切换、MCP、CDP 会话复用
 * 第 15 期新增：Skill 系统（三层加载 + load_skill 工具 + SkillContextBuffer 注入）、内置 web-access skill
 * HITL 增强：路径围栏（PathGuard）、命令快速拒绝（CommandGuard）、操作审计链（AuditLog）—— 见 com.paicli.policy
 */
public class Main {
    private static final String VERSION = "15.0.0";
    private static final String ENV_FILE = ".env";
    private static final String LOG_DIR_PROPERTY = "paicli.log.dir";
    private static final String LOG_LEVEL_PROPERTY = "paicli.log.level";
    private static final String LOG_MAX_HISTORY_PROPERTY = "paicli.log.maxHistory";
    private static final String LOG_MAX_FILE_SIZE_PROPERTY = "paicli.log.maxFileSize";
    private static final String LOG_TOTAL_SIZE_CAP_PROPERTY = "paicli.log.totalSizeCap";
    private static final String BRACKETED_PASTE_BEGIN = "[200~";
    private static final String BRACKETED_PASTE_END = "\u001b[201~";
    private static final String ARROW_UP = "[A";
    private static final String ARROW_DOWN = "[B";
    private static final String APP_ARROW_UP = "OA";
    private static final String APP_ARROW_DOWN = "OB";
    private static final int CTRL_O = 15;
    private static final String DEFAULT_CHROME_DEVTOOLS_MCP_JSON = """
            {
              "mcpServers": {
                "chrome-devtools": {
                  "command": "npx",
                  "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
                }
              }
            }
            """;

    enum EscapeSequenceType {
        STANDALONE_ESC,
        BRACKETED_PASTE,
        CONTROL_SEQUENCE,
        OTHER
    }

    private record PromptInput(String text, boolean canceled) {
        static PromptInput submitted(String text) {
            return new PromptInput(text, false);
        }

        static PromptInput canceledInput() {
            return new PromptInput("", true);
        }
    }

    private record PrefillResult(String seedBuffer, boolean canceled, boolean submitted) {
        static PrefillResult canceledInput() {
            return new PrefillResult("", true, false);
        }

        static PrefillResult submittedInput() {
            return new PrefillResult("", false, true);
        }

        static PrefillResult seed(String seedBuffer) {
            return new PrefillResult(seedBuffer, false, false);
        }
    }

    private record KeyReadResult(Integer key, boolean ignoredControlSequence) {
        static KeyReadResult keyPressed(int key) {
            return new KeyReadResult(key, false);
        }

        static KeyReadResult ignoredSequence() {
            return new KeyReadResult(null, true);
        }

        static KeyReadResult unavailable() {
            return new KeyReadResult(null, false);
        }
    }

    public static void main(String[] args) {
        printBanner();
        configureLogging();

        PaiCliConfig config = PaiCliConfig.load();
        LlmClient llmClient = LlmClientFactory.createFromConfig(config);
        if (llmClient == null) {
            System.err.println("❌ 错误: 未找到可用的 API Key");
            System.err.println("请在 .env 文件中添加 GLM_API_KEY 或 DEEPSEEK_API_KEY");
            System.exit(1);
        }

        System.out.println("✅ 已加载模型: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")\n");

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            TerminalHitlHandler hitlHandler = new TerminalHitlHandler(false);
            HitlToolRegistry hitlToolRegistry = new HitlToolRegistry(hitlHandler);
            BrowserSession browserSession = new BrowserSession();
            BrowserConnectivityCheck browserConnectivityCheck = new BrowserConnectivityCheck();
            hitlToolRegistry.setBrowserGuard(new BrowserGuard(browserSession, new SensitivePagePolicy()));
            McpServerManager mcpServerManager = new McpServerManager(hitlToolRegistry, Path.of("."));
            hitlToolRegistry.setBrowserConnector(new com.paicli.browser.BrowserConnector() {
                @Override
                public String status() {
                    return handleBrowserCommand("status", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }

                @Override
                public String connectDefault() {
                    return handleBrowserCommand("connect", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }

                @Override
                public String disconnect() {
                    return handleBrowserCommand("disconnect", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }
            });
            try {
                McpConfigBootstrapResult bootstrapResult = ensureDefaultMcpConfig(Path.of(System.getProperty("user.home")));
                if (!bootstrapResult.message().isBlank()) {
                    System.out.println(bootstrapResult.message());
                }
                mcpServerManager.loadConfiguredServers();
                if (!mcpServerManager.servers().isEmpty()) {
                    System.out.println("🔌 启动 MCP server（" + mcpServerManager.servers().size() + " 个）...");
                }
                mcpServerManager.startAll(System.out);
                Runtime.getRuntime().addShutdownHook(new Thread(mcpServerManager::close, "paicli-mcp-shutdown"));
                System.out.println(mcpServerManager.startupSummary());
                System.out.println();
            } catch (Exception e) {
                System.out.println("⚠️ MCP 初始化失败: " + e.getMessage());
                System.out.println("   可检查 ~/.paicli/mcp.json 或 .paicli/mcp.json\n");
            }
            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new PaiCliCompleter(mcpServerManager::resourceCandidates))
                    .build();
            lineReader.option(LineReader.Option.BRACKETED_PASTE, true);
            lineReader.option(LineReader.Option.AUTO_LIST, true);
            lineReader.option(LineReader.Option.AUTO_MENU, true);
            configureSlashCommandHint(lineReader);
            AtMentionExpander mentionExpander = new AtMentionExpander(mcpServerManager);

            // === Skill 系统初始化 ===
            Path home = Path.of(System.getProperty("user.home"));
            Path skillsCacheDir = home.resolve(".paicli/skills-cache");
            Path userSkillsDir = home.resolve(".paicli/skills");
            Path projectSkillsDir = Path.of(".paicli/skills").toAbsolutePath();
            try {
                new com.paicli.skill.SkillBuiltinExtractor(skillsCacheDir).extractAll();
            } catch (Exception e) {
                System.err.println("⚠️ 内置 skill 解压失败: " + e.getMessage());
            }
            com.paicli.skill.SkillStateStore skillStateStore = new com.paicli.skill.SkillStateStore(home.resolve(".paicli/skills.json"));
            com.paicli.skill.SkillRegistry skillRegistry = new com.paicli.skill.SkillRegistry(
                    skillsCacheDir, userSkillsDir, projectSkillsDir, skillStateStore);
            skillRegistry.reload();
            com.paicli.skill.SkillContextBuffer skillContextBuffer = new com.paicli.skill.SkillContextBuffer();
            hitlToolRegistry.setSkillRegistry(skillRegistry);
            hitlToolRegistry.setSkillContextBuffer(skillContextBuffer);
            System.out.println(SkillCommandHandler.startupSummary(skillRegistry));

            Agent reactAgent = new Agent(llmClient, hitlToolRegistry);
            reactAgent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
            reactAgent.setSkillRegistry(skillRegistry);
            reactAgent.setSkillContextBuffer(skillContextBuffer);
            System.out.println("🔄 使用 ReAct 模式\n");
            boolean nextTaskUsePlanMode = false;
            boolean nextTaskUseTeamMode = false;

            printStartupHints();

            while (true) {
                PromptInput promptInput;
                try {
                    promptInput = readPromptInput(terminal, lineReader, nextTaskUsePlanMode || nextTaskUseTeamMode);
                } catch (UserInterruptException e) {
                    continue;  // Ctrl+C 跳过
                } catch (EndOfFileException e) {
                    break;  // Ctrl+D 退出
                }

                if (promptInput.canceled()) {
                    if (nextTaskUsePlanMode) {
                        nextTaskUsePlanMode = false;
                        System.out.println("↩️ 已取消待执行的 Plan-and-Execute，回到默认 ReAct。\n");
                    }
                    if (nextTaskUseTeamMode) {
                        nextTaskUseTeamMode = false;
                        System.out.println("↩️ 已取消待执行的 Multi-Agent，回到默认 ReAct。\n");
                    }
                    continue;
                }

                String input = promptInput.text().trim();

                if (input.isEmpty()) {
                    continue;
                }

                CliCommandParser.ParsedCommand command = CliCommandParser.parse(input);
                switch (command.type()) {
                    case UNKNOWN_COMMAND -> {
                        System.out.println("❌ 未知命令: " + command.payload());
                        printSlashCommandHelp();
                        continue;
                    }
                    case EXIT -> {
                        System.out.println("\n👋 再见!");
                        return;
                    }
                    case CANCEL -> {
                        System.out.println("当前没有正在运行的任务。\n");
                        continue;
                    }
                    case CLEAR -> {
                        reactAgent.clearHistory();
                        hitlHandler.clearApprovedAll();
                        System.out.println("🗑️ 当前对话历史已清空，长期记忆保持不变\n");
                        continue;
                    }
                    case CONTEXT_STATUS -> {
                        System.out.println("📋 上下文状态：");
                        System.out.println(reactAgent.getContextStatus());
                        System.out.println();
                        continue;
                    }
                    case MEMORY_STATUS -> {
                        System.out.println("📋 记忆系统状态：");
                        System.out.println(reactAgent.getMemoryManager().getSystemStatus());
                        System.out.println("   /memory clear - 清空长期记忆");
                        System.out.println("   /save <事实> - 手动保存到长期记忆");
                        System.out.println();
                        continue;
                    }
                    case MEMORY_CLEAR -> {
                        reactAgent.getMemoryManager().clearLongTerm();
                        System.out.println("🧹 长期记忆已清空\n");
                        System.out.println();
                        continue;
                    }
                    case MEMORY_SAVE -> {
                        String fact = command.payload();
                        if (fact == null || fact.isEmpty()) {
                            System.out.println("❌ 请提供要保存的内容，例如 /save 这个项目使用Java 17\n");
                        } else {
                            reactAgent.getMemoryManager().storeFact(fact);
                            System.out.println("💾 已保存到长期记忆: " + fact + "\n");
                        }
                        continue;
                    }
                    case SWITCH_PLAN -> {
                        if (command.payload() == null || command.payload().isEmpty()) {
                            nextTaskUsePlanMode = true;
                            System.out.println("📋 下一条任务将使用 Plan-and-Execute 模式，输入任务前按 ESC 可取消，执行完成后自动回到默认 ReAct。\n");
                            continue;
                        }
                        input = command.payload();
                    }
                    case SWITCH_TEAM -> {
                        if (command.payload() == null || command.payload().isEmpty()) {
                            nextTaskUseTeamMode = true;
                            System.out.println("👥 下一条任务将使用 Multi-Agent 协作模式（规划者 + 执行者 + 检查者），输入任务前按 ESC 可取消，执行完成后自动回到默认 ReAct。\n");
                            continue;
                        }
                        input = command.payload();
                    }
                    case SWITCH_MODEL -> {
                        String provider = command.payload();
                        if (provider == null || provider.isEmpty()) {
                            System.out.println("🤖 当前模型: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")");
                            System.out.println("   可用模型：glm, deepseek");
                            System.out.println("   /model glm     - 切换到 GLM-5.1");
                            System.out.println("   /model deepseek - 切换到 DeepSeek V4\n");
                        } else {
                            LlmClient newClient = LlmClientFactory.create(provider, config);
                            if (newClient == null) {
                                System.out.println("❌ 切换失败：未配置 " + provider + " 的 API Key\n");
                            } else {
                                llmClient = newClient;
                                config.setDefaultProvider(provider);
                                config.save();
                                reactAgent.setLlmClient(llmClient);
                                System.out.println("✅ 已切换到: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")");
                                System.out.println("   上下文策略: " + reactAgent.getMemoryManager().getContextProfile().summary());
                                System.out.println("   对话上下文已保留，使用 /clear 可清空\n");
                            }
                        }
                        continue;
                    }
                    case SWITCH_HITL -> {
                        String payload = command.payload();
                        if ("on".equals(payload)) {
                            hitlHandler.setEnabled(true);
                            System.out.println("🔒 HITL 审批已启用：write_file / execute_command / create_project 执行前将请求人工确认\n");
                        } else if ("off".equals(payload)) {
                            hitlHandler.setEnabled(false);
                            hitlHandler.clearApprovedAll();
                            System.out.println("🔓 HITL 审批已关闭：危险操作将直接执行\n");
                        } else {
                            String status = hitlHandler.isEnabled() ? "启用" : "关闭";
                            System.out.println("🔒 HITL 当前状态：" + status);
                            System.out.println("   /hitl on  - 启用人工审批");
                            System.out.println("   /hitl off - 关闭人工审批\n");
                        }
                        continue;
                    }
                    case POLICY_STATUS -> {
                        printPolicyStatus(reactAgent);
                        continue;
                    }
                    case AUDIT_TAIL -> {
                        printAuditTail(reactAgent, command.payload());
                        continue;
                    }
                    case MCP_LIST -> {
                        System.out.println(mcpServerManager.formatStatus());
                        System.out.println();
                        continue;
                    }
                    case MCP_RESTART -> {
                        printMcpCommandResult(mcpServerManager.restart(command.payload()));
                        continue;
                    }
                    case MCP_LOGS -> {
                        printMcpCommandResult(mcpServerManager.logs(command.payload()));
                        continue;
                    }
                    case MCP_DISABLE -> {
                        printMcpCommandResult(mcpServerManager.disable(command.payload()));
                        continue;
                    }
                    case MCP_ENABLE -> {
                        printMcpCommandResult(mcpServerManager.enable(command.payload()));
                        continue;
                    }
                    case MCP_RESOURCES -> {
                        printMcpCommandResult(mcpServerManager.resources(command.payload()));
                        continue;
                    }
                    case MCP_PROMPTS -> {
                        printMcpCommandResult(mcpServerManager.prompts(command.payload()));
                        continue;
                    }
                    case BROWSER -> {
                        printMcpCommandResult(handleBrowserCommand(
                                command.payload(),
                                browserSession,
                                browserConnectivityCheck,
                                mcpServerManager,
                                hitlToolRegistry,
                                hitlHandler));
                        continue;
                    }
                    case SKILL_LIST -> {
                        System.out.println(SkillCommandHandler.list(skillRegistry));
                        continue;
                    }
                    case SKILL_SHOW -> {
                        System.out.println(SkillCommandHandler.show(skillRegistry, command.payload()));
                        continue;
                    }
                    case SKILL_ON -> {
                        System.out.println(SkillCommandHandler.enable(skillRegistry, skillStateStore, command.payload()));
                        continue;
                    }
                    case SKILL_OFF -> {
                        System.out.println(SkillCommandHandler.disable(skillRegistry, skillStateStore, command.payload()));
                        continue;
                    }
                    case SKILL_RELOAD -> {
                        skillRegistry.reload();
                        System.out.println("🔄 已重新扫描 skill 目录");
                        System.out.println(SkillCommandHandler.startupSummary(skillRegistry));
                        System.out.println("✅ 下一轮 LLM 调用生效");
                        continue;
                    }
                    case INDEX_CODE -> {
                        String indexPath = command.payload() != null ? command.payload() : ".";
                        System.out.println("📦 正在索引代码库: " + indexPath);
                        CodeIndex indexer = new CodeIndex();
                        CodeIndex.IndexResult result = indexer.index(indexPath);
                        System.out.println(result.message() + "\n");

                        // 同步项目路径到 ToolRegistry，让 search_code 工具可以正常工作
                        String absPath = new File(indexPath).getAbsolutePath();
                        reactAgent.getToolRegistry().setProjectPath(absPath);
                        continue;
                    }
                    case SEARCH_CODE -> {
                        String query = command.payload();
                        if (query == null || query.isEmpty()) {
                            System.out.println("❌ 请提供检索关键词，例如 /search 用户登录实现\n");
                            continue;
                        }
                        System.out.println("🔍 检索: " + query);
                        try (CodeRetriever retriever = new CodeRetriever(".")) {
                            var stats = retriever.getStats();
                            if (stats.chunkCount() == 0) {
                                System.out.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
                                continue;
                            }
                            List<com.paicli.rag.VectorStore.SearchResult> results = retriever.hybridSearch(query, 5);
                            if (results.isEmpty()) {
                                System.out.println("📭 未找到相关代码\n");
                            } else {
                                System.out.println(SearchResultFormatter.formatForCli(query, results) + "\n");
                            }
                        } catch (Exception e) {
                            System.out.println("❌ 检索失败: " + e.getMessage() + "\n");
                        }
                        continue;
                    }
                    case GRAPH_QUERY -> {
                        String className = command.payload();
                        if (className == null || className.isEmpty()) {
                            System.out.println("❌ 请提供类名，例如 /graph Main\n");
                            continue;
                        }
                        System.out.println("🕸️ 查询类关系图谱: " + className);
                        try (CodeRetriever retriever = new CodeRetriever(".")) {
                            var stats = retriever.getStats();
                            if (stats.chunkCount() == 0) {
                                System.out.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
                                continue;
                            }
                            List<CodeRelation> relations = retriever.getRelationGraph(className);
                            if (relations.isEmpty()) {
                                System.out.println("📭 未找到相关关系\n");
                            } else {
                                System.out.println("📋 找到 " + relations.size() + " 条关系:\n");
                                for (CodeRelation rel : relations) {
                                    String arrow = rel.relationType().equals("contains") ? "├── contains -->"
                                            : rel.relationType().equals("extends") ? "└── extends -->"
                                            : rel.relationType().equals("implements") ? "└── implements -->"
                                            : rel.relationType().equals("calls") ? "├── calls -->"
                                            : "├── " + rel.relationType() + " -->";
                                    System.out.printf("   %s %s [%s]%n", rel.fromName(), arrow,
                                            rel.toName() != null ? rel.toName() : "unknown");
                                }
                                System.out.println();
                            }
                        } catch (Exception e) {
                            System.out.println("❌ 查询失败: " + e.getMessage() + "\n");
                        }
                        continue;
                    }
                    case NONE -> {
                    }
                }

                // 运行 Agent
                input = mentionExpander.expand(input);
                System.out.println();
                final String taskInput = input;
                Callable<String> runTask;
                if (nextTaskUsePlanMode || command.type() == CliCommandParser.CommandType.SWITCH_PLAN) {
                    LlmClient activeClient = llmClient;
                    runTask = () -> {
                        PlanExecuteAgent planAgent = createPlanAgent(activeClient, reactAgent, terminal, lineReader);
                        planAgent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
                        planAgent.setSkillRegistry(skillRegistry);
                        planAgent.setSkillContextBuffer(skillContextBuffer);
                        return planAgent.run(taskInput);
                    };
                } else if (nextTaskUseTeamMode || command.type() == CliCommandParser.CommandType.SWITCH_TEAM) {
                    LlmClient activeClient = llmClient;
                    runTask = () -> {
                        AgentOrchestrator orchestrator = createTeamAgent(activeClient, reactAgent);
                        orchestrator.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
                        orchestrator.setSkillSystem(skillRegistry, skillContextBuffer);
                        return orchestrator.run(taskInput);
                    };
                } else {
                    runTask = () -> reactAgent.run(taskInput);
                }
                String response = runWithCancelSupport(terminal, runTask);
                nextTaskUsePlanMode = false;
                nextTaskUseTeamMode = false;
                if (response != null && !response.isBlank()) {
                    System.out.println(response);
                    System.out.println();
                }
            }

        } catch (IOException e) {
            System.err.println("❌ 终端初始化失败: " + e.getMessage());
            System.exit(1);
        }

        System.out.println("\n👋 再见!");
    }

    static PlanExecuteAgent createPlanAgent(LlmClient llmClient, Agent reactAgent,
                                            PlanExecuteAgent.PlanReviewHandler reviewHandler) {
        return new PlanExecuteAgent(
                llmClient,
                reactAgent.getToolRegistry(),
                reactAgent.getMemoryManager(),
                reviewHandler
        );
    }

    private static PlanExecuteAgent createPlanAgent(LlmClient llmClient, Agent reactAgent,
                                                    Terminal terminal, LineReader lineReader) {
        System.out.println("📋 使用 Plan-and-Execute 模式\n");
        return createPlanAgent(llmClient, reactAgent, createPlanReviewHandler(terminal, lineReader));
    }

    private static AgentOrchestrator createTeamAgent(LlmClient llmClient, Agent reactAgent) {
        System.out.println("👥 使用 Multi-Agent 协作模式\n");
        return new AgentOrchestrator(llmClient, reactAgent.getToolRegistry(), reactAgent.getMemoryManager());
    }

    private static String runWithCancelSupport(Terminal terminal, Callable<String> task) {
        CancellationToken token = CancellationContext.startRun();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-agent-runner");
            thread.setDaemon(true);
            return thread;
        });
        Future<String> future = executor.submit(task);
        // 进入 raw mode 监听 ESC：raw mode 关 ICANON / ECHO / IEXTEN 但保留 ISIG，所以 Ctrl+C 仍能终止 PaiCLI。
        Attributes original = null;
        boolean hintPrinted = false;
        try {
            if (terminal != null) {
                try {
                    original = terminal.enterRawMode();
                } catch (Exception ignored) {
                    // raw mode 进入失败（非交互终端等），降级为不监听 ESC，靠 Ctrl+C 退出。
                }
            }
            while (!future.isDone()) {
                if (!hintPrinted) {
                    System.out.println("   运行中按 ESC 取消当前任务。");
                    hintPrinted = true;
                }
                if (original != null && readEscCancel(terminal)) {
                    token.cancel();
                    future.cancel(true);
                    executor.shutdownNow();
                    return "⏹️ 已请求取消当前任务。";
                }
                try {
                    return future.get(150, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException ignored) {
                    // 继续监听 ESC
                }
            }
            return future.get();
        } catch (CancellationException e) {
            return "⏹️ 已取消当前任务。";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            token.cancel();
            future.cancel(true);
            return "⏹️ 已取消当前任务。";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause == null || cause.getMessage() == null ? "未知错误" : cause.getMessage();
            return "❌ 执行失败: " + message;
        } finally {
            if (terminal != null && original != null) {
                try {
                    terminal.setAttributes(original);
                } catch (Exception ignored) {
                }
            }
            CancellationContext.clear(token);
            executor.shutdownNow();
        }
    }

    /**
     * 任务运行期间监听 ESC 按键。raw mode 下 ESC 字节是 0x1b（27）。
     *
     * 关键陷阱：方向键 / Home / End 等由 ESC + 控制序列组成（如 ESC[A），不能误判为单 ESC 取消。
     * 复用 {@link #readInputBurst} + {@link #classifyEscapeSequence}：
     * - STANDALONE_ESC（孤立的 ESC）→ 用户取消
     * - CONTROL_SEQUENCE / BRACKETED_PASTE / OTHER → 丢弃，不取消
     */
    static boolean readEscCancel(Terminal terminal) {
        if (terminal == null) {
            return false;
        }
        try {
            NonBlockingReader reader = terminal.reader();
            int next = reader.read(50);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                return false;
            }
            String escTail = next == 27 ? readInputBurst(terminal, 80, 20, 120) : null;
            if (next != 27) {
                // 非 ESC 输入，drain 这一轮残余字节避免堆积，但不触发取消。
                while (true) {
                    int more = reader.read(1);
                    if (more == NonBlockingReader.READ_EXPIRED || more < 0) {
                        break;
                    }
                }
            }
            return decideEscCancel(next, escTail);
        } catch (Exception ignored) {
            // 监听是 best-effort；失败不能影响任务执行。
            return false;
        }
    }

    /**
     * ESC 取消判断的纯函数版（不依赖终端 IO，便于单测）。
     *
     * @param firstByte ESC=27 触发判断；其他字节直接返回 false
     * @param escTail  紧跟 ESC 之后的字节序列（不含 ESC 本身）；null / 空 → 单 ESC 取消
     */
    static boolean decideEscCancel(int firstByte, String escTail) {
        if (firstByte != 27) {
            return false;
        }
        return classifyEscapeSequence(escTail) == EscapeSequenceType.STANDALONE_ESC;
    }

    private static PromptInput readPromptInput(Terminal terminal, LineReader lineReader, boolean allowEscCancel)
            throws UserInterruptException, EndOfFileException {
        if (!allowEscCancel) {
            return PromptInput.submitted(lineReader.readLine("👤 你: "));
        }

        String prompt = "👤 你: ";
        System.out.print(prompt);
        System.out.flush();

        PrefillResult prefill = readPrefillInputFromTerminal(terminal, lineReader);
        if (prefill == null) {
            return PromptInput.submitted(lineReader.readLine(""));
        }

        if (prefill.canceled()) {
            System.out.println();
            return PromptInput.canceledInput();
        }

        if (prefill.submitted()) {
            System.out.println();
            return PromptInput.submitted("");
        }

        return PromptInput.submitted(lineReader.readLine("", null, (MaskingCallback) null, prefill.seedBuffer()));
    }

    private static PlanExecuteAgent.PlanReviewHandler createPlanReviewHandler(Terminal terminal, LineReader lineReader) {
        return (String goal, ExecutionPlan plan) -> {
            boolean expanded = false;
            System.out.println(plan.summarize());
            System.out.println("📝 计划已生成。");
            System.out.println("   - 回车：按当前计划执行");
            System.out.println("   - Ctrl+O：展开完整计划");
            System.out.println("   - ESC：折叠或取消本次计划");
            System.out.println("   - I：输入补充要求后重新规划\n");

            while (true) {
                KeyReadResult keyReadResult = readSingleKeyFromTerminal(terminal);
                if (keyReadResult.ignoredControlSequence()) {
                    continue;
                }

                Integer key = keyReadResult.key();
                if (key != null) {
                    // Enter
                    if (key == '\n' || key == '\r') {
                        System.out.println();
                        return PlanExecuteAgent.PlanReviewDecision.execute();
                    }

                    // ESC (27)
                    if (key == 27) {
                        System.out.println();
                        if (expanded) {
                            expanded = false;
                            System.out.println(plan.summarize());
                            System.out.println("📁 已退出完整计划视图，继续按 Enter / Ctrl+O / ESC / I。\n");
                            continue;
                        }
                        return PlanExecuteAgent.PlanReviewDecision.cancel();
                    }

                    // I 或 i
                    if (key == 'i' || key == 'I') {
                        System.out.println();
                        String supplementInput = lineReader.readLine("补充> ").trim();
                        PlanReviewInputParser.Decision supplementDecision =
                                PlanReviewInputParser.parse(supplementInput);
                        return mapReviewDecision(supplementDecision);
                    }

                    // Ctrl+O
                    if (key == CTRL_O) {
                        System.out.println();
                        System.out.println(plan.visualize());
                        expanded = true;
                        System.out.println("👆 已展开完整计划，继续按 Enter / Ctrl+O / ESC / I。\n");
                        continue;
                    }

                    System.out.println();
                    System.out.println("未识别按键，请按 Enter / Ctrl+O / ESC / I。\n");
                    continue;
                }

                // 如果无法读取单键，回退到行输入模式
                String decisionInput = lineReader.readLine("操作/补充> ").trim();
                if (decisionInput.equalsIgnoreCase("/view")) {
                    System.out.println();
                    System.out.println(plan.visualize());
                    expanded = true;
                    System.out.println("👆 已展开完整计划，继续输入 Enter / /cancel / 补充要求。\n");
                    continue;
                }
                PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse(decisionInput);
                return mapReviewDecision(decision);
            }
        };
    }

    private static KeyReadResult readSingleKeyFromTerminal(Terminal terminal) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();
            try {
                int key = terminal.reader().read();
                if (key < 0) {
                    return KeyReadResult.unavailable();
                }

                if (key == 27) {
                    String escapeSequence = readInputBurst(terminal, 80, 20, 120);
                    EscapeSequenceType escapeSequenceType = classifyEscapeSequence(escapeSequence);
                    if (escapeSequenceType == EscapeSequenceType.STANDALONE_ESC) {
                        return KeyReadResult.keyPressed(27);
                    }
                    if (escapeSequenceType == EscapeSequenceType.CONTROL_SEQUENCE
                            || escapeSequenceType == EscapeSequenceType.BRACKETED_PASTE) {
                        return KeyReadResult.ignoredSequence();
                    }
                }

                return KeyReadResult.keyPressed(key);
            } finally {
                terminal.setAttributes(originalAttributes);
            }
        } catch (Exception e) {
            return KeyReadResult.unavailable();
        }
    }

    private static PrefillResult readPrefillInputFromTerminal(Terminal terminal, LineReader lineReader) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();
            try {
                int key = terminal.reader().read();
                if (key < 0) {
                    return null;
                }

                if (key == 27) {
                    return readEscapeInput(terminal, lineReader);
                }

                if (isSubmitKey(key)) {
                    return PrefillResult.submittedInput();
                }

                String rawInput = switch (key) {
                    case 8, 127 -> "";
                    default -> Character.toString((char) key);
                };

                rawInput += readInputBurst(terminal, 20, 25, 250);
                return PrefillResult.seed(prepareSeedBuffer(rawInput));
            } finally {
                terminal.setAttributes(originalAttributes);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static PrefillResult readEscapeInput(Terminal terminal, LineReader lineReader)
            throws IOException, InterruptedException {
        String sequence = readInputBurst(terminal, 80, 20, 300);
        EscapeSequenceType escapeSequenceType = classifyEscapeSequence(sequence);
        if (escapeSequenceType == EscapeSequenceType.STANDALONE_ESC) {
            return PrefillResult.canceledInput();
        }

        if (escapeSequenceType == EscapeSequenceType.BRACKETED_PASTE) {
            String pastedText = sequence.substring(BRACKETED_PASTE_BEGIN.length());
            while (!pastedText.contains(BRACKETED_PASTE_END)) {
                String burst = readInputBurst(terminal, 30, 25, 500);
                if (burst.isEmpty()) {
                    break;
                }
                pastedText += burst;
            }

            return PrefillResult.seed(prepareSeedBuffer(stripBracketedPasteEndMarker(pastedText)));
        }

        if (escapeSequenceType == EscapeSequenceType.CONTROL_SEQUENCE) {
            return PrefillResult.seed(seedBufferForHistoryNavigation(lineReader, sequence));
        }

        return PrefillResult.canceledInput();
    }

    private static String readInputBurst(Terminal terminal, long firstWaitMs, long idleWaitMs, long maxWaitMs)
            throws IOException, InterruptedException {
        NonBlockingReader reader = terminal.reader();
        StringBuilder buffer = new StringBuilder();
        long start = System.currentTimeMillis();
        long waitMs = firstWaitMs;

        while (System.currentTimeMillis() - start < maxWaitMs) {
            int next = reader.read(waitMs);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                break;
            }
            buffer.append((char) next);
            waitMs = idleWaitMs;
        }

        return buffer.toString();
    }

    static String prepareSeedBuffer(String rawInput) {
        if (rawInput == null || rawInput.isEmpty()) {
            return "";
        }
        return normalizeLineEndings(rawInput);
    }

    static List<String> startupHints() {
        return List.of(
                "输入你的问题或任务",
                "输入 '/' 查看命令",
                "输入 '@server:protocol://path' 可显式引用 MCP resource",
                "输入 '/skill list' 查看可用 skill；任务匹配时 LLM 会自动 load_skill 加载完整指引",
                "任务运行中按 ESC 取消当前任务",
                "默认模式是 ReAct"
        );
    }

    record SlashCommandHint(String insertText, String display, String description) {
    }

    static List<SlashCommandHint> slashCommandHints() {
        return List.of(
                new SlashCommandHint("/model", "/model", "查看当前模型"),
                new SlashCommandHint("/model glm", "/model glm", "切换到 GLM-5.1"),
                new SlashCommandHint("/model deepseek", "/model deepseek", "切换到 DeepSeek V4"),
                new SlashCommandHint("/plan", "/plan", "下一条任务使用 Plan-and-Execute 模式"),
                new SlashCommandHint("/plan ", "/plan <任务内容>", "直接用计划模式执行这条任务"),
                new SlashCommandHint("/team", "/team", "下一条任务使用 Multi-Agent 协作模式"),
                new SlashCommandHint("/team ", "/team <任务内容>", "直接用多 Agent 协作执行这条任务"),
                new SlashCommandHint("/hitl", "/hitl", "查看 HITL 状态"),
                new SlashCommandHint("/hitl on", "/hitl on", "启用危险操作人工审批"),
                new SlashCommandHint("/hitl off", "/hitl off", "关闭 HITL 审批"),
                new SlashCommandHint("/browser", "/browser", "查看浏览器会话状态"),
                new SlashCommandHint("/browser connect", "/browser connect", "复用已允许远程调试的登录态 Chrome"),
                new SlashCommandHint("/browser connect ", "/browser connect <port>", "旧式 CDP 端口连接"),
                new SlashCommandHint("/browser status", "/browser status", "查看浏览器会话状态"),
                new SlashCommandHint("/browser tabs", "/browser tabs", "查看 shared 模式真实 Chrome tab"),
                new SlashCommandHint("/browser disconnect", "/browser disconnect", "切回 isolated 浏览器模式"),
                new SlashCommandHint("/mcp", "/mcp", "查看 MCP server 状态"),
                new SlashCommandHint("/mcp restart ", "/mcp restart <name>", "重启 MCP server"),
                new SlashCommandHint("/mcp logs ", "/mcp logs <name>", "查看 MCP server 日志"),
                new SlashCommandHint("/mcp disable ", "/mcp disable <name>", "禁用 MCP server"),
                new SlashCommandHint("/mcp enable ", "/mcp enable <name>", "启用 MCP server"),
                new SlashCommandHint("/mcp resources ", "/mcp resources <name>", "查看 MCP resources"),
                new SlashCommandHint("/mcp prompts ", "/mcp prompts <name>", "查看 MCP prompts"),
                new SlashCommandHint("/policy", "/policy", "查看安全策略状态"),
                new SlashCommandHint("/audit", "/audit", "查看今日最近 10 条危险工具审计"),
                new SlashCommandHint("/audit ", "/audit [N]", "查看今日最近 N 条危险工具审计"),
                new SlashCommandHint("/index", "/index", "索引当前代码库"),
                new SlashCommandHint("/index ", "/index [路径]", "索引指定路径代码库"),
                new SlashCommandHint("/search ", "/search <查询>", "语义检索代码"),
                new SlashCommandHint("/graph ", "/graph <类名>", "查看代码关系图谱"),
                new SlashCommandHint("/clear", "/clear", "清空当前对话历史"),
                new SlashCommandHint("/context", "/context", "查看上下文和记忆状态"),
                new SlashCommandHint("/memory", "/memory", "查看记忆状态"),
                new SlashCommandHint("/memory clear", "/memory clear", "清空长期记忆"),
                new SlashCommandHint("/save ", "/save <事实内容>", "手动保存关键事实到长期记忆"),
                new SlashCommandHint("/skill", "/skill", "查看 skill 列表"),
                new SlashCommandHint("/skill list", "/skill list", "查看 skill 列表"),
                new SlashCommandHint("/skill show ", "/skill show <name>", "查看 SKILL.md 全文"),
                new SlashCommandHint("/skill on ", "/skill on <name>", "启用 skill"),
                new SlashCommandHint("/skill off ", "/skill off <name>", "禁用 skill"),
                new SlashCommandHint("/skill reload", "/skill reload", "重新扫描 skill 目录"),
                new SlashCommandHint("/exit", "/exit", "退出 PaiCLI"),
                new SlashCommandHint("/quit", "/quit", "退出 PaiCLI")
        );
    }

    private static void printSlashCommandHelp() {
        System.out.println("可用命令：");
        for (SlashCommandHint hint : slashCommandHints()) {
            System.out.println("   " + hint.display() + " - " + hint.description());
        }
        System.out.println();
    }

    static void configureSlashCommandHint(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("paicli-slash-command-hint", () -> {
            boolean atPromptStart = lineReader.getBuffer().length() == 0;
            lineReader.getBuffer().write("/");
            if (atPromptStart) {
                lineReader.callWidget(LineReader.LIST_CHOICES);
            }
            return true;
        });
        Reference slashHint = new Reference("paicli-slash-command-hint");
        bindSlashWidget(lineReader, LineReader.MAIN, slashHint);
        bindSlashWidget(lineReader, LineReader.EMACS, slashHint);
        bindSlashWidget(lineReader, LineReader.VIINS, slashHint);
    }

    private static void bindSlashWidget(LineReader lineReader, String keyMapName, Reference slashHint) {
        KeyMap<org.jline.reader.Binding> keyMap = lineReader.getKeyMaps().get(keyMapName);
        if (keyMap != null) {
            keyMap.bind(slashHint, "/");
        }
    }

    private static void printPolicyStatus(Agent reactAgent) {
        System.out.println("🛡️ 安全策略状态：");
        System.out.println("   项目根: " + reactAgent.getToolRegistry().getProjectPath());
        System.out.println("   危险工具: " + String.join(", ", ApprovalPolicy.getDangerousTools()) + "，以及所有 mcp__ 前缀工具");
        System.out.println("   路径围栏: 强制限定在项目根之内（read_file / write_file / list_dir / create_project）");
        System.out.println("   命令黑名单: sudo / rm -rf 全盘 / mkfs / dd of=/dev / fork bomb / curl|sh / find / / chmod 777 / / shutdown");
        System.out.println("   写入文件上限: 5MB");
        System.out.println("   命令执行上限: 60 秒，输出 8KB（截断）");
        System.out.println("   审计目录: " + reactAgent.getToolRegistry().getAuditLog().getAuditDir());
        System.out.println();
    }

    static String handleBrowserCommand(String payload,
                                       BrowserSession browserSession,
                                       BrowserConnectivityCheck connectivityCheck,
                                       McpServerManager mcpServerManager,
                                       HitlToolRegistry registry,
                                       TerminalHitlHandler hitlHandler) {
        String normalized = payload == null || payload.isBlank() ? "status" : payload.trim();
        String[] parts = normalized.split("\\s+");
        String subCommand = parts[0].toLowerCase();
        return switch (subCommand) {
            case "status" -> browserStatus(browserSession, connectivityCheck, mcpServerManager);
            case "connect" -> {
                if (parts.length >= 2) {
                    int port = parseBrowserPort(parts[1]);
                    yield browserConnectByPort(port, browserSession, connectivityCheck, mcpServerManager, hitlHandler);
                }
                yield browserAutoConnect(browserSession, mcpServerManager, hitlHandler);
            }
            case "disconnect" -> browserDisconnect(browserSession, mcpServerManager, hitlHandler);
            case "tabs" -> browserTabs(browserSession, registry);
            default -> """
                    ❌ 未知 /browser 子命令: %s
                    可用命令：
                      /browser status
                      /browser connect [port]
                      /browser disconnect
                      /browser tabs
                    """.formatted(normalized).trim();
        };
    }

    private static String browserStatus(BrowserSession browserSession,
                                        BrowserConnectivityCheck connectivityCheck,
                                        McpServerManager mcpServerManager) {
        BrowserConnectivityCheck.ProbeResult probe = connectivityCheck.probe(9222);
        McpServer server = mcpServerManager.server("chrome-devtools");
        String serverStatus = server == null
                ? "未配置"
                : server.status() == McpServerStatus.READY
                ? "● ready (" + server.tools().size() + " tools)"
                : server.status().name().toLowerCase() + (server.errorMessage() == null ? "" : " - " + server.errorMessage());
        String mode = browserSession.mode() == BrowserMode.SHARED
                ? "shared（复用 " + browserSession.browserUrl() + "）"
                : "isolated（临时 user-data-dir，无登录态）";
        return """
                🌐 浏览器会话
                  当前模式: %s
                  chrome-devtools server: %s
                  旧式 /json/version 探活: %s
                  自动连接: Chrome 144+ 可在 chrome://inspect/#remote-debugging 勾选 Allow remote debugging 后使用 /browser connect
                """.formatted(mode, serverStatus, probe.ok() ? "✅ " + probe.browserUrl() : "⚠️ " + probe.message()).trim();
    }

    private static String browserAutoConnect(BrowserSession browserSession,
                                             McpServerManager mcpServerManager,
                                             TerminalHitlHandler hitlHandler) {
        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            return "❌ 未配置 chrome-devtools MCP server，请先检查 ~/.paicli/mcp.json";
        }
        List<String> oldArgs = List.copyOf(server.config().getArgs());
        List<String> autoConnectArgs = List.of("-y", "chrome-devtools-mcp@latest", "--autoConnect");
        String result = mcpServerManager.restartWithArgs("chrome-devtools", autoConnectArgs);
        McpServer restarted = mcpServerManager.server("chrome-devtools");
        if (restarted != null && restarted.status() == McpServerStatus.READY) {
            browserSession.switchToShared("autoConnect");
            hitlHandler.clearApprovedAllForServer("chrome-devtools");
            return "🔄 已用 --autoConnect 连接 Chrome（需已在 chrome://inspect/#remote-debugging 允许远程调试）\n" + result;
        }
        mcpServerManager.restartWithArgs("chrome-devtools", oldArgs);
        return "❌ autoConnect 连接失败，已回滚 chrome-devtools 启动参数：\n" + result
                + "\n\n请确认 Chrome 144+ 已打开 chrome://inspect/#remote-debugging，并勾选 Allow remote debugging for this browser instance。";
    }

    private static String browserConnectByPort(int port,
                                               BrowserSession browserSession,
                                               BrowserConnectivityCheck connectivityCheck,
                                               McpServerManager mcpServerManager,
                                               TerminalHitlHandler hitlHandler) {
        if (port < 1024 || port > 65535) {
            return "❌ /browser connect 端口必须在 1024-65535 之间。默认 /browser connect 使用 --autoConnect；旧式 CDP 端口连接可用 /browser connect 9222。";
        }
        BrowserConnectivityCheck.ProbeResult probe = connectivityCheck.probe(port);
        if (!probe.ok()) {
            return "❌ 未检测到 Chrome 调试端口 127.0.0.1:" + port + "：" + probe.message() + "\n\n"
                    + chromeLaunchHelp(port);
        }

        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            return "❌ 未配置 chrome-devtools MCP server，请先检查 ~/.paicli/mcp.json";
        }
        List<String> oldArgs = List.copyOf(server.config().getArgs());
        List<String> sharedArgs = List.of("-y", "chrome-devtools-mcp@latest", "--browser-url=" + probe.browserUrl());
        String result = mcpServerManager.restartWithArgs("chrome-devtools", sharedArgs);
        McpServer restarted = mcpServerManager.server("chrome-devtools");
        if (restarted != null && restarted.status() == McpServerStatus.READY) {
            browserSession.switchToShared(probe.browserUrl());
            hitlHandler.clearApprovedAllForServer("chrome-devtools");
            return "🔄 切换 chrome-devtools server 到 shared 模式 (" + probe.browserUrl() + ")\n" + result;
        }
        mcpServerManager.restartWithArgs("chrome-devtools", oldArgs);
        return "❌ shared 模式切换失败，已回滚 chrome-devtools 启动参数：\n" + result;
    }

    private static String browserDisconnect(BrowserSession browserSession,
                                            McpServerManager mcpServerManager,
                                            TerminalHitlHandler hitlHandler) {
        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            browserSession.switchToIsolated();
            return "❌ 未配置 chrome-devtools MCP server，已清理本地浏览器会话状态";
        }
        String result = mcpServerManager.restartWithArgs(
                "chrome-devtools",
                List.of("-y", "chrome-devtools-mcp@latest", "--isolated=true"));
        browserSession.switchToIsolated();
        hitlHandler.clearApprovedAllForServer("chrome-devtools");
        return "🔄 已切回 isolated 浏览器模式\n" + result;
    }

    private static String browserTabs(BrowserSession browserSession, HitlToolRegistry registry) {
        if (browserSession.mode() != BrowserMode.SHARED) {
            return "当前为 isolated 模式，没有真实 Chrome tab 可复用。可用 /browser connect 切到 shared 模式。";
        }
        return registry.executeTool("mcp__chrome-devtools__list_pages", "{}");
    }

    private static int parseBrowserPort(String value) {
        if (value == null || value.isBlank()) {
            return 9222;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String chromeLaunchHelp(int port) {
        return """
                请先用调试端口启动 Chrome：
                  macOS: open -na "Google Chrome" --args --remote-debugging-port=%d --user-data-dir=/tmp/paicli-chrome-profile
                  Windows: start chrome.exe --remote-debugging-port=%d --user-data-dir=%%TEMP%%\\paicli-chrome-profile
                  Linux: google-chrome --remote-debugging-port=%d --user-data-dir=/tmp/paicli-chrome-profile
                然后重新执行 /browser connect %d
                """.formatted(port, port, port, port).trim();
    }

    private static void printMcpCommandResult(String result) {
        System.out.println(result);
        System.out.println();
    }

    private static void printAuditTail(Agent reactAgent, String payload) {
        int requested = parseAuditCount(payload, 10);
        List<AuditLog.AuditEntry> entries = reactAgent.getToolRegistry().getAuditLog().readRecent(requested);
        if (entries.isEmpty()) {
            System.out.println("📭 今日尚无审计记录\n");
            return;
        }
        System.out.println("📋 最近 " + entries.size() + " 条危险工具审计：");
        for (AuditLog.AuditEntry entry : entries) {
            System.out.printf("   [%s] %s %s (%dms, approver=%s)%n",
                    entry.outcome().toUpperCase(),
                    entry.timestamp(),
                    entry.tool(),
                    entry.durationMs(),
                    entry.approver());
            if (entry.reason() != null && !entry.reason().isBlank()) {
                System.out.println("        原因: " + entry.reason());
            }
            BrowserAuditMetadata metadata = entry.metadata();
            if (metadata != null) {
                System.out.println("        浏览器: mode=" + metadata.browserMode()
                        + ", sensitive=" + metadata.sensitive()
                        + (metadata.targetUrl() == null ? "" : ", url=" + metadata.targetUrl()));
            }
        }
        System.out.println();
    }

    private static int parseAuditCount(String payload, int defaultN) {
        if (payload == null || payload.isBlank()) return defaultN;
        try {
            int n = Integer.parseInt(payload.trim());
            return Math.max(1, Math.min(n, 100));
        } catch (NumberFormatException e) {
            return defaultN;
        }
    }

    private static void printStartupHints() {
        System.out.println("💡 提示:");
        for (String hint : startupHints()) {
            System.out.println("   - " + hint);
        }
        System.out.println();
    }

    static String normalizeLineEndings(String rawInput) {
        return rawInput
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private static String stripBracketedPasteEndMarker(String rawInput) {
        int endMarkerIndex = rawInput.indexOf(BRACKETED_PASTE_END);
        if (endMarkerIndex >= 0) {
            return rawInput.substring(0, endMarkerIndex);
        }
        return rawInput;
    }

    private static boolean isSubmitKey(int key) {
        return key == '\n' || key == '\r';
    }

    static EscapeSequenceType classifyEscapeSequence(String sequence) {
        if (sequence == null || sequence.isEmpty()) {
            return EscapeSequenceType.STANDALONE_ESC;
        }
        if (sequence.startsWith(BRACKETED_PASTE_BEGIN)) {
            return EscapeSequenceType.BRACKETED_PASTE;
        }
        if (sequence.startsWith("[") || sequence.startsWith("O")) {
            return EscapeSequenceType.CONTROL_SEQUENCE;
        }
        return EscapeSequenceType.OTHER;
    }

    static String seedBufferForHistoryNavigation(LineReader lineReader, String sequence) {
        if (lineReader == null || sequence == null || sequence.isEmpty()) {
            return "";
        }

        if (isUpArrowSequence(sequence)) {
            return latestHistoryEntry(lineReader.getHistory());
        }

        if (isDownArrowSequence(sequence)) {
            return "";
        }

        return "";
    }

    private static boolean isUpArrowSequence(String sequence) {
        return ARROW_UP.equals(sequence) || APP_ARROW_UP.equals(sequence);
    }

    private static boolean isDownArrowSequence(String sequence) {
        return ARROW_DOWN.equals(sequence) || APP_ARROW_DOWN.equals(sequence);
    }

    private static String latestHistoryEntry(History history) {
        if (history == null || history.isEmpty()) {
            return "";
        }

        int lastIndex = history.last();
        if (lastIndex < 0) {
            return "";
        }

        String entry = history.get(lastIndex);
        return entry == null ? "" : entry;
    }

    private static PlanExecuteAgent.PlanReviewDecision mapReviewDecision(PlanReviewInputParser.Decision decision) {
        return switch (decision.type()) {
            case EXECUTE -> PlanExecuteAgent.PlanReviewDecision.execute();
            case CANCEL -> PlanExecuteAgent.PlanReviewDecision.cancel();
            case SUPPLEMENT -> PlanExecuteAgent.PlanReviewDecision.supplement(decision.feedback());
        };
    }

    /**
     * 从 .env 文件加载 API Key
     */
    private static String loadApiKey() {
        return loadConfigValue("GLM_API_KEY", null);
    }

    private static void configureLogging() {
        configureLogProperty(LOG_DIR_PROPERTY, "PAICLI_LOG_DIR",
                Path.of(System.getProperty("user.home"), ".paicli", "logs").toString());
        configureLogProperty(LOG_LEVEL_PROPERTY, "PAICLI_LOG_LEVEL", "INFO");
        configureLogProperty(LOG_MAX_HISTORY_PROPERTY, "PAICLI_LOG_MAX_HISTORY", "7");
        configureLogProperty(LOG_MAX_FILE_SIZE_PROPERTY, "PAICLI_LOG_MAX_FILE_SIZE", "10MB");
        configureLogProperty(LOG_TOTAL_SIZE_CAP_PROPERTY, "PAICLI_LOG_TOTAL_SIZE_CAP", "100MB");

        try {
            Files.createDirectories(Path.of(System.getProperty(LOG_DIR_PROPERTY)));
        } catch (IOException e) {
            System.err.println("⚠️ 创建日志目录失败: " + e.getMessage());
        }
    }

    private static void configureLogProperty(String propertyName, String envKey, String defaultValue) {
        String configuredValue = System.getProperty(propertyName);
        if (configuredValue == null || configuredValue.isBlank()) {
            configuredValue = loadConfigValue(envKey, defaultValue);
        }
        if (configuredValue != null && !configuredValue.isBlank()) {
            if (LOG_DIR_PROPERTY.equals(propertyName)) {
                configuredValue = expandHome(configuredValue.trim());
            }
            System.setProperty(propertyName, configuredValue.trim());
        }
    }

    private static String expandHome(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.equals("~")) {
            return System.getProperty("user.home");
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2)).toString();
        }
        return value;
    }

    private static String loadConfigValue(String key, String defaultValue) {
        String sysValue = System.getProperty(key);
        if (sysValue != null && !sysValue.isBlank()) {
            return sysValue.trim();
        }

        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        File currentEnv = new File(ENV_FILE);
        if (currentEnv.exists()) {
            String value = readValueFromFile(currentEnv, key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        File homeEnv = new File(System.getProperty("user.home"), ENV_FILE);
        if (homeEnv.exists()) {
            String value = readValueFromFile(homeEnv, key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return defaultValue;
    }

    private static String readValueFromFile(File file, String key) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith(key + "=")) {
                    return line.substring((key + "=").length()).trim();
                }
            }
        } catch (IOException e) {
            System.err.println("读取 .env 文件失败: " + e.getMessage());
        }
        return null;
    }

    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                                                          ║");
        System.out.println("║   ██████╗  █████╗ ██╗ ██████╗██╗     ██╗                ║");
        System.out.println("║   ██╔══██╗██╔══██╗██║██╔════╝██║     ██║                ║");
        System.out.println("║   ██████╔╝███████║██║██║     ██║     ██║                ║");
        System.out.println("║   ██╔═══╝ ██╔══██║██║██║     ██║     ██║                ║");
        System.out.println("║   ██║     ██║  ██║██║╚██████╗███████╗██║                ║");
        System.out.println("║   ╚═╝     ╚═╝  ╚═╝╚═╝ ╚═════╝╚══════╝╚═╝                ║");
        System.out.println("║                                                          ║");
        System.out.printf("║      Skill-Driven Agent CLI %-26s║%n", "v" + VERSION);
        System.out.println("║                                                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    static McpConfigBootstrapResult ensureDefaultMcpConfig(Path userHome) throws IOException {
        Path configFile = userHome.resolve(".paicli").resolve("mcp.json");
        if (Files.notExists(configFile)) {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, DEFAULT_CHROME_DEVTOOLS_MCP_JSON);
            return new McpConfigBootstrapResult(true,
                    "✅ 已创建默认 MCP 配置: " + configFile
                            + "\n   默认启用 chrome-devtools（isolated 模式）。");
        }
        String content = Files.readString(configFile);
        if (!content.contains("\"chrome-devtools\"")) {
            return new McpConfigBootstrapResult(false,
                    "ℹ️ 检测到 ~/.paicli/mcp.json 未配置 chrome-devtools，建议参考 README 添加浏览器 MCP server。");
        }
        return new McpConfigBootstrapResult(false, "");
    }

    record McpConfigBootstrapResult(boolean created, String message) {
    }
}
