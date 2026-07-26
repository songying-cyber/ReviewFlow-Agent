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
import com.paicli.hitl.ApprovalActionType;
import com.paicli.hitl.ApprovalFingerprint;
import com.paicli.hitl.ApprovalPolicy;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.lsp.LspDiagnosticReport;
import com.paicli.lsp.LspManager;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.rag.CodeRetriever;
import com.paicli.rag.SearchResultFormatter;
import com.paicli.rag.VectorStore;
import com.paicli.policy.AuditLog;
import com.paicli.policy.CommandGuard;
import com.paicli.policy.PathGuard;
import com.paicli.policy.PolicyException;
import com.paicli.runtime.CancellationContext;
import com.paicli.sandbox.CommandResult;
import com.paicli.sandbox.CommandRunner;
import com.paicli.sandbox.SandboxAuditMetadata;
import com.paicli.sandbox.SandboxConfig;
import com.paicli.sandbox.SandboxDecision;
import com.paicli.sandbox.SandboxPolicy;
import com.paicli.snapshot.RestoreResult;
import com.paicli.snapshot.SnapshotService;
import com.paicli.skill.Skill;
import com.paicli.skill.SkillContextBuffer;
import com.paicli.skill.SkillRegistry;
import com.paicli.web.FetchResult;
import com.paicli.web.HtmlExtractor;
import com.paicli.web.NetworkPolicy;
import com.paicli.web.SearchProvider;
import com.paicli.web.SearchProviderFactory;
import com.paicli.web.SearchResult;
import com.paicli.web.WebFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工具注册表 - 管理所有可用工具
 */
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    private static final int MAX_PARALLEL_TOOLS = 4;
    private static final int MAX_COMMAND_OUTPUT_CHARS = 8_000;
    private static final int MAX_READ_FILE_LINES = 2_000;
    private static final int MAX_GREP_RESULTS = 200;
    private static final int MAX_GREP_CONTEXT_LINES = 5;
    private static final int DEFAULT_GREP_MAX_CHARS = 24_000;
    private static final int MAX_GREP_MAX_CHARS = 60_000;
    private static final int DEFAULT_GREP_HEAD_LIMIT = 20;
    private static final String STEP_SEARCH_SERVER = "step_search";
    private static final String STEP_SEARCH_TOOL = "mcp__" + STEP_SEARCH_SERVER + "__web_search";
    private static final String STEP_FETCH_TOOL = "mcp__" + STEP_SEARCH_SERVER + "__web_fetch";
    private static final Set<String> SEARCH_EXCLUDED_DIRS = Set.of(
            ".git", ".paicli", "target", "node_modules", "dist", "build", "coverage", ".idea", ".gradle"
    );
    // write_file 单次写入字节数上限。LLM 想塞超大内容时通常是误生成（重复粘贴 / hallucinate 大段日志），
    // 5MB 对常规代码生成 / 文档撰写完全够用，超过即拒，避免磁盘灌满与误覆盖。
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, McpRegisteredTool> mcpTools = new ConcurrentHashMap<>();
    private final ThreadLocal<String> auditFingerprint = new ThreadLocal<>();
    private final ThreadLocal<SandboxAuditMetadata> auditSandboxMetadata = new ThreadLocal<>();
    private volatile ToolIntentValidator toolIntentValidator;
    private volatile SandboxConfig sandboxConfig = new SandboxConfig();
    private final long commandTimeoutSeconds;
    private final long toolBatchTimeoutSeconds;
    private static final int DEFAULT_FETCH_MAX_CHARS = 8_000;
    private static final int MAX_FETCH_MAX_CHARS = 60_000;
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
    private BiConsumer<String, String> memorySaver;
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private java.util.function.BiConsumer<String, String[]> writeFileObserver = (p, ba) -> {};
    private final List<Consumer<Path>> readFileObservers = new CopyOnWriteArrayList<>();
    private LspManager lspManager = new LspManager(projectPath);
    private SnapshotService snapshotService = SnapshotService.forProject(Path.of(projectPath));
    private boolean customSnapshotService;
    private volatile String currentProvider = "";
    private volatile String currentModel = "";

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
        registerSkillTools();
        registerSnapshotTools();
        registerOperationTools();
    }

    /**
     * 设置代码检索的项目路径
     */
    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
        this.pathGuard = new PathGuard(projectPath);
        this.lspManager.setProjectPath(projectPath);
        if (!customSnapshotService) {
            this.snapshotService.close();
            this.snapshotService = SnapshotService.forProject(Path.of(projectPath));
        }
    }

    public void setSandboxConfig(SandboxConfig sandboxConfig) {
        this.sandboxConfig = sandboxConfig == null ? new SandboxConfig() : sandboxConfig.copy();
    }

    public SandboxConfig getSandboxConfig() {
        return sandboxConfig.copy();
    }

    public SandboxDecision sandboxDecisionForCommand(String argumentsJson) {
        try {
            JsonNode args = mapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
            String command = args.path("command").asText("");
            boolean disable = args.path("dangerously_disable_sandbox").asBoolean(
                    args.path("dangerouslyDisableSandbox").asBoolean(false));
            return new SandboxPolicy(sandboxConfig).decide(command, disable);
        } catch (Exception e) {
            return new SandboxPolicy(sandboxConfig).decide("", false);
        }
    }

    public boolean isSandboxAutoAllowedCommand(String name, String argumentsJson) {
        if (!"execute_command".equals(name)) {
            return false;
        }
        SandboxDecision decision = sandboxDecisionForCommand(argumentsJson);
        return decision.useSandbox() && decision.autoAllowed();
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

    public void setCurrentModel(String provider, String model) {
        this.currentProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        this.currentModel = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }

    public void setToolIntentValidator(ToolIntentValidator validator) {
        this.toolIntentValidator = validator;
    }

    public boolean hasToolIntentValidator() {
        return toolIntentValidator != null;
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

    public void setMemorySaver(Consumer<String> memorySaver) {
        this.memorySaver = memorySaver == null ? null : (fact, scope) -> memorySaver.accept(fact);
    }

    public void setScopedMemorySaver(BiConsumer<String, String> memorySaver) {
        this.memorySaver = memorySaver;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    public SkillContextBuffer getSkillContextBuffer() {
        return skillContextBuffer;
    }

    /**
     * 注册 write_file 写入观察者：参数 (path, [before, after])，
     * before == null 表示新建文件或读不出原文。
     * 用于把 write_file 接到行内 diff 渲染等只读副作用里；
     * 观察者抛异常不影响 write_file 主路径。
     */
    public void setWriteFileObserver(java.util.function.BiConsumer<String, String[]> observer) {
        this.writeFileObserver = observer == null ? (p, ba) -> {} : observer;
    }

    public void setReadFileObserver(Consumer<Path> observer) {
        this.readFileObservers.clear();
        if (observer != null) {
            this.readFileObservers.add(observer);
        }
    }

    public void addReadFileObserver(Consumer<Path> observer) {
        if (observer != null) {
            this.readFileObservers.add(observer);
        }
    }

    public void setLspManager(LspManager lspManager) {
        this.lspManager = lspManager == null ? new LspManager(projectPath) : lspManager;
        this.lspManager.setProjectPath(projectPath);
    }

    public LspDiagnosticReport flushPendingLspDiagnostics() {
        return lspManager == null ? LspDiagnosticReport.EMPTY : lspManager.flushPendingDiagnostics();
    }

    public SnapshotService getSnapshotService() {
        return snapshotService;
    }

    public void setSnapshotService(SnapshotService snapshotService) {
        this.snapshotService = snapshotService == null ? SnapshotService.forProject(Path.of(projectPath)) : snapshotService;
        this.customSnapshotService = snapshotService != null;
    }

    /**
     * 注册文件操作工具
     */
    private void registerFileTools() {
        // read_file 工具
        tools.put("read_file", new Tool(
                "read_file",
                "读取已知路径的项目文件内容（只读，仅限项目根目录之内）。先用 glob_files/grep_code 定位路径；读取大文件时必须用 offset/limit 按行读取，不要整段塞进上下文",
                createParameters(
                        new Param("path", "string", "项目根目录内的相对文件路径，例如 src/main/java/com/example/App.java；不要传项目外路径", true),
                        new Param("offset", "integer", "起始行号，1 表示第一行；省略时读取全文", false)
                                .range(1, null),
                        new Param("limit", "integer", "最多读取多少行；省略时读取全文，最大 2000 行", false)
                                .range(1, MAX_READ_FILE_LINES)
                ),
                ToolMetadata.readOnly("只读取项目根目录内的文件内容"),
                args -> {
                    Path safe = pathGuard.resolveSafe(args.get("path"));
                    try {
                        return readFileForTool(safe, args);
                    } catch (Exception e) {
                        return "读取文件失败: " + e.getMessage();
                    }
                }
        ));

        // write_file 工具
        tools.put("write_file", new Tool(
                "write_file",
                "整文件覆盖写入项目文件（写操作，仅限项目根目录之内，单文件 5MB 上限）。不支持 patch/diff/追加；修改已有文件前应先 read_file 获取当前内容，再写入完整新内容",
                createParameters(
                        new Param("path", "string", "项目根目录内的相对文件路径；会创建缺失的父目录，但不能越出项目根", true),
                        new Param("content", "string", "要写入的完整文件内容，不是 diff 或片段", true)
                                .maxLength(MAX_WRITE_FILE_BYTES),
                        new Param("write_mode", "string", "写入模式；当前仅支持 overwrite，表示整文件覆盖", false)
                                .enumValues("overwrite")
                                .defaultValue("overwrite")
                ),
                ToolMetadata.mediumWrite("将整文件覆盖写入项目根目录内的文件，可能覆盖原有内容"),
                args -> {
                    String path = args.get("path");
                    String content = args.get("content") == null ? "" : args.get("content");
                    String writeMode = args.getOrDefault("write_mode", "overwrite");
                    if (!"overwrite".equals(writeMode)) {
                        return "写入文件失败: write_mode 仅支持 overwrite（整文件覆盖）";
                    }
                    int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
                    if (contentBytes > MAX_WRITE_FILE_BYTES) {
                        throw new PolicyException("写入内容 " + contentBytes + " 字节超过 "
                                + (MAX_WRITE_FILE_BYTES / 1024 / 1024) + "MB 上限");
                    }
                    Path safe = pathGuard.resolveSafe(path);
                    String before = null;
                    try {
                        if (Files.exists(safe) && Files.isRegularFile(safe)) {
                            before = Files.readString(safe);
                        }
                    } catch (Exception ignored) {
                        // 二进制 / 大文件 / 编码错读不出来时，前文当 null 处理（diff 退化为长度提示）
                    }
                    try {
                        Path parent = safe.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.writeString(safe, content);
                        try {
                            writeFileObserver.accept(path, new String[]{before, content});
                        } catch (Exception ignored) {
                            // observer 失败不能影响 write_file 主路径
                        }
                        runPostEditLspHook(path, safe);
                        return "文件已写入: " + path;
                    } catch (Exception e) {
                        return "写入文件失败: " + e.getMessage();
                    }
                }
        ));

        // list_dir 工具
        tools.put("list_dir", new Tool(
                "list_dir",
                "列出已知目录的直接子项（只读，仅限项目根目录之内）。用于查看目录结构；按文件名模式查找候选文件请用 glob_files",
                createParameters(new Param("path", "string", "项目根目录内的相对目录路径，例如 src/main/java", true)),
                ToolMetadata.readOnly("只读取项目根目录内的目录列表"),
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

        tools.put("glob_files", new Tool(
                "glob_files",
                "按文件名或路径 glob 查找项目内候选文件（只读、实时、尊重常见忽略目录）。只知道文件名/后缀/路径模式时优先用它；要搜符号或字符串请用 grep_code",
                createParameters(
                        new Param("pattern", "string", "文件名或路径 glob 模式，例如 **/*.java、**/*Controller*、README.md", true),
                        new Param("path", "string", "项目根目录内的搜索起始目录，默认 .", false)
                                .defaultValue("."),
                        new Param("max_results", "integer", "最多返回结果数，默认 50，上限 200", false)
                                .range(1, MAX_GREP_RESULTS)
                                .defaultValue(50)
                ),
                ToolMetadata.readOnly("只按文件名或路径模式查找项目内候选文件"),
                args -> globFiles(args)
        ));

        tools.put("grep_code", new Tool(
                "grep_code",
                "在项目内按已知符号、类名、方法名、错误文本或字符串片段实时搜索（只读、优先 ripgrep、返回文件和行号）。适合精确定位；如果只有自然语言描述或不知道关键词，请用 search_code",
                createParameters(
                        new Param("pattern", "string", "要搜索的精确符号、类名、方法名、错误文本或字符串片段；自然语言问题请改用 search_code", true),
                        new Param("path", "string", "项目根目录内的搜索起始目录，默认 .", false)
                                .defaultValue("."),
                        new Param("glob", "string", "可选文件 glob 过滤，例如 **/*.java；用于缩小搜索范围", false),
                        new Param("regex", "boolean", "是否按 Java 正则解释 pattern，默认 false 表示字面量搜索", false)
                                .defaultValue(false),
                        new Param("case_sensitive", "boolean", "是否大小写敏感，默认 true", false)
                                .defaultValue(true),
                        new Param("context_lines", "integer", "每条命中前后上下文行数，默认 0，上限 5；需要更多上下文时用 read_file", false)
                                .range(0, MAX_GREP_CONTEXT_LINES)
                                .defaultValue(0),
                        new Param("max_results", "integer", "最多返回命中数，默认 50，上限 200", false)
                                .range(1, MAX_GREP_RESULTS)
                                .defaultValue(50),
                        new Param("head_limit", "integer", "单个文件最多返回多少条命中，默认 20，上限 50", false)
                                .range(1, 50)
                                .defaultValue(DEFAULT_GREP_HEAD_LIMIT),
                        new Param("max_chars", "integer", "单次工具结果字符预算，默认 24000，上限 60000", false)
                                .range(1_000, MAX_GREP_MAX_CHARS)
                                .defaultValue(DEFAULT_GREP_MAX_CHARS)
                ),
                ToolMetadata.readOnly("只搜索项目内代码文本，不修改文件"),
                args -> grepCode(args)
        ));
    }

    private String readFileForTool(Path file, Map<String, String> args) throws IOException {
        if (!Files.isRegularFile(file)) {
            return "读取文件失败: 不是普通文件";
        }
        notifyReadFileObservers(file.toAbsolutePath().normalize());
        boolean ranged = args.containsKey("offset") || args.containsKey("limit");
        if (!ranged) {
            return "文件内容:\n" + Files.readString(file);
        }

        int offset = Math.max(1, parseInt(args.get("offset"), 1));
        int limit = Math.max(1, Math.min(parseInt(args.get("limit"), 200), MAX_READ_FILE_LINES));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int total = lines.size();
        if (offset > total) {
            return "文件内容: " + file.getFileName() + " 共 " + total + " 行，offset 超出范围";
        }

        int from = offset - 1;
        int to = Math.min(from + limit, total);
        StringBuilder sb = new StringBuilder();
        sb.append("文件内容: ").append(file.getFileName())
                .append(" (lines ").append(offset).append("-").append(to)
                .append(" of ").append(total).append(")\n");
        for (int i = from; i < to; i++) {
            sb.append(String.format("%5d | %s%n", i + 1, lines.get(i)));
        }
        if (to < total) {
            sb.append("...(已截断，可用 offset=").append(to + 1).append(" 继续读取)");
        }
        return sb.toString().trim();
    }

    private void notifyReadFileObservers(Path file) {
        for (Consumer<Path> observer : readFileObservers) {
            try {
                observer.accept(file);
            } catch (Exception ignored) {
                // Observer failure must not affect read_file.
            }
        }
    }

    private String globFiles(Map<String, String> args) {
        String pattern = args.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return "文件匹配失败: pattern 不能为空";
        }
        Path root = pathGuard.resolveSafe(args.getOrDefault("path", "."));
        int maxResults = clamp(parseInt(args.get("max_results"), 50), 1, MAX_GREP_RESULTS);
        Path projectRoot = pathGuard.getRootPath();
        PathMatcher matcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeGlob(pattern));
        PathMatcher fileNameMatcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeFileNameGlob(pattern));
        List<String> matches = new ArrayList<>();

        try {
            Files.walkFileTree(root, new SearchFileVisitor(projectRoot, path -> {
                if (matches.size() >= maxResults) {
                    return;
                }
                Path relative = projectRoot.relativize(path);
                if (matcher.matches(relative) || fileNameMatcher.matches(path.getFileName())) {
                    matches.add(relative.toString());
                }
            }));
        } catch (Exception e) {
            return "文件匹配失败: " + e.getMessage();
        }

        if (matches.isEmpty()) {
            return "未找到匹配文件: " + pattern;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("匹配文件 ").append(matches.size()).append(" 个");
        if (matches.size() >= maxResults) {
            sb.append("（已达到上限 ").append(maxResults).append("）");
        }
        sb.append(":\n");
        for (int i = 0; i < matches.size(); i++) {
            sb.append(i + 1).append(". ").append(matches.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private String grepCode(Map<String, String> args) {
        String query = args.get("pattern");
        if (query == null || query.isBlank()) {
            return "代码搜索失败: pattern 不能为空";
        }
        Path root = pathGuard.resolveSafe(args.getOrDefault("path", "."));
        Path projectRoot = pathGuard.getRootPath();
        int maxResults = clamp(parseInt(args.get("max_results"), 50), 1, MAX_GREP_RESULTS);
        int contextLines = clamp(parseInt(args.get("context_lines"), 0), 0, MAX_GREP_CONTEXT_LINES);
        boolean regex = parseBoolean(args.get("regex"), false);
        boolean caseSensitive = parseBoolean(args.get("case_sensitive"), true);
        int headLimit = clamp(parseInt(args.get("head_limit"), DEFAULT_GREP_HEAD_LIMIT), 1, 50);
        int maxChars = clamp(parseInt(args.get("max_chars"), DEFAULT_GREP_MAX_CHARS), 1_000, MAX_GREP_MAX_CHARS);
        CodeSearchRequest request = new CodeSearchRequest(
                query,
                root,
                projectRoot,
                args.get("glob"),
                regex,
                caseSensitive,
                contextLines,
                maxResults,
                headLimit
        );
        CodeSearchResult result = new RipgrepCodeSearchEngine(SEARCH_EXCLUDED_DIRS).search(request);

        if (!result.partialReason().isBlank() && result.matches().isEmpty()) {
            return "代码搜索失败: " + result.partialReason();
        }
        if (result.matches().isEmpty()) {
            return "未找到匹配内容: " + query;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("匹配结果 ").append(result.matches().size()).append(" 条")
                .append(" (engine=").append(result.engine()).append(")");
        if (result.partial()) {
            sb.append("（partial: ").append(result.partialReason()).append("）");
        }
        sb.append(":\n");
        boolean truncatedByChars = false;
        int rendered = 0;
        for (int i = 0; i < result.matches().size(); i++) {
            GrepMatch match = result.matches().get(i);
            String matchHeader = (i + 1) + ". " + match.file() + ":" + match.lineNumber() + "\n";
            if (sb.length() + matchHeader.length() > maxChars) {
                truncatedByChars = true;
                break;
            }
            sb.append(i + 1).append(". ").append(match.file()).append(":").append(match.lineNumber()).append("\n");
            for (ContextLine line : match.context()) {
                String marker = line.lineNumber() == match.lineNumber() ? ">" : " ";
                String contextLine = String.format("   %s%5d | %s%n", marker, line.lineNumber(), line.text());
                if (sb.length() + contextLine.length() > maxChars) {
                    truncatedByChars = true;
                    break;
                }
                sb.append(contextLine);
            }
            rendered++;
            if (truncatedByChars) {
                break;
            }
        }
        if (truncatedByChars) {
            sb.append("\npartial: true（已达到 max_chars=").append(maxChars).append("，请缩小 path/glob/pattern 或提高 offset 后 read_file）");
        } else if (result.partial()) {
            sb.append("\npartial: true（").append(result.partialReason()).append("，请缩小 path/glob/pattern 继续搜索）");
        }
        appendSuggestedReads(sb, result.matches().subList(0, Math.min(rendered, result.matches().size())));
        return sb.toString().trim();
    }

    private void appendSuggestedReads(StringBuilder sb, List<GrepMatch> matches) {
        if (matches.isEmpty()) {
            return;
        }
        sb.append("\nsuggested_reads:");
        Set<String> seen = new LinkedHashSet<>();
        for (GrepMatch match : matches) {
            if (seen.size() >= 3 || !seen.add(match.file())) {
                continue;
            }
            int offset = Math.max(1, match.lineNumber() - 20);
            sb.append("\n- read_file {\"path\":\"")
                    .append(match.file().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\",\"offset\":").append(offset)
                    .append(",\"limit\":80}");
        }
    }

    /**
     * 注册Shell命令工具
     */
    private void registerShellTools() {
        tools.put("execute_command", new Tool(
                "execute_command",
                "在当前项目目录中执行短时 Shell 命令（写/执行类操作，默认 60 秒超时，不允许全盘扫描）。适合运行测试、构建、只读检查；不要用于编辑文件，编辑请用 write_file",
                createParameters(
                        new Param("command", "string", "要执行的完整 shell 命令；必须范围明确、短时运行，不能包含明显破坏性操作", true),
                        new Param("dangerously_disable_sandbox", "boolean", "仅当 macOS 沙箱导致必要命令无法运行且用户授权时才可设为 true；默认 false", false)
                                .defaultValue(false),
                        new Param("purpose", "string", "执行目的，用于帮助模型选择和审批展示", false)
                                .enumValues("run_tests", "build_project", "inspect_project", "format_code", "other")
                ),
                ToolMetadata.highRisk("将在当前项目目录执行 Shell 命令，可能修改文件、安装软件或影响系统状态"),
                args -> executeCommand(args.get("command"),
                        Boolean.parseBoolean(args.getOrDefault("dangerously_disable_sandbox",
                                args.getOrDefault("dangerouslyDisableSandbox", "false"))))
        ));
    }

    /**
     * 注册代码相关工具
     */
    private void registerCodeTools() {
        tools.put("create_project", new Tool(
                "create_project",
                "创建新的示例项目目录结构（写操作）。仅适合用户明确要求新建 java/python/node 项目；不要用于修改当前已有项目代码",
                createParameters(
                        new Param("name", "string", "要创建的项目目录名，必须位于当前项目根目录内", true),
                        new Param("type", "string", "项目类型", true)
                                .enumValues("java", "python", "node")
                ),
                ToolMetadata.mediumWrite("将在项目根目录内创建新目录和模板文件"),
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
                "RAG 语义辅助检索代码库，根据自然语言描述查找相关代码块。只在关键词不明确、普通 grep/glob 难以定位或用户描述很模糊时使用；已知符号/类名/字符串时不要用它，改用 grep_code/glob_files/read_file",
                createParameters(
                        new Param("query", "string", "自然语言查询描述，例如'用户登录的实现'；不要填单个已知符号名，已知符号请用 grep_code", true),
                        new Param("top_k", "integer", "返回结果数量（默认 5，上限 30）", false)
                                .range(1, 30)
                                .defaultValue(5)
                ),
                ToolMetadata.readOnly("只读取本地代码索引并返回语义检索结果"),
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
                "搜索互联网以获取当前/外部信息，例如最新版本、官方文档、新闻或技术资料。当前项目、本地 README、已知 URL 内容不要用它；项目内代码优先用 glob_files/grep_code/read_file，已知 URL 请用 web_fetch。",
                createParameters(
                        new Param("query", "string", "互联网搜索关键词，例如 'Java 21 新特性'、'Spring Boot 3.3 release notes'", true),
                        new Param("top_k", "integer", "返回结果数量（默认 5，上限 10）", false)
                                .range(1, 10)
                                .defaultValue(5)
                ),
                ToolMetadata.readOnly("只搜索互联网并返回结果摘要，不修改本地或外部状态"),
                args -> webSearch(args.get("query"), clamp(parseInt(args.get("top_k"), 5), 1, 10))
        ));

        tools.put("web_fetch", new Tool(
                "web_fetch",
                "抓取一个已知 http/https URL 并提取正文为 Markdown。只在用户给出 URL 或 web_search 已返回具体 URL 时使用；不知道 URL 时先用 web_search。JS 渲染/防爬页面可能返回空正文。",
                createParameters(
                        new Param("url", "string", "完整 http 或 https URL", true)
                                .format("uri"),
                        new Param("max_chars", "integer", "返回 Markdown 最大字符数（默认 8000，超出截断，上限 60000）", false)
                                .range(1_000, MAX_FETCH_MAX_CHARS)
                                .defaultValue(DEFAULT_FETCH_MAX_CHARS)
                ),
                ToolMetadata.readOnly("只抓取指定 URL 并提取正文，不修改本地或外部状态"),
                args -> webFetch(args.get("url"),
                        clamp(parseInt(args.get("max_chars"), DEFAULT_FETCH_MAX_CHARS), 1_000, MAX_FETCH_MAX_CHARS))
        ));
    }

    private void registerBrowserTools() {
        tools.put("browser_connect", new Tool(
                "browser_connect",
                "当浏览器页面返回登录页、权限不足或明确需要登录态时，自动连接已允许远程调试的本机 Chrome 并复用其登录态；公开页面不要提前调用。",
                createParameters(),
                ToolMetadata.mediumWrite("将切换浏览器连接模式并可能复用本机 Chrome 登录态"),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法自动切换 shared 模式"
                        : browserConnector.connectDefault()
        ));
        tools.put("browser_disconnect", new Tool(
                "browser_disconnect",
                "完成登录态页面访问后，可切回 isolated 浏览器模式。",
                createParameters(),
                ToolMetadata.mediumWrite("将切换浏览器连接模式回 isolated"),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法切回 isolated 模式"
                        : browserConnector.disconnect()
        ));
        tools.put("browser_status", new Tool(
                "browser_status",
                "查看当前浏览器 MCP 模式、autoConnect 引导和旧式 CDP 端口探活状态。",
                createParameters(),
                ToolMetadata.readOnly("只读取当前浏览器连接状态"),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法查看浏览器状态"
                        : browserConnector.status()
        ));
    }

    private void registerSkillTools() {
        tools.put("load_skill", new Tool(
                "load_skill",
                "Load full SKILL.md instructions for a skill the system has indexed (see the \"可用 Skills\" section in this system prompt). Call this when a skill's description matches the current task. Pass the exact kebab-case skill name. The full body will appear at the start of your next user message under \"## 已加载 Skill：<name>\". Don't reload the same skill twice in one session.",
                createParameters(new Param("name", "string", "the exact kebab-case skill name (e.g. web-access)", true)),
                ToolMetadata.lowWrite("会把 Skill 指引写入下一轮上下文缓冲，不修改项目文件"),
                args -> {
                    String name = args.get("name");
                    if (name == null || name.isBlank()) {
                        return "load_skill 失败: name 不能为空";
                    }
                    if (skillRegistry == null) {
                        return "load_skill 失败: Skill 系统未初始化";
                    }
                    Skill skill = skillRegistry.findSkill(name);
                    if (skill == null) {
                        Skill any = skillRegistry.findAnySkill(name);
                        if (any == null) {
                            return "Skill '" + name + "' 未找到，可用 /skill list 查看可用 skill";
                        }
                        return "Skill '" + name + "' 已被禁用，可用 /skill on " + name + " 启用";
                    }
                    String body = skill.body();
                    int originalLen = body == null ? 0 : body.length();
                    int max = 5 * 1024;
                    String injected = body == null ? "" : body;
                    if (injected.length() > max) {
                        injected = injected.substring(0, max)
                                + "\n\n...(skill body truncated, full content via /skill show " + name + ")";
                    }
                    if (skillContextBuffer != null) {
                        skillContextBuffer.push(name, injected);
                    }
                    return "已加载 skill '" + name + "' 的完整指引（" + originalLen
                            + " bytes），将在下一轮上下文中以 \"## 已加载 Skill：" + name + "\" 段出现。";
                }
        ));
    }

    private void registerMemoryTools() {
        tools.put("save_memory", new Tool(
                "save_memory",
                "当且仅当用户明确说“记一下”“记住”“以后记得”或要求保存长期偏好/稳定事实时调用，把精炼事实写入长期记忆；scope 默认 project，跨项目偏好才用 global；不要保存一次性任务请求、临时文件名或模型猜测。",
                createParameters(
                        new Param("fact", "string", "要长期保存的稳定事实或用户偏好，必须精炼、可跨会话复用", true),
                        new Param("scope", "string", "记忆作用域。默认 project；跨项目长期偏好才用 global", false)
                                .enumValues("project", "global")
                                .defaultValue("project")
                ),
                ToolMetadata.lowWrite("会写入 PaiCLI 长期记忆，不修改项目文件"),
                args -> {
                    String fact = args.get("fact");
                    if (fact == null || fact.isBlank()) {
                        return "保存长期记忆失败: fact 不能为空";
                    }
                    if (memorySaver == null) {
                        return "保存长期记忆失败: 记忆保存器未初始化";
                    }
                    String normalized = fact.trim();
                    String scope = "global".equalsIgnoreCase(args.get("scope")) ? "global" : "project";
                    memorySaver.accept(normalized, scope);
                    return "💾 已保存到长期记忆(" + scope + "): " + normalized;
                }
        ));
    }

    private void registerSnapshotTools() {
        tools.put("revert_turn", new Tool(
                "revert_turn",
                "恢复到 Side-Git 记录的最近第 N 个 pre-turn 快照。会先记录 pre-restore 快照；属于高危写入操作，必须经 HITL 审批。",
                createParameters(new Param("offset", "integer", "要恢复的 pre-turn 快照序号，1 表示最近一次任务开始前", false)
                        .range(1, null)
                        .defaultValue(1)),
                ToolMetadata.highRisk("将按 Side-Git 快照批量恢复工作区文件，可能覆盖当前未保存修改"),
                args -> {
                    int offset = parseInt(args.get("offset"), 1);
                    try {
                        RestoreResult result = snapshotService.restorePreTurn(Math.max(1, offset));
                        return result.formatForCli();
                    } catch (Exception e) {
                        return "恢复快照失败: " + e.getMessage();
                    }
                }
        ));
    }

    private void registerOperationTools() {
        tools.put("tool_status", new Tool(
                "tool_status",
                "查询有副作用工具调用的执行状态（只读）。当工具结果为 PENDING/UNKNOWN，或返回 operation_id 时，用它确认是否已成功、失败、仍在运行或已补偿",
                createParameters(new Param("operation_id", "string", "工具结果返回的 operation_id，例如 tool_ab12cd34ef56", true)),
                ToolMetadata.readOnly("只查询工具操作状态，不修改项目文件"),
                args -> "当前运行模式未启用工具操作存储，无法查询 operation_id: "
                        + args.getOrDefault("operation_id", "")
        ));
        tools.put("tool_compensate", new Tool(
                "tool_compensate",
                "按 operation_id 补偿一个已成功且可逆的写工具副作用。属于高危操作；只能在用户明确要求回滚/补偿该次操作时使用",
                createParameters(new Param("operation_id", "string", "要补偿的工具 operation_id，例如 tool_ab12cd34ef56", true)),
                ToolMetadata.highRisk("按快照恢复某次写工具执行前的文件状态，可能覆盖当前工作区修改"),
                args -> "当前运行模式未启用工具操作存储，无法补偿 operation_id: "
                        + args.getOrDefault("operation_id", "")
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim())
                || "yes".equalsIgnoreCase(value.trim());
    }

    private static String normalizeGlob(String pattern) {
        String normalized = pattern == null ? "**/*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "**/*";
        }
        if (!normalized.contains("/") && !normalized.startsWith("**")) {
            return "**/" + normalized;
        }
        return normalized;
    }

    private static String normalizeFileNameGlob(String pattern) {
        String normalized = pattern == null ? "*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "*";
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static final class SearchFileVisitor extends SimpleFileVisitor<Path> {
        private final Path projectRoot;
        private final java.util.function.Consumer<Path> fileConsumer;

        private SearchFileVisitor(Path projectRoot, java.util.function.Consumer<Path> fileConsumer) {
            this.projectRoot = projectRoot;
            this.fileConsumer = fileConsumer;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (!dir.equals(projectRoot) && SEARCH_EXCLUDED_DIRS.contains(name)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileConsumer.accept(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
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
        if (shouldPreferStepSearch() && tools.containsKey(STEP_SEARCH_TOOL)) {
            ObjectNode args = mapper.createObjectNode();
            args.put("query", query.trim());
            putIfStepToolAccepts(STEP_SEARCH_TOOL, args, topK,
                    "top_k", "topK", "max_results", "num_results", "limit", "count");
            ToolOutput output = executeToolOutput(STEP_SEARCH_TOOL, args.toString());
            if (isUsableMcpOutput(output)) {
                return "🔍 [StepSearch] " + query.trim() + "\n\n" + output.text().trim();
            }
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

    private void runPostEditLspHook(String displayPath, Path safePath) {
        try {
            if (lspManager != null) {
                lspManager.runPostEditLspHook(displayPath, safePath);
            }
        } catch (Exception ignored) {
            // LSP 诊断是 post-edit 辅助信号，失败不能影响工具主结果。
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
        if (shouldPreferStepSearch() && tools.containsKey(STEP_FETCH_TOOL)) {
            ObjectNode args = mapper.createObjectNode();
            args.put("url", url.trim());
            putIfStepToolAccepts(STEP_FETCH_TOOL, args, maxChars,
                    "max_chars", "maxChars", "limit", "max_length", "maxLength");
            ToolOutput output = executeToolOutput(STEP_FETCH_TOOL, args.toString());
            if (isUsableMcpOutput(output)) {
                return "🌐 [StepSearch] 抓取: " + url.trim() + "\n\n" + output.text().trim();
            }
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

    private boolean shouldPreferStepSearch() {
        return "step".equals(currentProvider) && currentModel.startsWith("step-3.7-flash");
    }

    private void putIfStepToolAccepts(String toolName, ObjectNode args, int value, String... names) {
        if (value <= 0 || names == null || names.length == 0) {
            return;
        }
        McpRegisteredTool tool = mcpTools.get(toolName);
        JsonNode properties = tool == null ? null : tool.descriptor().inputSchema().path("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }
        for (String name : names) {
            if (properties.has(name)) {
                args.put(name, value);
                return;
            }
        }
    }

    private boolean isUsableMcpOutput(ToolOutput output) {
        if (output == null || output.text() == null || output.text().isBlank()) {
            return false;
        }
        if (!output.ok() || output.status() == ToolExecutionStatus.FAILED) {
            return false;
        }
        String text = output.text().trim();
        return !text.startsWith("[HITL]")
                && !text.startsWith("🛡️")
                && !text.startsWith("工具执行失败")
                && !text.startsWith("未知工具")
                && !text.startsWith("MCP 工具返回错误");
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
        parameters.put("additionalProperties", false);
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if (param.format() != null && !param.format().isBlank()) {
                prop.put("format", param.format());
            }
            if (!param.enumValues().isEmpty()) {
                ArrayNode enumNode = prop.putArray("enum");
                for (String value : param.enumValues()) {
                    enumNode.add(value);
                }
            }
            if (param.minimum() != null) {
                prop.put("minimum", param.minimum());
            }
            if (param.maximum() != null) {
                prop.put("maximum", param.maximum());
            }
            if (param.maxLength() != null) {
                prop.put("maxLength", param.maxLength());
            }
            if (param.defaultValue() != null) {
                putDefaultValue(prop, param.defaultValue());
            }
            if (param.required()) {
                required.add(param.name());
            }
        }

        return parameters;
    }

    private static void putDefaultValue(ObjectNode prop, Object defaultValue) {
        if (defaultValue instanceof Integer value) {
            prop.put("default", value);
        } else if (defaultValue instanceof Long value) {
            prop.put("default", value);
        } else if (defaultValue instanceof Boolean value) {
            prop.put("default", value);
        } else if (defaultValue instanceof String value) {
            prop.put("default", value);
        } else {
            prop.putPOJO("default", defaultValue);
        }
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
        registerMcpToolOutput(descriptor, args -> ToolOutput.text(invoker.apply(args)));
    }

    public synchronized void registerMcpToolOutput(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        String toolName = descriptor.namespacedName();
        McpRegisteredTool registered = new McpRegisteredTool(descriptor, invoker);
        mcpTools.put(toolName, registered);
        tools.put(toolName, new Tool(
                toolName,
                mcpDescription(descriptor),
                descriptor.inputSchema(),
                ToolMetadata.highRisk("外部 MCP 工具默认高风险，可能访问网络、文件或第三方服务"),
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
        replaceMcpToolOutputsForServer(serverName, newTools,
                descriptor -> args -> ToolOutput.text(invokerFactory.apply(descriptor).apply(args)));
    }

    public synchronized void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                            Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {
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
            registerMcpToolOutput(descriptor, invokerFactory.apply(descriptor));
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
        return doExecuteTool(name, argumentsJson).text();
    }

    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        if (isLegacyExecuteToolOverride()) {
            return ToolOutput.text(executeTool(name, argumentsJson));
        }
        return doExecuteTool(name, argumentsJson);
    }

    protected ToolOutput doExecuteTool(String name, String argumentsJson) {
        if (CancellationContext.isCancelled()) {
            return ToolOutput.error(ToolErrorType.CANCELED, true,
                    "用户取消了此次工具调用", "");
        }
        Tool tool = tools.get(name);
        if (tool == null) {
            return ToolOutput.error(ToolErrorType.NOT_FOUND, true,
                    "未知工具: " + name,
                    "请改用当前工具列表中存在且符合用户意图的工具。");
        }

        boolean shouldAudit = shouldAudit(name);
        long start = System.nanoTime();
        BrowserAuditMetadata auditMetadata = null;
        SandboxAuditMetadata sandboxMetadata = null;
        String fingerprint = auditFingerprint.get();
        if ((fingerprint == null || fingerprint.isBlank()) && shouldAudit) {
            fingerprint = fingerprintFor(name, argumentsJson, getToolMetadata(name));
        }

        try {
            McpRegisteredTool mcpTool = mcpTools.get(name);
            if (mcpTool != null) {
                BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, false);
                auditMetadata = browserCheck.metadata();
                if (browserCheck.blocked()) {
                    throw new PolicyException(browserCheck.reason());
                }
                ToolOutput output = mcpTool.invoker().apply(argumentsJson);
                if (output == null) {
                    output = ToolOutput.text("");
                }
                output = classifyExternalOutput(name, output);
                if (browserGuard != null) {
                    browserGuard.applyAfterExecution(name, argumentsJson, output.text());
                }
                if (shouldAudit) {
                    auditLog.record(AuditLog.AuditEntry.allow(
                            name, argumentsJson, elapsedMillis(start), auditMetadata, fingerprint));
                }
                return output;
            }

            JsonNode args = mapper.readTree(argumentsJson);
            Map<String, String> argMap = new HashMap<>();
            args.fields().forEachRemaining(entry ->
                    argMap.put(entry.getKey(), entry.getValue().asText()));
            String result = tool.executor().execute(argMap);
            sandboxMetadata = auditSandboxMetadata.get();
            ToolOutput output = classifyBuiltinOutput(name, argumentsJson, result, argMap);
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.allow(
                        name, argumentsJson, elapsedMillis(start), auditMetadata, sandboxMetadata, fingerprint));
            }
            return output;
        } catch (PolicyException e) {
            sandboxMetadata = auditSandboxMetadata.get();
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.denyByPolicy(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata, sandboxMetadata, fingerprint));
            }
            return ToolOutput.error(ToolErrorType.POLICY_DENIED, false,
                    "🛡️ 策略拒绝: " + e.getMessage(),
                    "不要绕过策略拒绝；请改用项目内安全路径、更小范围或向用户说明限制。");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            sandboxMetadata = auditSandboxMetadata.get();
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.error(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata, sandboxMetadata, fingerprint));
            }
            return ToolOutput.error(ToolErrorType.INVALID_ARGUMENT, true,
                    "工具参数不是合法 JSON: " + e.getOriginalMessage(),
                    "请重新生成符合该工具 schema 的 JSON 参数。");
        } catch (Exception e) {
            sandboxMetadata = auditSandboxMetadata.get();
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.error(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata, sandboxMetadata, fingerprint));
            }
            return ToolOutput.error(ToolErrorType.EXECUTION_ERROR, false,
                    "工具执行失败: " + e.getMessage(),
                    "请不要原样重试；先分析错误原因，必要时换用更合适的工具或向用户说明。");
        } finally {
            auditSandboxMetadata.remove();
        }
    }

    protected ToolOutput doExecuteToolWithApprovalFingerprint(String name, String argumentsJson, String fingerprint) {
        auditFingerprint.set(fingerprint);
        try {
            return doExecuteTool(name, argumentsJson);
        } finally {
            auditFingerprint.remove();
        }
    }

    private ToolOutput classifyBuiltinOutput(String name, String argumentsJson, String result,
                                             Map<String, String> args) {
        String text = result == null ? "" : result;
        if ("read_file".equals(name)) {
            if (text.startsWith("读取文件失败")) {
                return ToolOutput.error(ToolErrorType.SEMANTIC_ERROR, true, text,
                        "请确认 path 指向项目内存在的普通文本文件。");
            }
            if (text.contains("offset 超出范围")) {
                return ToolOutput.error(ToolErrorType.SEMANTIC_ERROR, true, text,
                        "请根据返回的总行数调整 offset，或不带 offset 重新读取。");
            }
            if (text.contains("...(已截断")) {
                String next = extractAfter(text, "可用 offset=", " ");
                String nextAction = next.isBlank()
                        ? "继续调用 read_file，并设置更大的 offset 读取剩余内容。"
                        : "继续调用 read_file，使用 offset=" + next + " 读取剩余内容。";
                return ToolOutput.partial(text, "read_file_range_truncated", nextAction);
            }
        }
        if ("glob_files".equals(name) && text.contains("已达到上限")) {
            return ToolOutput.partial(text, "max_results_reached",
                    "请缩小 pattern/path 或提高 max_results 后继续查找。");
        }
        if ("grep_code".equals(name) && text.contains("partial: true")) {
            return ToolOutput.partial(text, "grep_result_partial",
                    "请缩小 path/glob/pattern、提高 max_chars，或按 suggested_reads 调用 read_file 获取上下文。");
        }
        if ("web_fetch".equals(name) && text.contains("（已截断）")) {
            return ToolOutput.partial(text, "web_fetch_truncated",
                    "如果需要完整页面内容，请提高 max_chars 或抓取更具体的 URL/章节。");
        }
        if ("tool_status".equals(name) || "tool_compensate".equals(name)) {
            return ToolOutput.error(ToolErrorType.SEMANTIC_ERROR, false, text,
                    "该工具只能在 durable runtime 写工具记录可用时使用；当前模式下请改为检查文件/日志或向用户说明限制。");
        }
        if ("execute_command".equals(name)) {
            if (text.startsWith("命令执行超时")) {
                return ToolOutput.error(
                        new ToolError(ToolErrorType.TIMEOUT, true, text,
                                "命令是否产生副作用不确定；不要重复执行有副作用命令，先检查文件、日志或进程状态。"),
                        ToolExecutionStatus.UNKNOWN);
            }
            if (text.startsWith("用户取消")) {
                return ToolOutput.error(ToolErrorType.CANCELED, true, text, "");
            }
            if (text.startsWith("执行命令失败")) {
                return ToolOutput.error(ToolErrorType.EXECUTION_ERROR, true, text,
                        "请修正命令或改用更合适的只读检查命令。");
            }
            Integer exitCode = parseExitCode(text);
            if (exitCode != null && exitCode != 0) {
                return ToolOutput.error(
                        new ToolError(ToolErrorType.EXECUTION_ERROR, true, text,
                                "命令已执行但返回非零退出码；请根据输出修正命令或向用户说明失败原因。"),
                        ToolExecutionStatus.FAILED);
            }
            if (text.contains("...(输出已截断)")) {
                return ToolOutput.partial(text, "command_output_truncated",
                        "如需完整输出，请运行更精确的命令、重定向到文件后分段读取，或缩小输出范围。");
            }
        }
        return ToolOutput.text(text);
    }

    private ToolOutput classifyExternalOutput(String name, ToolOutput output) {
        if (output == null || output.status() != ToolExecutionStatus.SUCCESS || output.error() != null) {
            return output;
        }
        String text = output.text() == null ? "" : output.text().trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (text.startsWith("MCP 工具返回错误")) {
            return ToolOutput.error(ToolErrorType.EXTERNAL_ERROR, true, text,
                    "请检查 MCP server 返回的错误，修正参数或稍后重试。");
        }
        if (lower.contains("\"status\":\"pending\"") || lower.contains("\"status\":\"queued\"")
                || lower.contains("status: pending") || lower.contains("status: queued")
                || lower.contains("accepted") && lower.contains("operation")) {
            return ToolOutput.status(
                    ToolExecutionStatus.PENDING,
                    output.text(),
                    new ToolResultMeta(false, "external_operation_pending", "", "请查询外部操作状态，不要假定任务已完成。"));
        }
        return output;
    }

    private static Integer parseExitCode(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern.compile("exit code: (-?\\d+)").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extractAfter(String text, String prefix, String stopChars) {
        if (text == null || prefix == null) {
            return "";
        }
        int idx = text.indexOf(prefix);
        if (idx < 0) {
            return "";
        }
        int start = idx + prefix.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch) || ch == ')' || ch == '，' || ch == ',' || ch == ';') {
                break;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private boolean isLegacyExecuteToolOverride() {
        try {
            return getClass()
                    .getMethod("executeTool", String.class, String.class)
                    .getDeclaringClass() != ToolRegistry.class;
        } catch (NoSuchMethodException e) {
            return false;
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
        return executeTools(invocations, ToolIntentContext.empty());
    }

    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations, ToolIntentContext intentContext) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        if (CancellationContext.isCancelled()) {
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(
                            invocation,
                            "用户取消了此次工具调用",
                            new ToolError(ToolErrorType.CANCELED, true, "用户取消了此次工具调用", "")))
                    .toList();
        }
        if (invocations.size() == 1) {
            ToolInvocation invocation = invocations.get(0);
            ToolExecutionResult validation = validateToolIntent(invocation, intentContext);
            if (validation != null) {
                return List.of(validation);
            }
            long startedAt = System.nanoTime();
            ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
            return List.of(ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt)));
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
                            return ToolExecutionResult.failed(
                                    invocation,
                                    "用户取消了此次工具调用",
                                    new ToolError(ToolErrorType.CANCELED, true, "用户取消了此次工具调用", ""));
                        }
                        ToolExecutionResult validation = validateToolIntent(invocation, intentContext);
                        if (validation != null) {
                            return validation;
                        }
                        long startedAt = System.nanoTime();
                        ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
                        return ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt));
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
                    .map(invocation -> ToolExecutionResult.failed(
                            invocation,
                            "工具批次执行被中断",
                            new ToolError(ToolErrorType.EXECUTION_ERROR, true, "工具批次执行被中断", "")))
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private ToolExecutionResult validateToolIntent(ToolInvocation invocation, ToolIntentContext intentContext) {
        ToolIntentValidator validator = toolIntentValidator;
        if (validator == null || invocation == null || intentContext == null || intentContext.isBlank()) {
            return null;
        }
        try {
            Optional<ToolError> error = validator.validate(intentContext, invocation, getToolMetadata(invocation.name()));
            return error.map(toolError -> ToolExecutionResult.completed(
                    invocation,
                    ToolOutput.error(toolError),
                    0)).orElse(null);
        } catch (Exception e) {
            log.warn("tool intent validator failed open for tool {}: {}",
                    invocation.name(), e.getMessage());
            return null;
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    public ToolMetadata getToolMetadata(String name) {
        Tool tool = tools.get(name);
        if (tool != null && tool.metadata() != null) {
            return tool.metadata();
        }
        if (ApprovalPolicy.isMcpTool(name)) {
            return ToolMetadata.highRisk("外部 MCP 工具默认高风险，可能访问网络、文件或第三方服务");
        }
        return ToolMetadata.readOnly("未知工具默认按只读兼容处理");
    }

    protected ApprovalRequest createApprovalRequest(String name, String argumentsJson, ToolMetadata metadata,
                                                    String sensitiveNotice) {
        String fingerprint = fingerprintFor(name, argumentsJson, metadata);
        return ApprovalRequest.of(
                ApprovalActionType.TOOL_CALL,
                name,
                name,
                argumentsJson,
                null,
                projectPath,
                sensitiveNotice,
                metadata,
                fingerprint,
                previewForApproval(name, argumentsJson));
    }

    public String fingerprintFor(String name, String argumentsJson, ToolMetadata metadata) {
        ToolMetadata effective = metadata == null ? getToolMetadata(name) : metadata;
        return ApprovalFingerprint.create(
                ApprovalActionType.TOOL_CALL,
                name,
                argumentsJson,
                effective.riskLevel(),
                projectPath);
    }

    private boolean shouldAudit(String name) {
        return getToolMetadata(name).riskLevel().shouldAuditByDefault();
    }

    private String previewForApproval(String name, String argumentsJson) {
        if (!"write_file".equals(name)) {
            return "";
        }
        try {
            JsonNode args = mapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
            String path = args.path("path").asText("");
            String content = args.path("content").asText("");
            if (path.isBlank()) {
                return "write_file: path 为空，无法生成影响预览";
            }
            Path safe = pathGuard.resolveSafe(path);
            int newBytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (!Files.exists(safe)) {
                return "create file: " + path + " (" + newBytes + " bytes)";
            }
            if (!Files.isRegularFile(safe)) {
                return "write_file: 目标不是普通文件: " + path;
            }
            String before = Files.readString(safe);
            int oldBytes = before.getBytes(StandardCharsets.UTF_8).length;
            return "modify file: " + path + " (" + oldBytes + " -> " + newBytes + " bytes)\n"
                    + compactLineDiff(before, content);
        } catch (PolicyException e) {
            return "preview failed: " + e.getMessage();
        } catch (Exception e) {
            return "preview failed: " + e.getMessage();
        }
    }

    private static String compactLineDiff(String before, String after) {
        if (Objects.equals(before, after)) {
            return "no content change";
        }
        List<String> oldLines = List.of(before.split("\\R", -1));
        List<String> newLines = List.of(after.split("\\R", -1));
        int prefix = 0;
        while (prefix < oldLines.size() && prefix < newLines.size()
                && Objects.equals(oldLines.get(prefix), newLines.get(prefix))) {
            prefix++;
        }
        int oldSuffix = oldLines.size() - 1;
        int newSuffix = newLines.size() - 1;
        while (oldSuffix >= prefix && newSuffix >= prefix
                && Objects.equals(oldLines.get(oldSuffix), newLines.get(newSuffix))) {
            oldSuffix--;
            newSuffix--;
        }
        StringBuilder sb = new StringBuilder();
        int rendered = 0;
        for (int i = prefix; i <= oldSuffix && rendered < 8; i++, rendered++) {
            sb.append("- ").append(i + 1).append(" | ").append(oldLines.get(i)).append("\n");
        }
        for (int i = prefix; i <= newSuffix && rendered < 16; i++, rendered++) {
            sb.append("+ ").append(i + 1).append(" | ").append(newLines.get(i)).append("\n");
        }
        if (oldSuffix - prefix + 1 + newSuffix - prefix + 1 > rendered) {
            sb.append("...(diff preview truncated)");
        }
        return sb.toString().trim();
    }

    private static String mcpDescription(McpToolDescriptor descriptor) {
        String base = descriptor.description() == null || descriptor.description().isBlank()
                ? "MCP server 提供的外部工具"
                : descriptor.description();
        return base + " (MCP server: " + descriptor.serverName() + ", tool: " + descriptor.name()
                + "；外部 MCP 工具，除非用户明确需要该 server 的能力，否则优先使用 PaiCLI 内置工具)";
    }

    private String executeCommand(String command, boolean dangerouslyDisableSandbox) {
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

        SandboxDecision decision = new SandboxPolicy(sandboxConfig).decide(normalized, dangerouslyDisableSandbox);
        if (decision.denied()) {
            SandboxAuditMetadata metadata = new SandboxAuditMetadata(
                    true,
                    false,
                    sandboxConfig.isRequired(),
                    "macos-seatbelt",
                    false,
                    "",
                    sandboxConfig.getNetwork().isEnabled() ? "allow" : "deny",
                    "",
                    List.of(decision.denyReason()));
            auditSandboxMetadata.set(metadata);
            throw new PolicyException(decision.denyReason());
        }
        CommandResult result = new CommandRunner(
                Path.of(projectPath),
                sandboxConfig,
                commandTimeoutSeconds,
                MAX_COMMAND_OUTPUT_CHARS).run(normalized, decision);
        auditSandboxMetadata.set(result.sandboxMetadata());
        return result.text();
    }

    // 记录定义
    private record Param(String name, String type, String description, boolean required,
                         List<String> enumValues, Integer minimum, Integer maximum,
                         Integer maxLength, Object defaultValue, String format) {
        private Param(String name, String type, String description, boolean required) {
            this(name, type, description, required, List.of(), null, null, null, null, null);
        }

        private Param enumValues(String... values) {
            return new Param(name, type, description, required, List.of(values),
                    minimum, maximum, maxLength, defaultValue, format);
        }

        private Param range(Integer minimum, Integer maximum) {
            return new Param(name, type, description, required, enumValues,
                    minimum, maximum, maxLength, defaultValue, format);
        }

        private Param maxLength(Integer maxLength) {
            return new Param(name, type, description, required, enumValues,
                    minimum, maximum, maxLength, defaultValue, format);
        }

        private Param defaultValue(Object defaultValue) {
            return new Param(name, type, description, required, enumValues,
                    minimum, maximum, maxLength, defaultValue, format);
        }

        private Param format(String format) {
            return new Param(name, type, description, required, enumValues,
                    minimum, maximum, maxLength, defaultValue, format);
        }
    }

    public record Tool(String name, String description, JsonNode parameters,
                       ToolMetadata metadata, ToolExecutor executor) {
        public Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {
            this(name, description, parameters, ToolMetadata.readOnly("只读工具"), executor);
        }
    }

    private record McpRegisteredTool(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {}

    public record ToolInvocation(String id, String name, String argumentsJson) {}

    public record ToolExecutionResult(String id, String name, String argumentsJson,
                                      String result, long elapsedMillis, boolean timedOut,
                                      List<com.paicli.llm.LlmClient.ContentPart> imageParts,
                                      ToolError error, ToolExecutionStatus status, ToolResultMeta meta) {
        public ToolExecutionResult(String id, String name, String argumentsJson,
                                   String result, long elapsedMillis, boolean timedOut,
                                   List<com.paicli.llm.LlmClient.ContentPart> imageParts) {
            this(id, name, argumentsJson, result, elapsedMillis, timedOut, imageParts, null,
                    timedOut ? ToolExecutionStatus.UNKNOWN : ToolExecutionStatus.SUCCESS, ToolResultMeta.empty());
        }

        public ToolExecutionResult(String id, String name, String argumentsJson,
                                   String result, long elapsedMillis, boolean timedOut,
                                   List<com.paicli.llm.LlmClient.ContentPart> imageParts,
                                   ToolError error) {
            this(id, name, argumentsJson, result, elapsedMillis, timedOut, imageParts, error,
                    error == null ? (timedOut ? ToolExecutionStatus.UNKNOWN : ToolExecutionStatus.SUCCESS)
                            : ToolExecutionStatus.FAILED,
                    ToolResultMeta.empty());
        }

        public ToolExecutionResult {
            imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
            status = status == null
                    ? (error == null ? (timedOut ? ToolExecutionStatus.UNKNOWN : ToolExecutionStatus.SUCCESS)
                    : ToolExecutionStatus.FAILED)
                    : status;
            meta = meta == null ? ToolResultMeta.empty() : meta;
        }

        private static ToolExecutionResult completed(ToolInvocation invocation, ToolOutput output, long elapsedMillis) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    output == null ? "" : output.text(),
                    elapsedMillis,
                    false,
                    output == null ? List.of() : output.imageParts(),
                    output == null ? null : output.error(),
                    output == null ? ToolExecutionStatus.SUCCESS : output.status(),
                    output == null ? ToolResultMeta.empty() : output.meta());
        }

        private static ToolExecutionResult completed(ToolInvocation invocation, String result, long elapsedMillis) {
            return completed(invocation, ToolOutput.text(result), elapsedMillis);
        }

        private static ToolExecutionResult failed(ToolInvocation invocation, String message) {
            return failed(invocation, message,
                    new ToolError(ToolErrorType.EXECUTION_ERROR, false, "工具执行失败: " + message, ""));
        }

        private static ToolExecutionResult failed(ToolInvocation invocation, String message, ToolError error) {
            ToolError effective = error == null
                    ? new ToolError(ToolErrorType.EXECUTION_ERROR, false, "工具执行失败: " + message, "")
                    : error;
            return completed(invocation, ToolOutput.error(effective), 0);
        }

        private static ToolExecutionResult timedOut(ToolInvocation invocation, long timeoutSeconds) {
            ToolError error = new ToolError(
                    ToolErrorType.TIMEOUT,
                    true,
                    "工具执行超时（" + timeoutSeconds + "秒），已取消",
                    "不要重复执行有副作用工具；请先查询状态、检查文件/日志，或缩小参数范围后再重试。");
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    error.message(),
                    timeoutSeconds * 1000,
                    true,
                    List.of(),
                    error,
                    ToolExecutionStatus.UNKNOWN,
                    new ToolResultMeta(false, "tool_batch_timeout", "", "先验证工具是否已产生副作用，再决定是否重试。")
            );
        }

        public boolean hasImageParts() {
            return imageParts != null && !imageParts.isEmpty();
        }

        public String modelMessageContent() {
            return ToolResultTrustWrapper.wrap(name, result, error, status, meta);
        }
    }

    public interface ToolExecutor {
        String execute(Map<String, String> args);
    }
}
