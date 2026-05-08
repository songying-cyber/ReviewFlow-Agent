package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.browser.BrowserAuditMetadata;
import com.paicli.browser.BrowserCheckResult;
import com.paicli.browser.BrowserConnector;
import com.paicli.browser.BrowserGuard;
import com.paicli.context.ContextProfile;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.rag.CodeRetriever;
import com.paicli.rag.SearchResultFormatter;
import com.paicli.rag.VectorStore;
import com.paicli.policy.AuditLog;
import com.paicli.policy.CommandGuard;
import com.paicli.policy.PathGuard;
import com.paicli.policy.PolicyException;
import com.paicli.runtime.CancellationContext;
import com.paicli.web.FetchResult;
import com.paicli.web.HtmlExtractor;
import com.paicli.web.NetworkPolicy;
import com.paicli.web.SearchProvider;
import com.paicli.web.SearchProviderFactory;
import com.paicli.web.SearchResult;
import com.paicli.web.WebFetcher;

import java.io.File;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * 工具注册表 - 管理所有可用工具
 */
public class ToolRegistry {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    private static final int MAX_PARALLEL_TOOLS = 4;
    private static final int MAX_COMMAND_OUTPUT_CHARS = 8_000;
    // write_file 单次写入字节数上限。LLM 想塞超大内容时通常是误生成（重复粘贴 / hallucinate 大段日志），
    // 5MB 对常规代码生成 / 文档撰写完全够用，超过即拒，避免磁盘灌满与误覆盖。
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;
    // 需要审计的内置工具（与 ApprovalPolicy 的 DANGEROUS_TOOLS 保持一致）；MCP 工具按前缀动态纳入审计。
    private static final Set<String> AUDIT_TOOLS = Set.of("write_file", "execute_command", "create_project");
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, McpRegisteredTool> mcpTools = new ConcurrentHashMap<>();
    private final long commandTimeoutSeconds;
    private final long toolBatchTimeoutSeconds;
    private static final int DEFAULT_FETCH_MAX_CHARS = 8_000;
    private String projectPath = System.getProperty("user.dir");
    private PathGuard pathGuard = new PathGuard(projectPath);
    private final AuditLog auditLog = new AuditLog();
    private SearchProvider searchProvider;
    private WebFetcher webFetcher;
    private HtmlExtractor htmlExtractor;
    private NetworkPolicy networkPolicy;
    private ContextProfile contextProfile = ContextProfile.from(null);
    private BrowserGuard browserGuard;
    private BrowserConnector browserConnector;
    private java.util.function.Consumer<String> memorySaver;

    public ToolRegistry() {
        this(DEFAULT_COMMAND_TIMEOUT_SECONDS, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS);
    }

    ToolRegistry(long commandTimeoutSeconds) {
        this(commandTimeoutSeconds, Math.max(commandTimeoutSeconds + 5, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS));
    }

    ToolRegistry(long commandTimeoutSeconds, long toolBatchTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
        // 注册内置工具
        registerFileTools();
        registerShellTools();
        registerCodeTools();
        registerRagTools();
        registerWebTools();
        registerBrowserTools();
        registerMemoryTools();
    }

    /**
     * 设置代码检索的项目路径
     */
    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
        this.pathGuard = new PathGuard(projectPath);
    }

    /**
     * 获取代码检索的项目路径
     */
    public String getProjectPath() {
        return projectPath;
    }

    public void setContextProfile(ContextProfile contextProfile) {
        if (contextProfile != null) {
            this.contextProfile = contextProfile;
        }
    }

    public ContextProfile getContextProfile() {
        return contextProfile;
    }

    public void setBrowserGuard(BrowserGuard browserGuard) {
        this.browserGuard = browserGuard;
    }

    protected BrowserGuard getBrowserGuard() {
        return browserGuard;
    }

    public void setBrowserConnector(BrowserConnector browserConnector) {
        this.browserConnector = browserConnector;
    }

    public void setMemorySaver(java.util.function.Consumer<String> memorySaver) {
        this.memorySaver = memorySaver;
    }

    /**
     * 注册文件操作工具
     */
    private void registerFileTools() {
        // read_file 工具
        tools.put("read_file", new Tool(
                "read_file",
                "读取文件内容（仅限项目根目录之内）",
                createParameters(new Param("path", "string", "文件路径", true)),
                args -> {
                    Path safe = pathGuard.resolveSafe(args.get("path"));
                    try {
                        return "文件内容:\n" + Files.readString(safe);
                    } catch (Exception e) {
                        return "读取文件失败: " + e.getMessage();
                    }
                }
        ));

        // write_file 工具
        tools.put("write_file", new Tool(
                "write_file",
                "写入文件内容（仅限项目根目录之内，单文件 5MB 上限）",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("content", "string", "文件内容", true)
                ),
                args -> {
                    String path = args.get("path");
                    String content = args.get("content") == null ? "" : args.get("content");
                    int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
                    if (contentBytes > MAX_WRITE_FILE_BYTES) {
                        throw new PolicyException("写入内容 " + contentBytes + " 字节超过 "
                                + (MAX_WRITE_FILE_BYTES / 1024 / 1024) + "MB 上限");
                    }
                    Path safe = pathGuard.resolveSafe(path);
                    try {
                        Path parent = safe.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.writeString(safe, content);
                        return "文件已写入: " + path;
                    } catch (Exception e) {
                        return "写入文件失败: " + e.getMessage();
                    }
                }
        ));

        // list_dir 工具
        tools.put("list_dir", new Tool(
                "list_dir",
                "列出目录内容（仅限项目根目录之内）",
                createParameters(new Param("path", "string", "目录路径", true)),
                args -> {
                    Path safe = pathGuard.resolveSafe(args.get("path"));
                    try {
                        File[] files = safe.toFile().listFiles();
                        if (files == null) {
                            return "目录为空或不存在";
                        }
                        StringBuilder sb = new StringBuilder("目录内容:\n");
                        for (File f : files) {
                            sb.append(f.isDirectory() ? "[D] " : "[F] ")
                              .append(f.getName())
                              .append("\n");
                        }
                        return sb.toString();
                    } catch (Exception e) {
                        return "列出目录失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册Shell命令工具
     */
    private void registerShellTools() {
        tools.put("execute_command", new Tool(
                "execute_command",
                "在当前项目目录中执行短时 Shell 命令（默认 60 秒超时，不允许全盘扫描）",
                createParameters(new Param("command", "string", "要执行的命令", true)),
                args -> executeCommand(args.get("command"))
        ));
    }

    /**
     * 注册代码相关工具
     */
    private void registerCodeTools() {
        tools.put("create_project", new Tool(
                "create_project",
                "创建新项目结构",
                createParameters(
                        new Param("name", "string", "项目名称", true),
                        new Param("type", "string", "项目类型 (java/python/node)", true)
                ),
                args -> {
                    String name = args.get("name");
                    String type = args.get("type");
                    Path projectRoot = pathGuard.resolveSafe(name);
                    try {
                        Files.createDirectories(projectRoot);

                        switch (type.toLowerCase()) {
                            case "java" -> {
                                Files.createDirectories(projectRoot.resolve("src/main/java"));
                                Files.createDirectories(projectRoot.resolve("src/main/resources"));
                                Files.writeString(projectRoot.resolve("pom.xml"),
                                        String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                                "<project>\n" +
                                                "    <modelVersion>4.0.0</modelVersion>\n" +
                                                "    <groupId>com.example</groupId>\n" +
                                                "    <artifactId>%s</artifactId>\n" +
                                                "    <version>1.0</version>\n" +
                                                "</project>", name));
                            }
                            case "python" -> {
                                Files.createDirectories(projectRoot.resolve(name));
                                Files.writeString(projectRoot.resolve("main.py"), "# 主程序入口\n");
                                Files.writeString(projectRoot.resolve("requirements.txt"), "# 依赖列表\n");
                            }
                            case "node" -> {
                                Files.writeString(projectRoot.resolve("package.json"),
                                        String.format("{\"name\": \"%s\", \"version\": \"1.0.0\"}", name));
                            }
                        }
                        return "项目已创建: " + name + " (类型: " + type + ")";
                    } catch (Exception e) {
                        return "创建项目失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册 RAG 检索工具
     */
    private void registerRagTools() {
        tools.put("search_code", new Tool(
                "search_code",
                "语义检索代码库，根据自然语言描述查找相关代码块；默认 top_k=5，可显式指定（上限 30）",
                createParameters(
                        new Param("query", "string", "自然语言查询描述，例如'用户登录的实现'", true),
                        new Param("top_k", "integer", "返回结果数量（默认 5，上限 30）", false)
                ),
                args -> {
                    String query = args.get("query");
                    int topK = 5;
                    try {
                        if (args.containsKey("top_k")) {
                            topK = Integer.parseInt(args.get("top_k"));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                    topK = Math.max(1, Math.min(topK, 30));

                    try (CodeRetriever retriever = new CodeRetriever(projectPath)) {
                        var stats = retriever.getStats();
                        if (stats.chunkCount() == 0) {
                            return "代码库尚未索引，请先使用 /index 命令索引当前项目。";
                        }

                        List<VectorStore.SearchResult> results = retriever.hybridSearch(query, topK);
                        if (results.isEmpty()) {
                            return "未找到与查询相关的代码。";
                        }

                        return SearchResultFormatter.formatForTool(query, results);
                    } catch (Exception e) {
                        return "代码检索失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册联网工具：web_search（多 provider 抽象）+ web_fetch（HTTP + readability）
     */
    private void registerWebTools() {
        tools.put("web_search", new Tool(
                "web_search",
                "搜索互联网，获取实时信息（最新版本、官方文档、技术资讯等）。" +
                        "支持 SerpAPI（默认）和 SearXNG（自托管）两种 provider，由 SEARCH_PROVIDER 环境变量切换。",
                createParameters(
                        new Param("query", "string", "搜索关键词，例如'Java 21 新特性'、'Spring Boot 3.3 release notes'", true),
                        new Param("top_k", "integer", "返回结果数量（默认5）", false)
                ),
                args -> webSearch(args.get("query"), parseInt(args.get("top_k"), 5))
        ));

        tools.put("web_fetch", new Tool(
                "web_fetch",
                "抓取指定 URL，提取正文转 Markdown。" +
                        "适用静态 / SSR 页面（博客、文档、官网）；JS 渲染或防爬站会返回空正文，本期不重试。",
                createParameters(
                        new Param("url", "string", "完整 URL，需 http 或 https 协议", true),
                        new Param("max_chars", "integer", "返回 Markdown 最大字符数（默认 8000，超出截断）", false)
                ),
                args -> webFetch(args.get("url"), parseInt(args.get("max_chars"), DEFAULT_FETCH_MAX_CHARS))
        ));
    }

    private void registerBrowserTools() {
        tools.put("browser_connect", new Tool(
                "browser_connect",
                "当浏览器页面返回登录页、权限不足或明确需要登录态时，自动连接已允许远程调试的本机 Chrome 并复用其登录态；公开页面不要提前调用。",
                createParameters(),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法自动切换 shared 模式"
                        : browserConnector.connectDefault()
        ));
        tools.put("browser_disconnect", new Tool(
                "browser_disconnect",
                "完成登录态页面访问后，可切回 isolated 浏览器模式。",
                createParameters(),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法切回 isolated 模式"
                        : browserConnector.disconnect()
        ));
        tools.put("browser_status", new Tool(
                "browser_status",
                "查看当前浏览器 MCP 模式、autoConnect 引导和旧式 CDP 端口探活状态。",
                createParameters(),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法查看浏览器状态"
                        : browserConnector.status()
        ));
    }

    private void registerMemoryTools() {
        tools.put("save_memory", new Tool(
                "save_memory",
                "当且仅当用户明确说“记一下”“记住”“以后记得”或要求保存长期偏好/稳定事实时调用，把精炼事实写入长期记忆；不要保存一次性任务请求、临时文件名或模型猜测。",
                createParameters(new Param("fact", "string", "要长期保存的稳定事实或用户偏好，必须精炼、可跨会话复用", true)),
                args -> {
                    String fact = args.get("fact");
                    if (fact == null || fact.isBlank()) {
                        return "保存长期记忆失败: fact 不能为空";
                    }
                    if (memorySaver == null) {
                        return "保存长期记忆失败: 记忆保存器未初始化";
                    }
                    String normalized = fact.trim();
                    memorySaver.accept(normalized);
                    return "💾 已保存到长期记忆: " + normalized;
                }
        ));
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private synchronized SearchProvider searchProvider() {
        if (searchProvider == null) {
            searchProvider = SearchProviderFactory.create();
        }
        return searchProvider;
    }

    private synchronized WebFetcher webFetcher() {
        if (webFetcher == null) {
            webFetcher = new WebFetcher();
        }
        return webFetcher;
    }

    private synchronized HtmlExtractor htmlExtractor() {
        if (htmlExtractor == null) {
            htmlExtractor = new HtmlExtractor();
        }
        return htmlExtractor;
    }

    private synchronized NetworkPolicy networkPolicy() {
        if (networkPolicy == null) {
            networkPolicy = new NetworkPolicy();
        }
        return networkPolicy;
    }

    String webSearch(String query, int topK) {
        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空";
        }
        SearchProvider provider = searchProvider();
        if (!provider.isReady()) {
            return "⚠️ " + provider.unavailableHint();
        }
        try {
            List<SearchResult> results = provider.search(query.trim(), topK);
            return formatSearchResults(provider.name(), query, results);
        } catch (Exception e) {
            return "搜索失败 (" + provider.name() + "): " + e.getMessage();
        }
    }

    private String formatSearchResults(String providerName, String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "🔍 [" + providerName + "] " + query + "\n\n未找到相关结果。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 [").append(providerName).append("] ").append(query).append("\n\n");
        for (SearchResult r : results) {
            sb.append(r.position()).append(". ").append(r.title()).append("\n");
            if (!r.snippet().isBlank()) {
                String snippet = r.snippet();
                if (snippet.length() > 200) {
                    snippet = snippet.substring(0, 200) + "...";
                }
                sb.append("   ").append(snippet).append("\n");
            }
            if (!r.url().isBlank()) {
                sb.append("   🔗 ").append(r.url());
                if (!r.source().isBlank()) {
                    sb.append("  (").append(r.source()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    String webFetch(String url, int maxChars) {
        if (url == null || url.isBlank()) {
            return "URL 不能为空";
        }
        NetworkPolicy policy = networkPolicy();
        String denyReason = policy.checkUrl(url);
        if (denyReason != null) {
            return "❌ 网络访问被拒绝: " + denyReason;
        }
        String rateReason = policy.acquire();
        if (rateReason != null) {
            return "❌ " + rateReason;
        }

        try {
            WebFetcher.RawResponse raw = webFetcher().fetch(url.trim());
            HtmlExtractor.Extracted extracted = htmlExtractor().extract(raw.body(), raw.url());
            String markdown = extracted.markdown();
            int originalLength = markdown.length();
            boolean truncated = false;
            if (maxChars > 0 && markdown.length() > maxChars) {
                markdown = markdown.substring(0, maxChars);
                truncated = true;
            }
            FetchResult result = FetchResult.ok(raw.url(), extracted.title(), markdown, originalLength, truncated);
            return formatFetchResult(result);
        } catch (Exception e) {
            return "抓取失败: " + e.getMessage();
        }
    }

    private String formatFetchResult(FetchResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌐 抓取: ").append(result.url()).append("\n");
        if (!result.title().isBlank()) {
            sb.append("📄 标题: ").append(result.title()).append("\n");
        }
        if (result.bodyEmpty()) {
            sb.append("\n⚠️ ").append(result.hint()).append("\n");
            return sb.toString();
        }
        sb.append("📏 正文 ").append(result.contentLength()).append(" 字符");
        if (result.truncated()) {
            sb.append("（已截断）");
        }
        sb.append("\n\n---\n\n");
        sb.append(result.markdown());
        return sb.toString();
    }

    /**
     * 创建参数定义
     */
    private JsonNode createParameters(Param... params) {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }

        return parameters;
    }

    /**
     * 获取所有工具定义（用于LLM）
     */
    public List<com.paicli.llm.LlmClient.Tool> getToolDefinitions() {
        return tools.values().stream()
                .map(t -> new com.paicli.llm.LlmClient.Tool(t.name(), t.description(), t.parameters()))
                .toList();
    }

    /**
     * 注册一个 MCP 工具到 ToolRegistry。
     *
     * @param descriptor 工具描述（含 namespacedName 如 mcp__filesystem__read_file）
     * @param invoker    工具执行器：输入 JSON 参数字符串，输出给 LLM 看的字符串结果。
     *                   typically lambda 在内部调用 McpClient.callTool 并处理异常 → 字符串。
     */
    public synchronized void registerMcpTool(McpToolDescriptor descriptor, Function<String, String> invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        String toolName = descriptor.namespacedName();
        McpRegisteredTool registered = new McpRegisteredTool(descriptor, invoker);
        mcpTools.put(toolName, registered);
        tools.put(toolName, new Tool(
                toolName,
                mcpDescription(descriptor),
                descriptor.inputSchema(),
                args -> "MCP 工具不应通过 Map<String,String> 入口执行"
        ));
    }

    public synchronized void unregisterMcpTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        mcpTools.remove(toolName);
        tools.remove(toolName);
    }

    public synchronized void replaceMcpToolsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                      Function<McpToolDescriptor, Function<String, String>> invokerFactory) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(newTools, "newTools");
        Objects.requireNonNull(invokerFactory, "invokerFactory");
        String prefix = "mcp__" + serverName + "__";
        List<String> existing = mcpTools.keySet().stream()
                .filter(name -> name.startsWith(prefix))
                .toList();
        for (String toolName : existing) {
            mcpTools.remove(toolName);
            tools.remove(toolName);
        }
        for (McpToolDescriptor descriptor : newTools) {
            registerMcpTool(descriptor, invokerFactory.apply(descriptor));
        }
    }

    /**
     * 执行工具调用
     *
     * 危险工具（write_file / execute_command / create_project）会写一行审计：
     * - 策略拦截（PathGuard / CommandGuard / 文件大小上限）→ deny
     * - 普通异常 → error
     * - 其他情况 → allow（仅表示工具调用真的发生过，工具内部的业务错误仍以返回字符串呈现给 LLM）
     */
    public String executeTool(String name, String argumentsJson) {
        if (CancellationContext.isCancelled()) {
            return "用户取消了此次工具调用";
        }
        Tool tool = tools.get(name);
        if (tool == null) {
            return "未知工具: " + name;
        }

        boolean shouldAudit = shouldAudit(name);
        long start = System.nanoTime();
        BrowserAuditMetadata auditMetadata = null;

        try {
            McpRegisteredTool mcpTool = mcpTools.get(name);
            if (mcpTool != null) {
                BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, false);
                auditMetadata = browserCheck.metadata();
                if (browserCheck.blocked()) {
                    throw new PolicyException(browserCheck.reason());
                }
                String result = mcpTool.invoker().apply(argumentsJson);
                if (browserGuard != null) {
                    browserGuard.applyAfterExecution(name, argumentsJson, result);
                }
                if (shouldAudit) {
                    auditLog.record(AuditLog.AuditEntry.allow(name, argumentsJson, elapsedMillis(start), auditMetadata));
                }
                return result;
            }

            JsonNode args = mapper.readTree(argumentsJson);
            Map<String, String> argMap = new HashMap<>();
            args.fields().forEachRemaining(entry ->
                    argMap.put(entry.getKey(), entry.getValue().asText()));
            String result = tool.executor().execute(argMap);
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.allow(name, argumentsJson, elapsedMillis(start), auditMetadata));
            }
            return result;
        } catch (PolicyException e) {
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.denyByPolicy(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata));
            }
            return "🛡️ 策略拒绝: " + e.getMessage();
        } catch (Exception e) {
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.error(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata));
            }
            return "工具执行失败: " + e.getMessage();
        }
    }

    protected BrowserCheckResult checkBrowserTool(String name, String argumentsJson, boolean previewOnly) {
        if (browserGuard == null || !BrowserGuard.isChromeTool(name)) {
            return BrowserCheckResult.allow(null);
        }
        return browserGuard.check(name, argumentsJson, !previewOnly);
    }

    public AuditLog getAuditLog() {
        return auditLog;
    }

    /**
     * 并行执行同一轮 LLM 返回的多个工具调用。
     *
     * 结果按传入顺序返回，调用方可以安全地按原 tool_call 顺序回灌消息历史。
     * 如果某个工具超过批次超时仍未返回，会取消任务并返回超时结果；已完成工具不受影响。
     */
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        if (CancellationContext.isCancelled()) {
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(invocation, "用户取消了此次工具调用"))
                    .toList();
        }
        if (invocations.size() == 1) {
            ToolInvocation invocation = invocations.get(0);
            long startedAt = System.nanoTime();
            String result = executeTool(invocation.name(), invocation.argumentsJson());
            return List.of(ToolExecutionResult.completed(invocation, result, elapsedMillis(startedAt)));
        }

        int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread thread = new Thread(r, "paicli-tool-executor");
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Callable<ToolExecutionResult>> tasks = invocations.stream()
                    .<Callable<ToolExecutionResult>>map(invocation -> () -> {
                        if (CancellationContext.isCancelled()) {
                            return ToolExecutionResult.failed(invocation, "用户取消了此次工具调用");
                        }
                        long startedAt = System.nanoTime();
                        String result = executeTool(invocation.name(), invocation.argumentsJson());
                        return ToolExecutionResult.completed(invocation, result, elapsedMillis(startedAt));
                    })
                    .toList();

            List<Future<ToolExecutionResult>> futures =
                    executor.invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

            List<ToolExecutionResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                ToolInvocation invocation = invocations.get(i);
                Future<ToolExecutionResult> future = futures.get(i);
                if (future.isCancelled()) {
                    results.add(ToolExecutionResult.timedOut(invocation, toolBatchTimeoutSeconds));
                    continue;
                }

                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(ToolExecutionResult.failed(invocation, "工具执行被中断"));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause == null || cause.getMessage() == null
                            ? "未知错误"
                            : cause.getMessage();
                    results.add(ToolExecutionResult.failed(invocation, message));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(invocation, "工具批次执行被中断"))
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    private static boolean shouldAudit(String name) {
        return AUDIT_TOOLS.contains(name) || (name != null && name.startsWith("mcp__"));
    }

    private static String mcpDescription(McpToolDescriptor descriptor) {
        String base = descriptor.description() == null || descriptor.description().isBlank()
                ? "MCP server 提供的外部工具"
                : descriptor.description();
        return base + " (MCP server: " + descriptor.serverName() + ", tool: " + descriptor.name() + ")";
    }

    private String executeCommand(String command) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isEmpty()) {
            return "执行命令失败: 命令不能为空";
        }
        String denyReason = CommandGuard.check(normalized);
        if (denyReason != null) {
            // 抛 PolicyException 让外层 executeTool 统一写 audit 并格式化拒绝消息，
            // 命令围栏与路径围栏的拒绝路径走同一个出口。
            throw new PolicyException(denyReason);
        }

        ExecutorService outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-command-output");
            thread.setDaemon(true);
            return thread;
        });

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", normalized);
            pb.directory(new File(projectPath));
            pb.redirectErrorStream(true);
            process = pb.start();

            Process runningProcess = process;
            Future<String> outputFuture = outputReaderExecutor.submit(() -> readProcessOutput(runningProcess));

            boolean finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                return "命令执行超时（" + commandTimeoutSeconds + "秒），已强制终止";
            }

            String output = getCommandOutput(outputFuture);
            int exitCode = process.exitValue();
            return String.format("命令执行完成 (exit code: %d)\n%s", exitCode, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return "用户取消了此次工具调用";
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return "执行命令失败: " + e.getMessage();
        } finally {
            outputReaderExecutor.shutdownNow();
        }
    }

    private String readProcessOutput(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < MAX_COMMAND_OUTPUT_CHARS) {
                    int remaining = MAX_COMMAND_OUTPUT_CHARS - output.length();
                    if (line.length() > remaining) {
                        output.append(line, 0, remaining);
                    } else {
                        output.append(line);
                    }
                    output.append("\n");
                }
            }
        }
        if (output.length() >= MAX_COMMAND_OUTPUT_CHARS) {
            return output.substring(0, MAX_COMMAND_OUTPUT_CHARS) + "\n...(输出已截断)";
        }
        return output.toString();
    }

    private String getCommandOutput(Future<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            outputFuture.cancel(true);
            return "(命令已结束，但输出读取超时)";
        }
    }

    // 记录定义
    private record Param(String name, String type, String description, boolean required) {}

    public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

    private record McpRegisteredTool(McpToolDescriptor descriptor, Function<String, String> invoker) {}

    public record ToolInvocation(String id, String name, String argumentsJson) {}

    public record ToolExecutionResult(String id, String name, String argumentsJson,
                                      String result, long elapsedMillis, boolean timedOut) {
        private static ToolExecutionResult completed(ToolInvocation invocation, String result, long elapsedMillis) {
            return new ToolExecutionResult(
                    invocation.id(), invocation.name(), invocation.argumentsJson(), result, elapsedMillis, false);
        }

        private static ToolExecutionResult failed(ToolInvocation invocation, String message) {
            return completed(invocation, "工具执行失败: " + message, 0);
        }

        private static ToolExecutionResult timedOut(ToolInvocation invocation, long timeoutSeconds) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    "工具执行超时（" + timeoutSeconds + "秒），已取消",
                    timeoutSeconds * 1000,
                    true
            );
        }
    }

    public interface ToolExecutor {
        String execute(Map<String, String> args);
    }
}
