package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.context.ContextProfile;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Agent 循环的退出预算。
 *
 * 设计目标是把"是否继续下一轮"的主导权交给 LLM 自己——只要它返回 content 不再调用工具，
 * 循环就退出。本类只承担三种"保险阀"职责，避免模型在异常情况下无限重复同一动作：
 *
 * 1. Token 预算：累计 input + output token 超过阈值后强制收尾（**默认无限**，仅显式配置时生效）
 * 2. 停滞检测：连续 N 次工具调用使用完全相同的工具名 + 参数，判定为死循环
 * 3. 硬轮数兜底：累计迭代轮数超过 hardMaxIterations，作为兜底防御
 *
 * 这三个条件按"先到先触发"判定，任何一个命中都会让循环结束。
 *
 * 配置读取顺序（以 {@link #fromSystemProperties()} 为准）：
 * 1. 系统属性：{@code paicli.react.token.budget} / {@code paicli.react.stagnation.window} /
 *    {@code paicli.react.hard.max.iterations}
 * 2. 默认值：token 预算 = Integer.MAX_VALUE（实质不限）/ 连续 3 次相同工具调用 / 50 轮
 *
 * 设计取舍：长上下文模型（GLM-5.1 200k / DeepSeek V4 1M）配合套餐用户的"无限 token"诉求，
 * 默认不再以 80% × window 为硬限——让 LLM 自然停在它该停的地方。需要严格成本控制的
 * 场景（CI / 自动化批跑）通过 {@code -Dpaicli.react.token.budget=N} 显式启用。
 * 死循环防护交给 stagnation 检测和 hardMaxIterations 两道兜底。
 */
public class AgentBudget {

    public enum ExitReason {
        WITHIN_BUDGET,
        TOKEN_BUDGET_EXCEEDED,
        STAGNATION_DETECTED,
        HARD_ITERATION_LIMIT
    }

    private static final int DEFAULT_STAGNATION_WINDOW = 3;
    private static final int DEFAULT_HARD_MAX_ITERATIONS = 50;

    private final int tokenBudget;
    private final int stagnationWindow;
    private final int hardMaxIterations;

    private final Deque<String> recentToolSignatures = new ArrayDeque<>();
    private int iteration;
    private int totalInputTokens;
    private int totalOutputTokens;
    private int totalCachedInputTokens;
    private boolean stagnant;

    public AgentBudget(int tokenBudget, int stagnationWindow, int hardMaxIterations) {
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
        if (stagnationWindow < 2) {
            throw new IllegalArgumentException("stagnationWindow must be >= 2");
        }
        if (hardMaxIterations <= 0) {
            throw new IllegalArgumentException("hardMaxIterations must be positive");
        }
        this.tokenBudget = tokenBudget;
        this.stagnationWindow = stagnationWindow;
        this.hardMaxIterations = hardMaxIterations;
    }

    public static AgentBudget fromSystemProperties() {
        return fromLlmClient(null);
    }

    public static AgentBudget fromLlmClient(LlmClient llmClient) {
        // ContextProfile 仍按 80% × window 计算 agentTokenBudget，用于 /context 与 token stats 的"软提示"显示；
        // 但 AgentBudget 的硬限默认走 Integer.MAX_VALUE，避免长上下文 + 套餐用户被预算墙卡住。
        // 显式 -Dpaicli.react.token.budget=N 仍可启用硬预算，覆盖默认。
        return new AgentBudget(
                readIntProperty("paicli.react.token.budget", Integer.MAX_VALUE),
                readIntProperty("paicli.react.stagnation.window", DEFAULT_STAGNATION_WINDOW),
                readIntProperty("paicli.react.hard.max.iterations", DEFAULT_HARD_MAX_ITERATIONS)
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

    /**
     * 记录本轮工具调用签名并判断是否进入停滞。
     *
     * 停滞条件：最近 stagnationWindow 轮的"工具名 + 参数"完全相同；
     * 一旦判定为停滞，状态会保持，后续 {@link #check()} 会返回 STAGNATION_DETECTED。
     */
    public void recordToolCalls(List<LlmClient.ToolCall> toolCalls) {
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

    public ExitReason check() {
        if (stagnant) {
            return ExitReason.STAGNATION_DETECTED;
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
        };
    }

    private static String signatureOf(List<LlmClient.ToolCall> toolCalls) {
        StringBuilder sb = new StringBuilder();
        for (LlmClient.ToolCall tc : toolCalls) {
            sb.append(tc.function().name()).append('|').append(tc.function().arguments()).append(';');
        }
        return sb.toString();
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
