package com.paicli.wechat;

import com.paicli.render.PlainRenderer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WechatTerminalRendererTest {
    @Test
    void streamsOnlyAssistantContentToWechat() {
        List<String> sent = new ArrayList<>();
        WechatTerminalRenderer renderer = new WechatTerminalRenderer(new PlainRenderer(), sent::add);

        renderer.appendThinking("这段 thinking 只应该留在终端");
        assertTrue(sent.isEmpty());

        renderer.appendAssistantContentDelta("你好，");
        assertTrue(sent.isEmpty());

        renderer.finishAssistantContent();
        assertEquals(List.of("你好，"), sent);
        assertTrue(renderer.consumeSentContentFlag());
        assertFalse(renderer.consumeSentContentFlag());
    }

    @Test
    void flushesLongContentBeforeFinalFinish() {
        List<String> sent = new ArrayList<>();
        WechatTerminalRenderer renderer = new WechatTerminalRenderer(new PlainRenderer(), sent::add);

        renderer.appendAssistantContentDelta("a".repeat(920) + "。");

        assertEquals(1, sent.size());
        assertEquals(921, sent.get(0).length());
    }

    @Test
    void doesNotFlushLongContentInMiddleOfSentence() {
        List<String> sent = new ArrayList<>();
        WechatTerminalRenderer renderer = new WechatTerminalRenderer(new PlainRenderer(), sent::add);

        renderer.appendAssistantContentDelta("a".repeat(920));

        assertTrue(sent.isEmpty());
        renderer.finishAssistantContent();
        assertEquals(1, sent.size());
    }

    @Test
    void doesNotFlushInMiddleOfMarkdownTable() {
        List<String> sent = new ArrayList<>();
        WechatTerminalRenderer renderer = new WechatTerminalRenderer(new PlainRenderer(), sent::add);
        String table = """
                | API | 用途 |
                | --- | --- |
                | getupdates | %s |
                | sendmessage | 发送回复 |
                """.formatted("长轮询".repeat(300)).stripTrailing();

        renderer.appendAssistantContentDelta(table);
        assertTrue(sent.isEmpty());

        renderer.appendAssistantContentDelta("\n\n下一段。");
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("- **sendmessage**：发送回复"));
    }

    @Test
    void doesNotFlushBeforeMarkdownCodeFenceCloses() {
        List<String> sent = new ArrayList<>();
        WechatTerminalRenderer renderer = new WechatTerminalRenderer(new PlainRenderer(), sent::add);

        renderer.appendAssistantContentDelta("```java\n" + "System.out.println(\"hi\");\n".repeat(50) + "。");
        assertTrue(sent.isEmpty());

        renderer.appendAssistantContentDelta("\n```\n\n");
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).startsWith("```java"));
        assertTrue(sent.get(0).endsWith("```"));
    }
}
