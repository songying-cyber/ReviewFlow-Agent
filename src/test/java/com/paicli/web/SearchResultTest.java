package com.paicli.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchResultTest {

    @Test
    void of_extractsHostFromUrl() {
        SearchResult r = SearchResult.of(1, "技术派", "https://paicoding.com/article/1", "示例摘要");
        assertEquals(1, r.position());
        assertEquals("paicoding.com", r.source());
        assertEquals("https://paicoding.com/article/1", r.url());
    }

    @Test
    void of_keepsBlankSourceWhenUrlMissing() {
        SearchResult r = SearchResult.of(1, "纯精选摘要", "", "答案在这里");
        assertEquals("", r.source());
        assertEquals("", r.url());
    }

    @Test
    void of_handlesMalformedUrlGracefully() {
        SearchResult r = SearchResult.of(2, "题", "not-a-url", "snippet");
        assertEquals("", r.source());
    }

    @Test
    void of_trimsTitleAndSnippet() {
        SearchResult r = SearchResult.of(3, "  标题  ", "https://example.com", "  摘要  ");
        assertEquals("标题", r.title());
        assertEquals("摘要", r.snippet());
    }
}
