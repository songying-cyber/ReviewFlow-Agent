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

        renderer.appendAssistantContentDelta("a".repeat(200));

        assertEquals(1, sent.size());
        assertEquals(200, sent.get(0).length());
    }
}
