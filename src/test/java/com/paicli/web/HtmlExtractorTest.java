package com.paicli.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlExtractorTest {

    private final HtmlExtractor extractor = new HtmlExtractor();

    @Test
    void extractsArticleTagAsMain() {
        String html = """
                <html><head><title>技术派周刊</title></head>
                <body>
                  <nav><a href="/">首页</a></nav>
                  <article>
                    <h1>第 9 期 · 联网能力</h1>
                    <p>本文介绍 PaiCLI 的 web_search 与 web_fetch 工具实现。</p>
                    <p>核心是把搜索抽象成 SearchProvider 接口，正文抽取走 Jsoup。</p>
                  </article>
                  <footer>© 2026</footer>
                </body></html>
                """;
        HtmlExtractor.Extracted out = extractor.extract(html, "https://paicoding.com");
        assertEquals("技术派周刊", out.title());
        String md = out.markdown();
        assertTrue(md.contains("# 第 9 期"), "应包含 H1: " + md);
        assertTrue(md.contains("web_search"), "应保留正文内容");
        assertFalse(md.contains("首页"), "应去掉 nav: " + md);
        assertFalse(md.contains("© 2026"), "应去掉 footer: " + md);
    }

    @Test
    void rendersListsAsMarkdown() {
        String html = """
                <html><body><main>
                  <h2>清单</h2>
                  <ul>
                    <li>第一项</li>
                    <li>第二项</li>
                  </ul>
                </main></body></html>
                """;
        String md = extractor.extract(html, "").markdown();
        assertTrue(md.contains("- 第一项"));
        assertTrue(md.contains("- 第二项"));
    }

    @Test
    void rendersLinksAsMarkdown() {
        String html = """
                <html><body><article>
                  <p>访问 <a href="https://paicoding.com/about">技术派</a> 了解更多。</p>
                </article></body></html>
                """;
        String md = extractor.extract(html, "https://paicoding.com").markdown();
        assertTrue(md.contains("[技术派](https://paicoding.com/about)"), md);
    }

    @Test
    void rendersCodeBlock() {
        String html = """
                <html><body><article>
                  <pre><code>System.out.println("hi");</code></pre>
                </article></body></html>
                """;
        String md = extractor.extract(html, "").markdown();
        assertTrue(md.contains("```"));
        assertTrue(md.contains("System.out.println"));
    }

    @Test
    void emptyHtmlReturnsEmptyMarkdown() {
        HtmlExtractor.Extracted out = extractor.extract("<html><body><div></div></body></html>", "");
        assertTrue(out.markdown().isEmpty(), "空 body 应返回空 markdown，由调用方提示边界");
    }

    @Test
    void picksDensestBlockWhenNoSemanticContainer() {
        String html = """
                <html><body>
                  <div class="sidebar"><a href="/a">A</a><a href="/b">B</a><a href="/c">C</a></div>
                  <div>
                    <p>这是正文段落，文本足够长，应该被选中作为主块。包含足够多的字符确保超过启发式阈值，
                    避免被按链接密度高的 sidebar 挤掉。</p>
                    <p>第二段继续提供文本支持。</p>
                  </div>
                </body></html>
                """;
        String md = extractor.extract(html, "").markdown();
        assertTrue(md.contains("正文段落"), "应选中文本密度高的块: " + md);
    }
}
