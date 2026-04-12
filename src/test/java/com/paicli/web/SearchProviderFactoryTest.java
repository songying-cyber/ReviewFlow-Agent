package com.paicli.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchProviderFactoryTest {

    @Test
    void explicitProviderOverridesAutoDetect() {
        assertEquals("zhipu", SearchProviderFactory.pickProvider("zhipu", null, "key", "http://localhost"));
        assertEquals("searxng", SearchProviderFactory.pickProvider("searxng", "glm", "key", "http://localhost"));
        assertEquals("serpapi", SearchProviderFactory.pickProvider("serpapi", null, null, "http://localhost"));
    }

    @Test
    void autoSelectsZhipuWhenGlmKeyPresent() {
        // GLM_API_KEY 优先级最高 —— PaiCLI 主流场景就是 GLM 用户
        assertEquals("zhipu", SearchProviderFactory.pickProvider(null, "glm-key", null, null));
        assertEquals("zhipu", SearchProviderFactory.pickProvider(null, "glm-key", "serp-key", "http://localhost"));
    }

    @Test
    void autoSelectsSerpapiWhenOnlySerpKeyPresent() {
        assertEquals("serpapi", SearchProviderFactory.pickProvider(null, null, "any-key", null));
        assertEquals("serpapi", SearchProviderFactory.pickProvider("", "", "any-key", null));
    }

    @Test
    void autoSelectsSearxngWhenOnlyUrlPresent() {
        assertEquals("searxng", SearchProviderFactory.pickProvider(null, null, null, "http://localhost:8888"));
        assertEquals("searxng", SearchProviderFactory.pickProvider(null, "", "", "http://localhost:8888"));
    }

    @Test
    void fallsBackToZhipuPlaceholder() {
        assertEquals("zhipu", SearchProviderFactory.pickProvider(null, null, null, null));
    }

    @Test
    void normalizesExplicitToLowercase() {
        assertEquals("searxng", SearchProviderFactory.pickProvider("SEARXNG", null, null, null));
        assertEquals("serpapi", SearchProviderFactory.pickProvider("  SerpAPI  ", null, null, null));
        assertEquals("zhipu", SearchProviderFactory.pickProvider("ZHIPU", null, null, null));
    }
}
