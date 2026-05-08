package com.paicli.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExplicitMemoryHintsTest {

    @Test
    void extractsYuqueLoginChromePreferenceFromExplicitRememberRequest() {
        String fact = ExplicitMemoryHints.browserLoginFact(
                "你可以直接复用我已经登录的Chrome，记一下",
                List.of("请打开 https://www.yuque.com/itwanger/gykdzg 这个语雀文档")
        );

        assertEquals("访问 yuque.com（语雀）时优先复用用户已登录的 Chrome 登录态。", fact);
    }

    @Test
    void ignoresBrowserPreferenceWithoutExplicitRememberIntent() {
        String fact = ExplicitMemoryHints.browserLoginFact(
                "你可以直接复用我已经登录的Chrome",
                List.of("https://www.yuque.com/itwanger/gykdzg")
        );

        assertNull(fact);
    }

    @Test
    void fallsBackToGenericBrowserLoginPreferenceWhenNoSiteIsKnown() {
        String fact = ExplicitMemoryHints.browserLoginFact(
                "以后需要登录态时可以直接用我已经登录的 Chrome，记住",
                List.of()
        );

        assertEquals("用户明确允许在需要登录态的网站访问中复用已登录 Chrome。", fact);
    }
}
