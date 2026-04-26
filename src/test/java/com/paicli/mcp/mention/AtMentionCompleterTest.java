package com.paicli.mcp.mention;

import com.paicli.mcp.resources.McpResourceDescriptor;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AtMentionCompleterTest {
    @Test
    void suggestsResourcesWhenWordStartsWithAt() {
        AtMentionCompleter completer = new AtMentionCompleter(() -> List.of(resource("fs", "file://README.md")));
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("@"), candidates);

        assertEquals(1, candidates.size());
        assertEquals("@fs:file://README.md", candidates.get(0).value());
    }

    @Test
    void filtersByPrefix() {
        AtMentionCompleter completer = new AtMentionCompleter(() -> List.of(
                resource("fs", "file://README.md"),
                resource("repo", "git://main")
        ));
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("@rep"), candidates);

        assertEquals(1, candidates.size());
        assertEquals("@repo:git://main", candidates.get(0).value());
    }

    @Test
    void ignoresNormalWords() {
        AtMentionCompleter completer = new AtMentionCompleter(() -> List.of(resource("fs", "file://README.md")));
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("hello"), candidates);

        assertTrue(candidates.isEmpty());
    }

    private static McpResourceDescriptor resource(String server, String uri) {
        return new McpResourceDescriptor(server, uri, "name", "", "desc", "text/plain", null);
    }

    private static ParsedLine parsed(String word) {
        return new ParsedLine() {
            @Override public String word() { return word; }
            @Override public int wordCursor() { return word.length(); }
            @Override public int wordIndex() { return 0; }
            @Override public List<String> words() { return List.of(word); }
            @Override public String line() { return word; }
            @Override public int cursor() { return word.length(); }
        };
    }
}
