package com.paicli.wechat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WechatRendererTest {
    @Test
    void filtersMarkdownHeadingsAndBold() {
        assertEquals("标题\n加粗", WechatRenderer.filterMarkdown("# 标题\n**加粗**"));
    }

    @Test
    void stripsAnsiAndTerminalAnswerMarker() {
        String raw = "\u001B[1m\u001B[32m■\u001B[0m 你好";
        assertEquals("你好", WechatRenderer.filterMarkdown(raw));
    }

    @Test
    void doesNotRenderReasoningOrToolProgressToWechat() {
        List<String> sent = new ArrayList<>();
        WechatRenderer renderer = new WechatRenderer(sent::add);
        assertFalse(renderer.rendersReasoning());
        renderer.appendToolCalls(List.of(new com.paicli.llm.LlmClient.ToolCall(
                "1",
                new com.paicli.llm.LlmClient.ToolCall.Function("read_file", "{}")
        )));
        renderer.flushBuffer();
        assertTrue(sent.isEmpty());
    }

    @Test
    void buffersUntilTurnCompletes() {
        List<String> sent = new ArrayList<>();
        WechatRenderer renderer = new WechatRenderer(sent::add);
        renderer.append("第一段\n\n");
        assertTrue(sent.isEmpty());
        renderer.flushBuffer();
        assertEquals(List.of("第一段"), sent);
    }

    @Test
    void splitsLongOutputIntoWechatSizedChunks() {
        List<String> sent = new ArrayList<>();
        WechatRenderer renderer = new WechatRenderer(sent::add);
        renderer.append("a".repeat(WechatRenderer.MAX_CHARS + 100));
        renderer.flushBuffer();
        assertEquals(2, sent.size());
        assertTrue(sent.get(0).length() <= WechatRenderer.MAX_CHARS);
        assertFalse(sent.get(1).isBlank());
    }
}
