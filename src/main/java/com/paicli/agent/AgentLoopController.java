package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Agent 循环控制器。
 *
 * 主退出仍由模型决定：返回最终 content 且不再调用工具时结束。本控制器只负责兜底：
 * token / 硬轮数 / 重复工具 / 连续失败 / 连续无进展 / 无效反思。
 */
public class AgentLoopController {
    public enum ExitReason {
        WITHIN_BUDGET,
        TOKEN_BUDGET_EXCEEDED,
        STAGNATION_DETECTED,
        HARD_ITERATION_LIMIT,
        NO_PROGRESS_LIMIT,
        INVALID_REFLECTION,
        TOOL_FAILURE_LIMIT
    }

    private static final int DEFAULT_STAGNATION_WINDOW = 3;
    private static final int DEFAULT_HARD_MAX_ITERATIONS = 50;
    private static final int DEFAULT_NO_PROGRESS_WINDOW = 4;
    private static final int DEFAULT_INVALID_REFLECTION_WINDOW = 1;
    private static final int DEFAULT_TOOL_FAILURE_WINDOW = 5;

    private final int tokenBudget;
    private final int stagnationWindow;
    private final int hardMaxIterations;
    private final int noProgressWindow;
    private final int invalidReflectionWindow;
    private final int toolFailureWindow;

    private final Deque<String> recentToolSignatures = new ArrayDeque<>();
    private int iteration;
    private int totalInputTokens;
    private int totalOutputTokens;
    private int totalCachedInputTokens;
    private int noProgressCount;
    private int invalidReflectionCount;
    private int consecutiveToolFailures;
    private boolean stagnant;
    private String lastToolResultSignature = "";

    public AgentLoopController(int tokenBudget, int stagnationWindow, int hardMaxIterations) {
        this(tokenBudget, stagnationWindow, hardMaxIterations,
                DEFAULT_NO_PROGRESS_WINDOW,
                DEFAULT_INVALID_REFLECTION_WINDOW,
                DEFAULT_TOOL_FAILURE_WINDOW);
    }

    public AgentLoopController(int tokenBudget, int stagnationWindow, int hardMaxIterations,
                               int noProgressWindow, int invalidReflectionWindow, int toolFailureWindow) {
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
        if (stagnationWindow < 2) {
            throw new IllegalArgumentException("stagnationWindow must be >= 2");
        }
        if (hardMaxIterations <= 0) {
            throw new IllegalArgumentException("hardMaxIterations must be positive");
        }
        if (noProgressWindow < 2) {
            throw new IllegalArgumentException("noProgressWindow must be >= 2");
        }
        if (invalidReflectionWindow < 1) {
            throw new IllegalArgumentException("invalidReflectionWindow must be >= 1");
        }
        if (toolFailureWindow < 1) {
            throw new IllegalArgumentException("toolFailureWindow must be >= 1");
        }
        this.tokenBudget = tokenBudget;
        this.stagnationWindow = stagnationWindow;
        this.hardMaxIterations = hardMaxIterations;
        this.noProgressWindow = noProgressWindow;
        this.invalidReflectionWindow = invalidReflectionWindow;
        this.toolFailureWindow = toolFailureWindow;
    }

    public static AgentLoopController fromSystemProperties() {
        return fromLlmClient(null);
    }

    public static AgentLoopController fromLlmClient(LlmClient llmClient) {
        return new AgentLoopController(
                readIntProperty("paicli.react.token.budget", Integer.MAX_VALUE),
                readIntProperty("paicli.react.stagnation.window", DEFAULT_STAGNATION_WINDOW),
                readIntProperty("paicli.react.hard.max.iterations", DEFAULT_HARD_MAX_ITERATIONS),
                readIntProperty("paicli.react.no.progress.window", DEFAULT_NO_PROGRESS_WINDOW),
                readIntProperty("paicli.react.invalid.reflection.window", DEFAULT_INVALID_REFLECTION_WINDOW),
                readIntProperty("paicli.react.tool.failure.window", DEFAULT_TOOL_FAILURE_WINDOW)
        );
    }

    /** 进入新一轮迭代，返回当前轮次（从 1 开始）。 */
    public int beginIteration() {
        return ++iteration;
    }

    public void recordTokens(int inputTokens, int outputTokens) {
        recordTokens(inputTokens, outputTokens, 0);
    }

    public void recordTokens(int inputTokens, int outputTokens, int cachedInputTokens) {
        this.totalInputTokens += Math.max(0, inputTokens);
        this.totalOutputTokens += Math.max(0, outputTokens);
        this.totalCachedInputTokens += Math.max(0, cachedInputTokens);
    }

    public void recordToolCalls(List<LlmClient.ToolCall> toolCalls) {
        invalidReflectionCount = 0;
        if (toolCalls == null || toolCalls.isEmpty()) {
            recentToolSignatures.clear();
            return;
        }
        String signature = signatureOf(toolCalls);
        recentToolSignatures.addLast(signature);
        while (recentToolSignatures.size() > stagnationWindow) {
            recentToolSignatures.removeFirst();
        }
        if (recentToolSignatures.size() == stagnationWindow) {
            String first = recentToolSignatures.peekFirst();
            stagnant = recentToolSignatures.stream().allMatch(sig -> sig.equals(first));
        }
    }

    public void recordToolResults(List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            noProgressCount++;
            return;
        }
        boolean allFailed = toolResults.stream().allMatch(result -> result == null || looksFailed(result.result()));
        if (allFailed) {
            consecutiveToolFailures++;
            noProgressCount++;
            return;
        }
        consecutiveToolFailures = 0;
        String signature = resultSignature(toolResults);
        if (!signature.isBlank() && signature.equals(lastToolResultSignature)) {
            noProgressCount++;
        } else {
            noProgressCount = 0;
            lastToolResultSignature = signature;
        }
    }

    public void recordNoToolResponse(String content) {
        recentToolSignatures.clear();
        consecutiveToolFailures = 0;
        noProgressCount = 0;
        if (isInvalidReflection(content)) {
            invalidReflectionCount++;
        } else {
            invalidReflectionCount = 0;
        }
    }

    public ExitReason check() {
        if (stagnant) {
            return ExitReason.STAGNATION_DETECTED;
        }
        if (consecutiveToolFailures >= toolFailureWindow) {
            return ExitReason.TOOL_FAILURE_LIMIT;
        }
        if (noProgressCount >= noProgressWindow) {
            return ExitReason.NO_PROGRESS_LIMIT;
        }
        if (invalidReflectionCount >= invalidReflectionWindow) {
            return ExitReason.INVALID_REFLECTION;
        }
        if (totalInputTokens + totalOutputTokens >= tokenBudget) {
            return ExitReason.TOKEN_BUDGET_EXCEEDED;
        }
        if (iteration >= hardMaxIterations) {
            return ExitReason.HARD_ITERATION_LIMIT;
        }
        return ExitReason.WITHIN_BUDGET;
    }

    public int iteration() {
        return iteration;
    }

    public int totalInputTokens() {
        return totalInputTokens;
    }

    public int totalOutputTokens() {
        return totalOutputTokens;
    }

    public int totalCachedInputTokens() {
        return totalCachedInputTokens;
    }

    public int tokenBudget() {
        return tokenBudget;
    }

    public int hardMaxIterations() {
        return hardMaxIterations;
    }

    public int stagnationWindow() {
        return stagnationWindow;
    }

    public int noProgressWindow() {
        return noProgressWindow;
    }

    public int invalidReflectionWindow() {
        return invalidReflectionWindow;
    }

    public int toolFailureWindow() {
        return toolFailureWindow;
    }

    public String describeExit(ExitReason reason) {
        return switch (reason) {
            case WITHIN_BUDGET -> "未触发兜底条件";
            case TOKEN_BUDGET_EXCEEDED -> String.format(Locale.ROOT,
                    "Token 预算已用尽（%d / %d），任务被强制收尾",
                    totalInputTokens + totalOutputTokens, tokenBudget);
            case STAGNATION_DETECTED -> String.format(Locale.ROOT,
                    "检测到连续 %d 轮重复的工具调用，疑似死循环，已强制收尾",
                    stagnationWindow);
            case HARD_ITERATION_LIMIT -> String.format(Locale.ROOT,
                    "达到硬轮数上限（%d），已强制收尾", hardMaxIterations);
            case NO_PROGRESS_LIMIT -> String.format(Locale.ROOT,
                    "连续 %d 轮没有取得新的工具观察结果，已停止以避免空转",
                    noProgressWindow);
            case INVALID_REFLECTION -> String.format(Locale.ROOT,
                    "连续 %d 轮只产生无效反思或空响应，已停止以避免空转",
                    invalidReflectionWindow);
            case TOOL_FAILURE_LIMIT -> String.format(Locale.ROOT,
                    "连续 %d 轮工具调用失败，已停止以避免重复无效重试",
                    toolFailureWindow);
        };
    }

    private static String signatureOf(List<LlmClient.ToolCall> toolCalls) {
        StringBuilder sb = new StringBuilder();
        for (LlmClient.ToolCall tc : toolCalls) {
            if (tc == null || tc.function() == null) {
                continue;
            }
            sb.append(tc.function().name()).append('|').append(tc.function().arguments()).append(';');
        }
        return sb.toString();
    }

    private static String resultSignature(List<ToolExecutionResult> results) {
        StringBuilder sb = new StringBuilder();
        for (ToolExecutionResult result : results) {
            if (result == null) {
                continue;
            }
            sb.append(result.name()).append('|').append(compact(result.result())).append(';');
        }
        return sb.toString();
    }

    private static boolean looksFailed(String result) {
        if (result == null || result.isBlank()) {
            return true;
        }
        String normalized = result.toLowerCase(Locale.ROOT);
        return normalized.contains("工具执行失败")
                || normalized.contains("工具执行超时")
                || normalized.contains("策略拒绝")
                || normalized.contains("用户取消了此次工具调用")
                || normalized.contains("失败:");
    }

    static boolean isInvalidReflection(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        String normalized = content.trim().replaceAll("\\s+", " ");
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.matches(".*(我需要|需要|应该|接下来|让我|我将|我会|继续|检查|查看|分析).*")
                && lower.matches(".*(read_file|grep_code|list_dir|execute_command|工具|文件|命令|检查|查看|分析).*")
                && !lower.matches(".*(结论|完成|已|结果|答案|summary|final|done).*");
    }

    private static String compact(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300);
    }

    private static int readIntProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
