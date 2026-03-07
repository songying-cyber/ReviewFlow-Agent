package com.paicli.memory;

import java.time.Instant;
import java.util.Map;

/**
 * 记忆条目 - Memory 系统的基础数据单元
 */
public class MemoryEntry {
    private final String id;
    private final String content;
    private final MemoryType type;
    private final Instant timestamp;
    private final Map<String, String> metadata;
    private final int tokenCount;

    public enum MemoryType {
        CONVERSATION,  // 对话记忆
        FACT,          // 事实记忆（用户偏好、项目信息等）
        SUMMARY,       // 摘要记忆
        TOOL_RESULT    // 工具执行结果
    }

    public MemoryEntry(String id, String content, MemoryType type, Map<String, String> metadata, int tokenCount) {
        this(id, content, type, Instant.now(), metadata, tokenCount);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount) {
        this.id = id;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.metadata = metadata != null ? metadata : Map.of();
        this.tokenCount = tokenCount;
    }

    public String getId() { return id; }
    public String getContent() { return content; }
    public MemoryType getType() { return type; }
    public Instant getTimestamp() { return timestamp; }
    public Map<String, String> getMetadata() { return metadata; }
    public int getTokenCount() { return tokenCount; }

    /**
     * 粗略估算 token 数（中文约 1.5 字/token，英文约 4 字符/token）
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long chineseChars = text.chars().filter(c -> c > 0x4E00 && c < 0x9FFF).count();
        long otherChars = text.length() - chineseChars;
        return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
    }

    @Override
    public String toString() {
        return "[%s] %s: %s".formatted(type, id,
                content.length() > 80 ? content.substring(0, 80) + "..." : content);
    }
}
