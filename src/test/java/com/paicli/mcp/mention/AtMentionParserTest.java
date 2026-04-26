package com.paicli.mcp.mention;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AtMentionParserTest {
    @Test
    void parsesSingleResourceMention() {
        List<AtMentionParser.MentionToken> tokens =
                AtMentionParser.parse("看下 @filesystem:file://README.md");

        assertEquals(1, tokens.size());
        assertEquals("filesystem", tokens.get(0).serverName());
        assertEquals("file://README.md", tokens.get(0).uri());
    }

    @Test
    void parsesMultipleMentions() {
        List<AtMentionParser.MentionToken> tokens =
                AtMentionParser.parse("@fs:file://a 和 @repo:git://b");

        assertEquals(2, tokens.size());
        assertEquals("git://b", tokens.get(1).uri());
    }

    @Test
    void ignoresInvalidMentions() {
        assertTrue(AtMentionParser.parse("@1fs:file://a @fs:/bad").isEmpty());
    }

    @Test
    void stopsPathBeforeNextAtSymbol() {
        List<AtMentionParser.MentionToken> tokens =
                AtMentionParser.parse("@fs:file://a@repo:git://b");

        assertEquals(2, tokens.size());
        assertEquals("file://a", tokens.get(0).uri());
        assertEquals("git://b", tokens.get(1).uri());
    }

    @Test
    void ignoresMentionsInsideQuotes() {
        assertTrue(AtMentionParser.parse("这是 \"@fs:file://a\" 字面量").isEmpty());
    }
}
