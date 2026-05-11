package com.paicli.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillContextBufferTest {

    @Test
    void drainIsOneShot() {
        SkillContextBuffer buffer = new SkillContextBuffer();
        buffer.push("web-access", "body content");

        String first = buffer.drain();
        assertTrue(first.contains("web-access"));
        assertTrue(first.contains("body content"));

        String second = buffer.drain();
        assertEquals("", second, "drain 后再次调用应为空");
    }

    @Test
    void clearResetsBuffer() {
        SkillContextBuffer buffer = new SkillContextBuffer();
        buffer.push("a", "body a");
        buffer.push("b", "body b");
        buffer.clear();
        assertTrue(buffer.isEmpty());
        assertEquals("", buffer.drain());
    }

    @Test
    void preservesInsertionOrder() {
        SkillContextBuffer buffer = new SkillContextBuffer();
        buffer.push("alpha", "A");
        buffer.push("beta", "B");
        buffer.push("gamma", "C");

        String drained = buffer.drain();
        int alphaIdx = drained.indexOf("alpha");
        int betaIdx = drained.indexOf("beta");
        int gammaIdx = drained.indexOf("gamma");
        assertTrue(alphaIdx < betaIdx && betaIdx < gammaIdx, "应按 push 顺序输出");
    }

    @Test
    void capsAtThreeSkills() {
        SkillContextBuffer buffer = new SkillContextBuffer();
        buffer.push("a", "A");
        buffer.push("b", "B");
        buffer.push("c", "C");
        buffer.push("d", "D");

        assertEquals(3, buffer.size());
        String drained = buffer.drain();
        assertFalse(drained.contains("# a"), "最旧的 skill 应被淘汰");
        assertTrue(drained.contains("b"));
        assertTrue(drained.contains("c"));
        assertTrue(drained.contains("d"));
    }

    @Test
    void duplicatePushReplacesAndRefreshes() {
        SkillContextBuffer buffer = new SkillContextBuffer();
        buffer.push("a", "old body");
        buffer.push("b", "B");
        buffer.push("a", "new body");

        String drained = buffer.drain();
        assertFalse(drained.contains("old body"));
        assertTrue(drained.contains("new body"));
        // 重复 push 后 a 应该排在 b 之后
        assertTrue(drained.indexOf("new body") > drained.indexOf("B"));
    }

    @Test
    void ignoresInvalidPushArgs() {
        SkillContextBuffer buffer = new SkillContextBuffer();
        buffer.push(null, "body");
        buffer.push("", "body");
        buffer.push("ok", null);
        assertTrue(buffer.isEmpty());
    }
}
