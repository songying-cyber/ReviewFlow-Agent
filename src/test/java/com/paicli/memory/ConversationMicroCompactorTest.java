package com.paicli.memory;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMicroCompactorTest {

    @Test
    void compactsOldBulkyToolResultsAndKeepsRecentOnes() {
        ConversationMicroCompactor compactor = new ConversationMicroCompactor(2, 20, 0.0);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        addToolRound(history, "c1", "read_file", "old-1 " + longText(300));
        addToolRound(history, "c2", "grep_code", "old-2 " + longText(300));
        addToolRound(history, "c3", "read_file", "recent-1 " + longText(300));
        addToolRound(history, "c4", "execute_command", "recent-2 " + longText(300));

        ConversationMicroCompactor.MicroCompactionResult result = compactor.compactIfNeeded(history, 1);

        assertTrue(result.compacted());
        assertEquals(2, result.compactedToolResults());
        assertTrue(toolContent(history, "c1").contains("microcompact 清理"));
        assertTrue(toolContent(history, "c2").contains("microcompact 清理"));
        assertTrue(toolContent(history, "c3").startsWith("recent-1"));
        assertTrue(toolContent(history, "c4").startsWith("recent-2"));
    }

    @Test
    void skipsSmallToolResults() {
        ConversationMicroCompactor compactor = new ConversationMicroCompactor(1, 200, 0.0);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        addToolRound(history, "c1", "read_file", "small result");
        addToolRound(history, "c2", "read_file", "recent result");

        ConversationMicroCompactor.MicroCompactionResult result = compactor.compactNow(history);

        assertFalse(result.compacted());
        assertEquals("small result", toolContent(history, "c1"));
    }

    @Test
    void reportsToolResultStats() {
        ConversationMicroCompactor compactor = new ConversationMicroCompactor(1, 20, 0.0);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        addToolRound(history, "c1", "read_file", longText(2_000));
        addToolRound(history, "c2", "read_file", longText(2_000));

        compactor.compactNow(history);

        ConversationMicroCompactor.ToolResultStats stats = ConversationMicroCompactor.analyze(history);
        assertEquals(2, stats.count());
        assertEquals(1, stats.microcompacted());
        assertEquals(1, stats.bulky());
    }

    private static void addToolRound(List<LlmClient.Message> history, String callId, String toolName, String result) {
        history.add(LlmClient.Message.assistant(null, null, List.of(new LlmClient.ToolCall(
                callId,
                new LlmClient.ToolCall.Function(toolName, "{}")
        ))));
        history.add(LlmClient.Message.tool(callId, result));
    }

    private static String toolContent(List<LlmClient.Message> history, String callId) {
        return history.stream()
                .filter(msg -> "tool".equals(msg.role()) && callId.equals(msg.toolCallId()))
                .findFirst()
                .orElseThrow()
                .content();
    }

    private static String longText(int chars) {
        return "x".repeat(chars);
    }
}
