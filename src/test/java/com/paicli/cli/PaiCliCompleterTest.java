package com.paicli.cli;

import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaiCliCompleterTest {

    @Test
    void suggestsSlashCommandsWhenInputStartsWithSlash() {
        PaiCliCompleter completer = new PaiCliCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/", "/"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.displ().equals("/model")));
        assertTrue(candidates.stream().anyMatch(c -> c.displ().equals("/browser connect")));
        assertTrue(candidates.stream().anyMatch(c -> c.displ().equals("/search <查询>")));
    }

    @Test
    void completesSubCommandWithoutDuplicatingPrefix() {
        PaiCliCompleter completer = new PaiCliCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/mcp r", "r"), candidates);

        Candidate restart = candidates.stream()
                .filter(c -> c.displ().equals("/mcp restart <name>"))
                .findFirst()
                .orElseThrow();
        assertEquals("restart ", restart.value());
    }

    @Test
    void ignoresNormalWords() {
        PaiCliCompleter completer = new PaiCliCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("hello", "hello"), candidates);

        assertTrue(candidates.isEmpty());
    }

    private static ParsedLine parsed(String line, String word) {
        return new ParsedLine() {
            @Override public String word() { return word; }
            @Override public int wordCursor() { return word.length(); }
            @Override public int wordIndex() { return 0; }
            @Override public List<String> words() { return List.of(word); }
            @Override public String line() { return line; }
            @Override public int cursor() { return line.length(); }
        };
    }
}
