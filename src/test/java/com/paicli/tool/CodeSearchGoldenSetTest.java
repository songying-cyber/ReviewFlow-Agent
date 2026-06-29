package com.paicli.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSearchGoldenSetTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CHARS = 6_000;

    @Test
    void grepThenReadGoldenSetStaysWithinBudgetAndFindsExpectedCode() throws Exception {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectRoot.toString());
        List<GoldenCase> cases = loadGoldenSet();

        String previous = System.getProperty("paicli.search.disable.rg");
        System.setProperty("paicli.search.disable.rg", "true");
        try {
            for (GoldenCase goldenCase : cases) {
                Path expectedFile = projectRoot.resolve(goldenCase.expectedPath()).normalize();
                int expectedLine = lineContaining(expectedFile, goldenCase.expectedText());
                String grepJson = """
                        {"pattern":"%s","glob":"%s","max_results":20,"head_limit":5,"max_chars":%d}
                        """.formatted(jsonEscape(goldenCase.pattern()), jsonEscape(goldenCase.glob()), MAX_CHARS);

                String grepResult = registry.executeTool("grep_code", grepJson);

                assertTrue(grepResult.length() <= MAX_CHARS + 500,
                        () -> goldenCase.id() + " exceeded grep output budget: " + grepResult.length());
                assertTrue(grepResult.contains(goldenCase.expectedPath() + ":" + expectedLine),
                        () -> goldenCase.id() + " did not locate expected line. Output:\n" + grepResult);
                assertTrue(grepResult.contains("suggested_reads"),
                        () -> goldenCase.id() + " should guide the Agent to read nearby lines");

                int offset = Math.max(1, expectedLine - 20);
                String readJson = """
                        {"path":"%s","offset":%d,"limit":80}
                        """.formatted(jsonEscape(goldenCase.expectedPath()), offset);
                String readResult = registry.executeTool("read_file", readJson);
                assertTrue(readResult.contains(goldenCase.expectedText()),
                        () -> goldenCase.id() + " did not read expected context. Output:\n" + readResult);
            }
        } finally {
            restoreSystemProperty("paicli.search.disable.rg", previous);
        }
    }

    private List<GoldenCase> loadGoldenSet() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/code-search/golden-set.json")) {
            assertNotNull(in, "golden-set.json should be packaged as a test resource");
            List<GoldenCase> cases = MAPPER.readValue(in, new TypeReference<>() {});
            assertFalse(cases.isEmpty(), "golden set should contain at least one case");
            return cases;
        }
    }

    private int lineContaining(Path file, String text) throws Exception {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i + 1;
            }
        }
        throw new AssertionError("Expected text not found in " + file + ": " + text);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void restoreSystemProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private record GoldenCase(
            String id,
            String question,
            String pattern,
            String glob,
            String expectedPath,
            String expectedText
    ) {}
}
