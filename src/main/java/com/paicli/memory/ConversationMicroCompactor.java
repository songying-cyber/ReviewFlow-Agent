package com.paicli.memory;

import com.paicli.llm.LlmClient;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight cleanup for old tool results before full conversation compaction.
 *
 * Tool outputs are often the largest part of a coding-agent context. This class
 * keeps recent tool observations intact and replaces older bulky tool messages
 * with a short marker, without touching assistant tool-call messages.
 */
public class ConversationMicroCompactor {
    private static final int DEFAULT_RETAIN_RECENT_TOOL_RESULTS = 2;
    private static final int DEFAULT_MIN_TOOL_RESULT_TOKENS = 300;
    private static final double DEFAULT_TRIGGER_RATIO = 0.75;
    private static final String MARKER_PREFIX = "[旧工具结果已被 microcompact 清理";

    private static final Set<String> COMPACTABLE_TOOLS = Set.of(
            "read_file",
            "grep_code",
            "glob_files",
            "list_dir",
            "search_code",
            "execute_command",
            "web_search",
            "web_fetch",
            "write_file",
            "create_project",
            "load_skill"
    );

    private final int retainRecentToolResults;
    private final int minToolResultTokens;
    private final double triggerRatio;

    public ConversationMicroCompactor() {
        this(DEFAULT_RETAIN_RECENT_TOOL_RESULTS, DEFAULT_MIN_TOOL_RESULT_TOKENS, DEFAULT_TRIGGER_RATIO);
    }

    ConversationMicroCompactor(int retainRecentToolResults, int minToolResultTokens, double triggerRatio) {
        this.retainRecentToolResults = Math.max(1, retainRecentToolResults);
        this.minToolResultTokens = Math.max(1, minToolResultTokens);
        this.triggerRatio = Math.max(0.0, Math.min(1.0, triggerRatio));
    }

    public MicroCompactionResult compactIfNeeded(List<LlmClient.Message> history, int fullCompactTriggerTokens) {
        if (history == null || history.isEmpty()) {
            return MicroCompactionResult.empty();
        }
        int beforeTokens = TokenBudget.estimateMessagesTokens(history);
        if (fullCompactTriggerTokens > 0 && beforeTokens < fullCompactTriggerTokens * triggerRatio) {
            return MicroCompactionResult.empty(beforeTokens, beforeTokens);
        }
        return compact(history, beforeTokens);
    }

    public MicroCompactionResult compactNow(List<LlmClient.Message> history) {
        if (history == null || history.isEmpty()) {
            return MicroCompactionResult.empty();
        }
        return compact(history, TokenBudget.estimateMessagesTokens(history));
    }

    private MicroCompactionResult compact(List<LlmClient.Message> history, int beforeTokens) {
        Map<String, String> toolNames = collectToolNames(history);
        int seenToolResultsFromTail = 0;
        int compacted = 0;

        for (int i = history.size() - 1; i >= 0; i--) {
            LlmClient.Message msg = history.get(i);
            if (!"tool".equals(msg.role())) {
                continue;
            }
            seenToolResultsFromTail++;
            if (seenToolResultsFromTail <= retainRecentToolResults) {
                continue;
            }
            String content = msg.content();
            if (content == null || content.isBlank() || isMicrocompacted(content)) {
                continue;
            }
            int contentTokens = MemoryEntry.estimateTokens(content);
            if (contentTokens < minToolResultTokens) {
                continue;
            }
            String toolName = toolNames.getOrDefault(msg.toolCallId(), "unknown");
            if (!"unknown".equals(toolName) && !COMPACTABLE_TOOLS.contains(toolName)) {
                continue;
            }
            String replacement = replacement(toolName, contentTokens);
            history.set(i, new LlmClient.Message("tool", replacement, null, null, msg.toolCallId()));
            compacted++;
        }

        if (compacted == 0) {
            return MicroCompactionResult.empty(beforeTokens, beforeTokens);
        }
        int afterTokens = TokenBudget.estimateMessagesTokens(history);
        return new MicroCompactionResult(compacted, beforeTokens, afterTokens);
    }

    public static ToolResultStats analyze(List<LlmClient.Message> history) {
        if (history == null || history.isEmpty()) {
            return new ToolResultStats(0, 0, 0, 0);
        }
        int count = 0;
        int tokens = 0;
        int compacted = 0;
        int bulky = 0;
        for (LlmClient.Message msg : history) {
            if (!"tool".equals(msg.role())) {
                continue;
            }
            count++;
            int messageTokens = TokenBudget.estimateMessagesTokens(List.of(msg));
            tokens += messageTokens;
            if (isMicrocompacted(msg.content())) {
                compacted++;
            } else if (MemoryEntry.estimateTokens(msg.content()) >= DEFAULT_MIN_TOOL_RESULT_TOKENS) {
                bulky++;
            }
        }
        return new ToolResultStats(count, tokens, compacted, bulky);
    }

    public static boolean isMicrocompacted(String content) {
        return content != null && content.startsWith(MARKER_PREFIX);
    }

    private static Map<String, String> collectToolNames(List<LlmClient.Message> history) {
        Map<String, String> names = new HashMap<>();
        for (LlmClient.Message msg : history) {
            if (msg.toolCalls() == null) {
                continue;
            }
            for (LlmClient.ToolCall call : msg.toolCalls()) {
                if (call == null || call.id() == null || call.function() == null) {
                    continue;
                }
                names.put(call.id(), call.function().name());
            }
        }
        return names;
    }

    private static String replacement(String toolName, int contentTokens) {
        return String.format(Locale.ROOT,
                "%s；tool=%s，原始内容约 %s tokens。必要时请重新读取文件或重新执行工具。]",
                MARKER_PREFIX,
                toolName == null || toolName.isBlank() ? "unknown" : toolName,
                formatTokens(contentTokens));
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return String.format(Locale.ROOT, "%.1fk", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    public record MicroCompactionResult(int compactedToolResults, int beforeTokens, int afterTokens) {
        static MicroCompactionResult empty() {
            return new MicroCompactionResult(0, 0, 0);
        }

        static MicroCompactionResult empty(int beforeTokens, int afterTokens) {
            return new MicroCompactionResult(0, beforeTokens, afterTokens);
        }

        public boolean compacted() {
            return compactedToolResults > 0;
        }
    }

    public record ToolResultStats(int count, int tokens, int microcompacted, int bulky) {
    }
}
