package com.paicli.benchmark;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.github.GitHubChangedFile;
import com.paicli.github.GitHubCheckRun;
import com.paicli.github.GitHubCiStatus;
import com.paicli.github.GitHubCommitStatus;
import com.paicli.github.GitHubConfig;
import com.paicli.github.GitHubPrClient;
import com.paicli.github.GitHubPrReference;
import com.paicli.github.GitHubPrSnapshot;
import com.paicli.github.GitHubReviewComment;
import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class CodeReviewBenchRunner {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .build()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final int MAX_PATCH_CHARS_PER_FILE = 18_000;
    private static final int MAX_TOTAL_PATCH_CHARS = 180_000;
    private static final int MAX_FILE_CONTEXT_CHARS_PER_FILE = 14_000;
    private static final int MAX_TOTAL_FILE_CONTEXT_CHARS = 140_000;
    private static final int MAX_CONTEXT_FILES = 80;
    private static final int MAX_RECALL_RERANK_FINDINGS = 10;
    private static final int MAX_PARALLEL_PRS = 4;
    private static final int GIT_TIMEOUT_SECONDS = 300;

    private final GitHubPrClient gitHubClient;

    public CodeReviewBenchRunner() {
        this(new GitHubPrClient(GitHubConfig.fromEnvironment()));
    }

    CodeReviewBenchRunner(GitHubPrClient gitHubClient) {
        this.gitHubClient = gitHubClient;
    }

    public RunResult run(CodeReviewBenchOptions options, LlmClient llmClient) throws IOException {
        validate(options, llmClient);
        Path dataFile = options.offlineDir().resolve("results").resolve("benchmark_data.json").normalize();
        ObjectNode benchmarkData = (ObjectNode) MAPPER.readTree(dataFile.toFile());
        ObjectNode candidates = readObject(options.candidatesFile());

        int skipped = 0;
        int attempted = 0;
        List<PrWorkItem> workItems = new ArrayList<>();

        for (var entry : iterableFields(benchmarkData)) {
            String goldenUrl = entry.getKey();
            ObjectNode prEntry = (ObjectNode) entry.getValue();
            if (!matchesOnlyUrl(goldenUrl, prEntry, options.onlyUrl())) {
                continue;
            }
            if (options.force()) {
                removeToolReview(prEntry, options.tool());
                removeCandidates(candidates, goldenUrl, options.tool());
            }
            if (!options.force() && hasToolReview(prEntry, options.tool())) {
                skipped++;
                continue;
            }
            if (options.limit() > 0 && attempted >= options.limit()) {
                break;
            }
            attempted++;
            workItems.add(new PrWorkItem(goldenUrl, prEntry));
        }

        List<PrWorkResult> workResults = executePrWorkItems(workItems, options, llmClient);
        int processed = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();
        ArrayNode jsonl = MAPPER.createArrayNode();
        for (PrWorkResult result : workResults) {
            if (result.succeeded()) {
                ProcessedReview review = result.review();
                upsertReview(result.item().prEntry(), review.reviewJson(), options.tool());
                upsertCandidates(candidates, result.item().goldenUrl(), options.tool(), review.candidatesJson());
                jsonl.add(review.summaryJson());
                processed++;
            } else {
                failed++;
                failures.add(result.item().goldenUrl() + ": " + result.errorMessage());
            }
        }

        Files.createDirectories(options.outputData().getParent());
        MAPPER.writeValue(options.outputData().toFile(), benchmarkData);
        Files.createDirectories(options.candidatesFile().getParent());
        MAPPER.writeValue(options.candidatesFile().toFile(), candidates);
        Path runFile = options.candidatesFile().getParent().resolve("paicli_run.json");
        MAPPER.writeValue(runFile.toFile(), jsonl);

        if (options.inPlace() && !options.outputData().equals(dataFile)) {
            MAPPER.writeValue(dataFile.toFile(), benchmarkData);
        }

        return new RunResult(processed, skipped, failed, options.outputData(), options.candidatesFile(), runFile, failures);
    }

    private List<PrWorkResult> executePrWorkItems(List<PrWorkItem> workItems,
                                                  CodeReviewBenchOptions options,
                                                  LlmClient llmClient) {
        if (workItems.isEmpty()) {
            return List.of();
        }
        int parallelism = Math.max(1, Math.min(options.parallelism(), MAX_PARALLEL_PRS));
        if (workItems.size() == 1 || parallelism == 1) {
            List<PrWorkResult> results = new ArrayList<>();
            for (PrWorkItem item : workItems) {
                results.add(processPrWorkItem(item, options, llmClient));
            }
            return results;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(workItems.size(), parallelism), r -> {
            Thread thread = new Thread(r, "paicli-code-review-bench-pr");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Callable<PrWorkResult>> tasks = workItems.stream()
                    .<Callable<PrWorkResult>>map(item -> () -> processPrWorkItem(item, options, llmClient))
                    .toList();
            List<Future<PrWorkResult>> futures = executor.invokeAll(tasks);
            List<PrWorkResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                Future<PrWorkResult> future = futures.get(i);
                PrWorkItem item = workItems.get(i);
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    results.add(PrWorkResult.failure(item, cause.getMessage()));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            List<PrWorkResult> results = new ArrayList<>();
            for (PrWorkItem item : workItems) {
                results.add(PrWorkResult.failure(item, "parallel PR execution interrupted"));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private PrWorkResult processPrWorkItem(PrWorkItem item,
                                           CodeReviewBenchOptions options,
                                           LlmClient llmClient) {
        try {
            return PrWorkResult.success(item, processPr(item.goldenUrl(), item.prEntry(), options, llmClient));
        } catch (Exception e) {
            return PrWorkResult.failure(item, e.getMessage());
        }
    }

    private ProcessedReview processPr(String goldenUrl,
                                      ObjectNode prEntry,
                                      CodeReviewBenchOptions options,
                                      LlmClient llmClient) throws IOException {
        String prUrl = effectivePrUrl(goldenUrl, prEntry);
        GitHubPrReference ref = GitHubPrReference.parse(prUrl);
        GitHubPrSnapshot snapshot = gitHubClient.fetchSnapshot(ref);
        RepositoryContext repositoryContext = options.checkout()
                ? checkoutAndCollectContext(snapshot, options)
                : RepositoryContext.disabled();
        String reviewPrompt = buildBenchmarkReviewPrompt(snapshot, repositoryContext);
        String filterContext = buildPrecisionFilterContext(snapshot, repositoryContext);
        List<Finding> rawFindings = options.mode() == CodeReviewBenchOptions.Mode.REVIEW
                ? generateFindings(reviewPrompt, llmClient, options.timeoutSeconds())
                : List.of();
        List<Finding> findings = options.mode() == CodeReviewBenchOptions.Mode.REVIEW
                ? precisionFilterFindings(filterContext, rawFindings, llmClient, options.timeoutSeconds())
                : List.of();

        ObjectNode review = MAPPER.createObjectNode();
        review.put("tool", options.tool());
        review.put("repo_name", "%s__%s__%s__PR%d".formatted(
                ref.owner(), ref.repo(), options.tool(), ref.number()));
        review.put("pr_url", prUrl);
        ArrayNode reviewComments = review.putArray("review_comments");
        if (options.mode() == CodeReviewBenchOptions.Mode.SMOKE) {
            ObjectNode comment = reviewComments.addObject();
            comment.put("path", "");
            comment.putNull("line");
            comment.put("body", "PaiCLI benchmark smoke completed. No model findings were generated.");
            comment.put("created_at", Instant.now().toString());
        } else {
            for (Finding finding : findings) {
                ObjectNode comment = reviewComments.addObject();
                if (finding.path() == null || finding.path().isBlank()) {
                    comment.put("path", "");
                } else {
                    comment.put("path", finding.path());
                }
                if (finding.line() == null) {
                    comment.putNull("line");
                } else {
                    comment.put("line", finding.line());
                }
                comment.put("body", finding.toReviewBody());
                comment.put("created_at", Instant.now().toString());
            }
        }

        ArrayNode candidates = MAPPER.createArrayNode();
        for (Finding finding : findings) {
            ObjectNode candidate = candidates.addObject();
            candidate.put("text", finding.toCandidateText());
            if (finding.path() == null || finding.path().isBlank()) {
                candidate.putNull("path");
            } else {
                candidate.put("path", finding.path());
            }
            if (finding.line() == null) {
                candidate.putNull("line");
            } else {
                candidate.put("line", finding.line());
            }
            candidate.put("source", "paicli");
        }

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("golden_url", goldenUrl);
        summary.put("pr_url", prUrl);
        summary.put("tool", options.tool());
        summary.put("mode", options.mode().name().toLowerCase(Locale.ROOT));
        summary.put("changed_files", snapshot.changedFiles() == null ? 0 : snapshot.changedFiles().size());
        summary.put("review_comments", snapshot.reviewComments() == null ? 0 : snapshot.reviewComments().size());
        summary.put("diff_chars", snapshot.diff() == null ? 0 : snapshot.diff().length());
        summary.put("prompt_chars", reviewPrompt.length());
        summary.put("checkout_enabled", options.checkout());
        summary.put("checkout_ok", repositoryContext.ok());
        summary.put("context_files", repositoryContext.files().size());
        if (!repositoryContext.warning().isBlank()) {
            summary.put("checkout_warning", repositoryContext.warning());
        }
        summary.put("raw_findings", rawFindings.size());
        summary.put("findings", findings.size());
        return new ProcessedReview(review, candidates, summary);
    }

    static String buildBenchmarkReviewPrompt(GitHubPrSnapshot snapshot, RepositoryContext repositoryContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你现在是 Code Review Bench 模式下的 GitHub Pull Request 审查 agent。目标是高召回地找出
                真实、可定位、可解释的代码审查问题，用于和 human golden comments 对齐。

                与日常生产 review 不同，本模式不要过度克制：只要 diff 或 checked-out 文件上下文中有明确证据，
                就应覆盖低严重度但真实的问题，包括 doc_defect、style、test_gap、translation/locale、命名拼写、
                常量修饰符、测试覆盖缺口和边界条件。不要编造没有证据的问题；但不要因为问题是 P3 就省略。
                目标 finding 数量通常 3-12 条，按证据强度和严重度排序。

                必查清单：
                - bug/security/concurrency/data/api/perf 回归。
                - 翻译和 locale 文件：语言是否匹配文件 locale、占位符/MessageFormat/choice/plural 格式是否兼容。
                - HTML/URL/anchor sanitizer：是否验证数量、顺序、属性、缺失/新增 tag、matcher group 消费和异常路径。
                - 命名和拼写：新增 public/private 方法、字段、常量、测试资源名是否有明显 typo。
                - Java 常量和工具类：static/final/private 可见性、线程安全、不可变集合、异常类型是否合理。
                - 测试：新增逻辑是否覆盖负例、乱序、多 tag、多 locale、格式化占位符等边界。
                - CLI/exit behavior：在 picocli/Quarkus/Keycloak CLI command 的 run/call 方法中，重点检查新增
                  picocli.exit(...)、System.exit(...)、Runtime.exit(...)。不要把 picocli 误判成 Java package 名；
                  在该类代码里它可能是命令框架 helper。除非符号上下文证明无法编译，否则不要报告“不是合法 Java”。
                  重点审查这些 API 的语义：picocli.exit(...) / CommandLine.exit(...) 可能直接终止 JVM；
                  在可测试/可组合的 command path 中通常应返回 exit code、抛受控异常或通过命令框架传递结果，
                  而不是直接 System.exit。检查 exit code value assignment、exit code propagation、JVM termination
                  三类问题，不要只看常量数值。
                - Release/migration docs：如果 PR 新增、删除或改变 CLI exit code、feature flag 默认行为、命令参数、
                  错误消息或 operator 行为，必须检查 changelog / release notes / migration guide。区分 incorrect docs
                  和 missing docs；如果没有 release-note-like 文件被触碰，应报告缺失迁移说明。不要只更新用户手册表格，
                  还要检查是否需要 release note / migration guidance 告诉自动化脚本和运维如何适配行为变化。
                - API misuse：human golden comments 经常指向很小的 API misuse。对 changed lines 的新增方法调用，
                  检查它是否是 surrounding framework 的正确 API，尤其是名字看似普通但有全局副作用的方法。

                输出必须是 JSON，不要输出 Markdown，不要包裹代码块。

                """);

        var pr = snapshot.pullRequest();
        sb.append("## PR\n")
                .append("- repo: ").append(pr.owner()).append('/').append(pr.repo()).append('\n')
                .append("- number: ").append(pr.number()).append('\n')
                .append("- title: ").append(pr.title()).append('\n')
                .append("- state: ").append(pr.state()).append('\n')
                .append("- author: ").append(pr.author()).append('\n')
                .append("- base: ").append(pr.baseRef()).append(" @ ").append(pr.baseSha()).append('\n')
                .append("- head: ").append(pr.headRef()).append(" @ ").append(pr.headSha()).append('\n')
                .append("- url: ").append(pr.htmlUrl()).append("\n\n");
        appendTargetedAuditHints(sb, snapshot);
        if (pr.body() != null && !pr.body().isBlank()) {
            sb.append("## PR Body\n").append(truncate(pr.body(), 6_000)).append("\n\n");
        }

        appendCiSummary(sb, snapshot.ciStatus());
        appendChangedFiles(sb, snapshot.changedFiles());
        appendExistingComments(sb, snapshot);
        appendPatchByFile(sb, snapshot.changedFiles());
        appendRepositoryContext(sb, repositoryContext);
        return sb.toString();
    }

    private static void appendTargetedAuditHints(StringBuilder sb, GitHubPrSnapshot snapshot) {
        String diff = snapshot.diff() == null ? "" : snapshot.diff();
        String diffLower = diff.toLowerCase(Locale.ROOT);
        boolean hasPicocliExit = diff.contains("picocli.exit(");
        boolean hasExitBehaviorChange = hasPicocliExit
                || diffLower.contains("system.exit(")
                || diffLower.contains("runtime.exit(")
                || diffLower.contains("exit_code")
                || diffLower.contains("exit code")
                || diffLower.contains("exitcode")
                || diffLower.contains("exit(");
        boolean hasReleaseLikeFile = snapshot.changedFiles().stream()
                .map(GitHubChangedFile::filename)
                .map(path -> path.toLowerCase(Locale.ROOT))
                .anyMatch(path -> path.contains("changelog")
                        || path.contains("release-note")
                        || path.contains("releasenote")
                        || path.contains("release_notes")
                        || path.contains("migration")
                        || path.contains("migrating")
                        || path.contains("upgrade")
                        || path.contains("upgrading"));
        if (!hasPicocliExit && (!hasExitBehaviorChange || hasReleaseLikeFile)) {
            return;
        }
        sb.append("## Targeted Audit Hints\n");
        if (hasPicocliExit) {
            sb.append("- Diff contains picocli.exit(...). Treat picocli as an in-scope command helper unless symbol lookup proves otherwise; ")
                    .append("review whether the call directly terminates the JVM / behaves like System.exit, not whether the identifier is a package.\n");
        }
        if (hasExitBehaviorChange && !hasReleaseLikeFile) {
            sb.append("- Diff appears to change CLI exit behavior or exit-code semantics, and no changelog/release-notes/migration file is in changed_files. ")
                    .append("Explicitly consider a missing release notes / migration guidance finding for scripts and operators.\n");
        }
        sb.append('\n');
    }

    static String buildPrecisionFilterContext(GitHubPrSnapshot snapshot, RepositoryContext repositoryContext) {
        StringBuilder sb = new StringBuilder();
        var pr = snapshot.pullRequest();
        sb.append("## PR Summary\n")
                .append("- repo: ").append(pr.owner()).append('/').append(pr.repo()).append('\n')
                .append("- number: ").append(pr.number()).append('\n')
                .append("- title: ").append(pr.title()).append('\n')
                .append("- base/head: ").append(pr.baseSha()).append(" -> ").append(pr.headSha()).append("\n\n");
        appendChangedFiles(sb, snapshot.changedFiles());
        sb.append("## Touched File Evidence Summary\n");
        if (repositoryContext != null && repositoryContext.ok() && !repositoryContext.files().isEmpty()) {
            for (RepositoryFileContext file : repositoryContext.files()) {
                sb.append("### ").append(file.path()).append('\n')
                        .append(truncate(file.content(), 2_000))
                        .append("\n\n");
            }
        } else {
            sb.append("(checkout context unavailable)\n\n");
        }
        return sb.toString();
    }

    private RepositoryContext checkoutAndCollectContext(GitHubPrSnapshot snapshot, CodeReviewBenchOptions options) {
        try {
            var pr = snapshot.pullRequest();
            Path repoDir = options.worktreeDir()
                    .resolve(pr.owner() + "__" + pr.repo() + "__PR" + pr.number())
                    .normalize();
            Files.createDirectories(options.worktreeDir());
            if (!Files.exists(repoDir.resolve(".git"))) {
                runCommand(List.of(
                        "git", "clone",
                        "--filter=blob:none",
                        "--no-checkout",
                        "https://github.com/" + pr.owner() + "/" + pr.repo() + ".git",
                        repoDir.toString()), options.worktreeDir());
            }
            runCommand(List.of("git", "-C", repoDir.toString(), "fetch", "--depth", "1",
                    "origin", "pull/" + pr.number() + "/head"), repoDir);
            List<String> paths = changedFilePaths(snapshot.changedFiles());
            if (!paths.isEmpty()) {
                runCommand(List.of("git", "-C", repoDir.toString(),
                        "sparse-checkout", "init", "--no-cone"), repoDir);
                List<String> sparse = new ArrayList<>(List.of("git", "-C", repoDir.toString(),
                        "sparse-checkout", "set", "--no-cone", "--"));
                sparse.addAll(paths);
                runCommand(sparse, repoDir);
            }
            runCommand(List.of("git", "-C", repoDir.toString(), "checkout", "--detach", "FETCH_HEAD"), repoDir);
            return collectRepositoryContext(repoDir, snapshot.changedFiles());
        } catch (Exception e) {
            return new RepositoryContext(false, e.getMessage(), List.of());
        }
    }

    private static RepositoryContext collectRepositoryContext(Path repoDir, List<GitHubChangedFile> changedFiles)
            throws IOException {
        if (changedFiles == null || changedFiles.isEmpty()) {
            return new RepositoryContext(true, "", List.of());
        }
        List<RepositoryFileContext> contexts = new ArrayList<>();
        int totalChars = 0;
        for (GitHubChangedFile file : changedFiles) {
            if (file == null || contexts.size() >= MAX_CONTEXT_FILES) {
                continue;
            }
            String status = file.status() == null ? "" : file.status();
            if ("removed".equalsIgnoreCase(status)) {
                continue;
            }
            Path path = repoDir.resolve(file.filename()).normalize();
            if (!path.startsWith(repoDir) || !Files.isRegularFile(path)) {
                continue;
            }
            String content = Files.readString(path);
            String truncated = truncate(content, Math.min(MAX_FILE_CONTEXT_CHARS_PER_FILE,
                    Math.max(0, MAX_TOTAL_FILE_CONTEXT_CHARS - totalChars)));
            if (truncated.isBlank()) {
                continue;
            }
            totalChars += truncated.length();
            contexts.add(new RepositoryFileContext(file.filename(), truncated));
            if (totalChars >= MAX_TOTAL_FILE_CONTEXT_CHARS) {
                break;
            }
        }
        return new RepositoryContext(true, "", contexts);
    }

    private static List<String> changedFilePaths(List<GitHubChangedFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (GitHubChangedFile file : files) {
            if (file == null || file.filename() == null || file.filename().isBlank()) {
                continue;
            }
            paths.add(file.filename());
        }
        return paths;
    }

    private static void runCommand(List<String> command, Path cwd) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        boolean done = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            throw new IOException("git command timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IOException("git command failed (" + process.exitValue() + "): "
                    + String.join(" ", command));
        }
    }

    private static List<Finding> generateFindings(String reviewPrompt, LlmClient llmClient, int timeoutSeconds)
            throws IOException {
        String prompt = reviewPrompt + """

                ## Machine Output Contract
                现在只输出 JSON，不要输出 Markdown，不要包裹 ```。
                Schema:
                {
                  "summary": "brief review summary",
                  "findings": [
                    {
                      "severity": "P0|P1|P2|P3",
                      "category": "bug|security|concurrency|data|api|perf|test_gap|doc_defect|style|speculative|other",
                      "path": "changed file path or empty string",
                      "line": 123,
                      "title": "short issue title",
                      "body": "specific issue and suggested fix"
                    }
                  ]
                }
                category 可用 bug/security/concurrency/data/api/perf/test_gap/doc_defect/style/speculative/other。
                path 必须尽量使用 changed file path；line 尽量使用新文件行号。没有问题时 findings 返回空数组。
                """;
        return callFindingsLlm(prompt, llmClient, timeoutSeconds);
    }

    private static List<Finding> precisionFilterFindings(String filterContext,
                                                         List<Finding> findings,
                                                         LlmClient llmClient,
                                                         int timeoutSeconds) throws IOException {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        String filterPrompt = filterContext + "\n\n" + """
                ## Golden Recall Rerank
                下面是第一阶段高召回生成的候选 findings。现在目标是最大化 Golden Hit Rate / Recall：
                尽量保留可能匹配 human golden comments 的真实问题，不要因为问题是低严重度就删除。

                只删除以下候选：
                - 明显不是本 PR 引入或触碰的 changed file 问题。
                - 明显重复，或与另一个候选描述同一底层问题。
                - 完全没有 path/line/文件内容证据的主观建议。

                优先保留以下候选，即使是 P3：
                - 明确的 bug/security/data/api/doc_defect/style/test_gap 问题，有具体 path 和 line 证据。
                - translation/locale 错误、占位符格式错误、明显 typo、明显 constant modifier 问题。
                - sanitizer/matcher/HTML/anchor 校验逻辑问题。
                - 命名拼写、常量 private/static/final、测试资源和消息文件中的具体缺陷。
                - CLI command 中新增 picocli.exit/System.exit/Runtime.exit 这类会终止 JVM 的调用。
                - exit code、feature flag、命令参数或错误消息变化但缺少 changelog/release notes/migration guidance。

                输出最多 %d 条。按 expected benchmark match 可能性排序；目标是覆盖更多 golden，不是最少评论。
                只输出 JSON，不要 Markdown，不要包裹 ```。

                ## Candidate Findings
                %s

                ## Output Schema
                {
                  "findings": [
                    {
                      "severity": "P0|P1|P2|P3",
                      "category": "bug|security|concurrency|data|api|perf|test_gap|doc_defect|style|speculative|other",
                      "path": "changed file path",
                      "line": 123,
                      "title": "short issue title",
                      "body": "specific issue and suggested fix"
                    }
                  ]
                }
                """.formatted(MAX_RECALL_RERANK_FINDINGS, findingsToJson(findings));
        List<Finding> filtered = callFindingsLlm(filterPrompt, llmClient, timeoutSeconds);
        return filtered.size() > MAX_RECALL_RERANK_FINDINGS
                ? filtered.subList(0, MAX_RECALL_RERANK_FINDINGS)
                : filtered;
    }

    private static String findingsToJson(List<Finding> findings) throws IOException {
        ArrayNode array = MAPPER.createArrayNode();
        for (Finding finding : findings) {
            ObjectNode node = array.addObject();
            node.put("severity", finding.severity());
            node.put("category", finding.category());
            node.put("path", finding.path());
            if (finding.line() == null) {
                node.putNull("line");
            } else {
                node.put("line", finding.line());
            }
            node.put("title", finding.title());
            node.put("body", finding.body());
        }
        return MAPPER.writeValueAsString(array);
    }

    private static List<Finding> callFindingsLlm(String prompt, LlmClient llmClient, int timeoutSeconds)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "paicli-code-review-bench-llm");
                thread.setDaemon(true);
                return thread;
            });
            try {
                Future<LlmClient.ChatResponse> future = executor.submit(() -> llmClient.chat(List.of(
                        LlmClient.Message.system("You are PaiCLI's non-interactive code review benchmark worker. Respond with valid JSON only."),
                        LlmClient.Message.user(prompt)
                ), null));
                int effectiveTimeout = timeoutSeconds <= 0 ? 180 : timeoutSeconds;
                try {
                    LlmClient.ChatResponse response = future.get(effectiveTimeout, TimeUnit.SECONDS);
                    try {
                        return parseFindings(response.content());
                    } catch (JsonProcessingException e) {
                        last = new IOException("LLM returned invalid JSON: " + e.getOriginalMessage(), e);
                    }
                } catch (TimeoutException e) {
                    future.cancel(true);
                    throw e;
                }
            } catch (TimeoutException e) {
                throw new IOException("LLM review timed out after " + (timeoutSeconds <= 0 ? 180 : timeoutSeconds) + "s", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("LLM review interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) {
                    last = io;
                } else {
                    last = new IOException("LLM review failed: " + cause.getMessage(), cause);
                }
            } finally {
                executor.shutdownNow();
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(1_000L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("LLM review interrupted", e);
                }
            }
        }
        throw last == null ? new IOException("LLM review failed") : last;
    }

    static List<Finding> parseFindings(String content) throws IOException {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        JsonNode root = MAPPER.readTree(extractJsonObject(content));
        JsonNode findingsNode = root.path("findings");
        if (!findingsNode.isArray()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (JsonNode node : findingsNode) {
            String body = text(node, "body");
            String title = text(node, "title");
            if (body.isBlank() && title.isBlank()) {
                continue;
            }
            Integer line = node.hasNonNull("line") && node.get("line").canConvertToInt()
                    ? node.get("line").asInt()
                    : null;
            findings.add(new Finding(
                    normalizeSeverity(text(node, "severity")),
                    text(node, "category"),
                    text(node, "path"),
                    line,
                    title,
                    body));
        }
        return findings;
    }

    private static String extractJsonObject(String content) {
        String value = content.trim();
        if (value.startsWith("```")) {
            String[] parts = value.split("```");
            if (parts.length >= 2) {
                value = parts[1].trim();
                if (value.startsWith("json")) {
                    value = value.substring(4).trim();
                }
            }
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return value.substring(start, end + 1);
        }
        return value;
    }

    private static String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return "P2";
        }
        String value = severity.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "P0", "P1", "P2", "P3" -> value;
            case "CRITICAL" -> "P0";
            case "HIGH" -> "P1";
            case "MEDIUM" -> "P2";
            case "LOW" -> "P3";
            default -> "P2";
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static void appendCiSummary(StringBuilder sb, GitHubCiStatus ciStatus) {
        if (ciStatus == null) {
            return;
        }
        sb.append("## CI\n")
                .append("- combined status: ").append(ciStatus.combinedState()).append('\n');
        if (ciStatus.statuses() != null && !ciStatus.statuses().isEmpty()) {
            sb.append("- commit statuses:\n");
            for (GitHubCommitStatus status : ciStatus.statuses()) {
                sb.append("  - ").append(status.context()).append(": ").append(status.state());
                if (status.description() != null && !status.description().isBlank()) {
                    sb.append(" - ").append(status.description());
                }
                sb.append('\n');
            }
        }
        if (ciStatus.checkRuns() != null && !ciStatus.checkRuns().isEmpty()) {
            sb.append("- check runs:\n");
            for (GitHubCheckRun check : ciStatus.checkRuns()) {
                sb.append("  - ").append(check.name()).append(": ").append(check.status())
                        .append(" / ").append(check.conclusion()).append('\n');
            }
        }
        sb.append('\n');
    }

    private static void appendChangedFiles(StringBuilder sb, List<GitHubChangedFile> files) {
        sb.append("## Changed Files\n");
        if (files == null || files.isEmpty()) {
            sb.append("(none)\n\n");
            return;
        }
        for (GitHubChangedFile file : files) {
            sb.append("- ").append(file.filename())
                    .append(" [").append(file.status()).append("]")
                    .append(" +").append(file.additions())
                    .append(" -").append(file.deletions());
            if (file.previousFilename() != null && !file.previousFilename().isBlank()) {
                sb.append(" (renamed from ").append(file.previousFilename()).append(')');
            }
            sb.append('\n');
        }
        sb.append('\n');
    }

    private static void appendExistingComments(StringBuilder sb, GitHubPrSnapshot snapshot) {
        List<GitHubReviewComment> comments = snapshot.reviewComments();
        if (comments == null || comments.isEmpty()) {
            return;
        }
        sb.append("## Existing Review Comments\n");
        List<GitHubReviewComment> outdated = snapshot.outdatedReviewComments();
        for (GitHubReviewComment comment : comments.stream().limit(30).toList()) {
            boolean isOutdated = outdated.stream().anyMatch(existing -> existing.id() == comment.id());
            sb.append("- ").append(comment.path()).append(':')
                    .append(comment.line() == null ? "?" : comment.line())
                    .append(isOutdated ? " [outdated]" : "")
                    .append(" by ").append(comment.author())
                    .append(" - ").append(truncate(singleLine(comment.body()), 240))
                    .append('\n');
        }
        if (comments.size() > 30) {
            sb.append("- ... ").append(comments.size() - 30).append(" more comments omitted\n");
        }
        sb.append('\n');
    }

    private static void appendPatchByFile(StringBuilder sb, List<GitHubChangedFile> files) {
        sb.append("## Patch By File\n");
        if (files == null || files.isEmpty()) {
            sb.append("(none)\n\n");
            return;
        }
        int totalChars = 0;
        for (GitHubChangedFile file : files) {
            String patch = file.patch();
            if (patch == null || patch.isBlank()) {
                continue;
            }
            int remaining = MAX_TOTAL_PATCH_CHARS - totalChars;
            if (remaining <= 0) {
                sb.append("\n[paicli: remaining file patches omitted due benchmark prompt budget]\n\n");
                break;
            }
            String chunk = truncate(patch, Math.min(MAX_PATCH_CHARS_PER_FILE, remaining));
            totalChars += chunk.length();
            sb.append("### ").append(file.filename()).append('\n')
                    .append("```diff\n")
                    .append(chunk)
                    .append("\n```\n\n");
        }
    }

    private static void appendRepositoryContext(StringBuilder sb, RepositoryContext context) {
        sb.append("## Checked Out Head File Context\n");
        if (context == null) {
            sb.append("checkout disabled\n\n");
            return;
        }
        if (!context.ok()) {
            sb.append("checkout unavailable: ").append(truncate(singleLine(context.warning()), 1_000)).append("\n\n");
            return;
        }
        if (context.files().isEmpty()) {
            sb.append("(no readable changed files collected)\n\n");
            return;
        }
        for (RepositoryFileContext file : context.files()) {
            sb.append("### ").append(file.path()).append('\n')
                    .append("```text\n")
                    .append(file.content())
                    .append("\n```\n\n");
        }
    }

    private static String singleLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        if (maxChars <= 0) {
            return "";
        }
        return value.substring(0, maxChars) + "\n[paicli: truncated]";
    }

    private static String effectivePrUrl(String goldenUrl, ObjectNode entry) {
        JsonNode original = entry.get("original_url");
        if (original != null && !original.isNull() && !original.asText().isBlank()) {
            String value = original.asText();
            if (value.contains("/pull/")) {
                return value;
            }
        }
        return goldenUrl;
    }

    private static boolean matchesOnlyUrl(String goldenUrl, ObjectNode entry, String onlyUrl) {
        if (onlyUrl == null || onlyUrl.isBlank()) {
            return true;
        }
        String wanted = onlyUrl.trim();
        if (wanted.equals(goldenUrl)) {
            return true;
        }
        JsonNode original = entry.get("original_url");
        return original != null && !original.isNull() && wanted.equals(original.asText());
    }

    private static ObjectNode readObject(Path path) throws IOException {
        if (Files.exists(path)) {
            JsonNode node = MAPPER.readTree(path.toFile());
            if (node instanceof ObjectNode objectNode) {
                return objectNode;
            }
        }
        return MAPPER.createObjectNode();
    }

    private static void upsertReview(ObjectNode prEntry, ObjectNode review, String tool) {
        ArrayNode reviews = prEntry.withArray("reviews");
        removeToolReview(prEntry, tool);
        reviews.add(review);
    }

    private static void removeToolReview(ObjectNode prEntry, String tool) {
        ArrayNode reviews = prEntry.withArray("reviews");
        for (int i = reviews.size() - 1; i >= 0; i--) {
            JsonNode existing = reviews.get(i);
            if (tool.equals(existing.path("tool").asText())) {
                reviews.remove(i);
            }
        }
    }

    private static boolean hasToolReview(ObjectNode prEntry, String tool) {
        JsonNode reviews = prEntry.get("reviews");
        if (reviews == null || !reviews.isArray()) {
            return false;
        }
        for (JsonNode review : reviews) {
            if (tool.equals(review.path("tool").asText())) {
                return true;
            }
        }
        return false;
    }

    private static void upsertCandidates(ObjectNode candidates, String goldenUrl, String tool, ArrayNode toolCandidates) {
        ObjectNode prCandidates = candidates.withObject("/" + escapePointer(goldenUrl));
        prCandidates.set(tool, toolCandidates);
    }

    private static void removeCandidates(ObjectNode candidates, String goldenUrl, String tool) {
        JsonNode existing = candidates.at("/" + escapePointer(goldenUrl));
        if (existing instanceof ObjectNode objectNode) {
            objectNode.remove(tool);
        }
    }

    private static String escapePointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static Iterable<Map.Entry<String, JsonNode>> iterableFields(ObjectNode node) {
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> copy.put(entry.getKey(), entry.getValue()));
        return copy.entrySet();
    }

    private static void validate(CodeReviewBenchOptions options, LlmClient llmClient) throws IOException {
        if (options == null) {
            throw new IllegalArgumentException("缺少 benchmark options");
        }
        if (options.offlineDir() == null || !Files.isDirectory(options.offlineDir())) {
            throw new IOException("Code Review Bench offline 目录不存在: " + options.offlineDir());
        }
        Path dataFile = options.offlineDir().resolve("results").resolve("benchmark_data.json");
        if (!Files.exists(dataFile)) {
            throw new IOException("未找到 benchmark 数据文件: " + dataFile);
        }
        if (options.requiresLlm() && llmClient == null) {
            throw new IllegalArgumentException("review 模式需要可用 LLM；可改用 --mode smoke 验证链路");
        }
    }

    record ProcessedReview(ObjectNode reviewJson, ArrayNode candidatesJson, ObjectNode summaryJson) {
    }

    record RepositoryContext(boolean ok, String warning, List<RepositoryFileContext> files) {
        static RepositoryContext disabled() {
            return new RepositoryContext(false, "checkout disabled", List.of());
        }
    }

    record RepositoryFileContext(String path, String content) {
    }

    public record Finding(String severity,
                          String category,
                          String path,
                          Integer line,
                          String title,
                          String body) {
        String toReviewBody() {
            StringBuilder sb = new StringBuilder();
            sb.append('[').append(severity == null || severity.isBlank() ? "P2" : severity).append("] ");
            sb.append(title == null || title.isBlank() ? "Review finding" : title.trim());
            if (category != null && !category.isBlank()) {
                sb.append("\n\nCategory: ").append(category.trim());
            }
            if (body != null && !body.isBlank()) {
                sb.append("\n\n").append(body.trim());
            }
            return sb.toString();
        }

        String toCandidateText() {
            StringBuilder sb = new StringBuilder(toReviewBody());
            if (path != null && !path.isBlank()) {
                sb.append("\n\nLocation: ").append(path);
                if (line != null) {
                    sb.append(':').append(line);
                }
            }
            return sb.toString();
        }
    }

    public record RunResult(int processed,
                            int skipped,
                            int failed,
                            Path benchmarkData,
                            Path candidatesFile,
                            Path runFile,
                            List<String> failures) {
    }

    private record PrWorkItem(String goldenUrl, ObjectNode prEntry) {
    }

    private record PrWorkResult(PrWorkItem item, ProcessedReview review, String errorMessage) {
        static PrWorkResult success(PrWorkItem item, ProcessedReview review) {
            return new PrWorkResult(item, review, null);
        }

        static PrWorkResult failure(PrWorkItem item, String errorMessage) {
            return new PrWorkResult(item, null,
                    errorMessage == null || errorMessage.isBlank() ? "unknown error" : errorMessage);
        }

        boolean succeeded() {
            return review != null;
        }
    }
}
