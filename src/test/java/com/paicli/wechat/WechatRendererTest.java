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
        assertEquals("**标题**\n**加粗**", WechatRenderer.filterMarkdown("#标题\n** 加粗 **"));
    }

    @Test
    void formatsMarkdownTableForMobileWechat() {
        String markdown = """
                | API | 用途 |
                | --- | --- |
                | getupdates | 长轮询 |
                | sendmessage | 发送回复 |
                """;

        String expected = """
                - **getupdates**：长轮询
                - **sendmessage**：发送回复
                """.trim();

        assertEquals(expected, WechatRenderer.filterMarkdown(markdown));
    }

    @Test
    void unwrapsProseFlowCodeFence() {
        String markdown = """
                ```
                用户发消息 → 微信服务器 → POST XML到你的服务器 URL → 你的服务器处理并返回 XML
                ```
                """;

        String expected = """
                用户发消息
                → 微信服务器
                → POST XML到你的服务器 URL
                → 你的服务器处理并返回 XML
                """.trim();
        assertEquals(expected, WechatRenderer.filterMarkdown(markdown));
    }

    @Test
    void preservesRealCodeFenceMarkers() {
        String markdown = """
                ```java
                System.out.println("hi");
                ```
                """;

        assertEquals(markdown.trim(), WechatRenderer.filterMarkdown(markdown));
    }

    @Test
    void removesUnsupportedImageMarkdown() {
        assertEquals("前后", WechatRenderer.filterMarkdown("前![alt](https://example.com/a.png)后"));
    }

    @Test
    void downgradesUnsupportedDeepHeadings() {
        assertEquals("小标题\n**一级**", WechatRenderer.filterMarkdown("##### 小标题\n#一级"));
    }

    @Test
    void stripsCjkItalicButKeepsBoldAndInlineCode() {
        assertEquals("中文强调 **加粗** `code` *ascii*",
                WechatRenderer.filterMarkdown("*中文强调* **加粗** `code` *ascii*"));
    }

    @Test
    void normalizesMalformedHeadingAndBoldFromWechatScreenshots() {
        assertEquals("**2.微信小程序接入核心流程：**",
                WechatRenderer.filterMarkdown("##2.微信小程序接入**核心流程： **"));
    }

    @Test
    void convertsCollapsedMarkdownTableIntoKeyValueList() {
        String raw = "|---|---|| AppID / AppSecret |每个微信应用的身份凭证 || Access Token |调用服务端 API 的短期凭证 |";
        String expected = """
                - **AppID / AppSecret**：每个微信应用的身份凭证
                - **Access Token**：调用服务端 API 的短期凭证
                """.trim();
        assertEquals(expected, WechatRenderer.filterMarkdown(raw));
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
