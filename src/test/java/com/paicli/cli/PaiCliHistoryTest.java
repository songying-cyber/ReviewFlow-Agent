package com.paicli.cli;

import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaiCliHistoryTest {
    @TempDir
    Path tempDir;

    @Test
    void filtersSecretsAndImagePayloads() {
        PaiCliHistory history = new PaiCliHistory();

        history.add("帮我读取 pom.xml");
        history.add("GLM_API_KEY=secret");
        history.add("Authorization: Bearer abc");
        history.add("/config provider freellmapi --api-key sk-secret --model auto");
        history.add("@image:data:image/png;base64," + "A".repeat(260));

        assertEquals(1, history.size());
        assertEquals("帮我读取 pom.xml", history.get(history.last()));
    }

    @Test
    void configuresPersistentHistoryFileAndOptions() throws Exception {
        LineReader lineReader = newLineReader();

        Main.configureHistory(lineReader, tempDir);

        Path expected = tempDir.resolve(".paicli").resolve("history").resolve("input.history")
                .toAbsolutePath().normalize();
        assertEquals(expected, lineReader.getVariable(LineReader.HISTORY_FILE));
        assertEquals(2_000, lineReader.getVariable(LineReader.HISTORY_SIZE));
        assertEquals(10_000, lineReader.getVariable(LineReader.HISTORY_FILE_SIZE));
        assertTrue(lineReader.isSet(LineReader.Option.HISTORY_IGNORE_SPACE));
        assertTrue(lineReader.isSet(LineReader.Option.HISTORY_IGNORE_DUPS));
        assertTrue(lineReader.isSet(LineReader.Option.HISTORY_REDUCE_BLANKS));
        assertTrue(lineReader.isSet(LineReader.Option.DISABLE_EVENT_EXPANSION));
        assertTrue(Files.isDirectory(expected.getParent()));
    }

    @Test
    void clearsPersistentHistory() throws Exception {
        LineReader lineReader = newLineReader();
        Main.configureHistory(lineReader, tempDir);
        History history = lineReader.getHistory();
        history.add("第一条");
        history.save();

        Main.clearLineReaderHistory(lineReader);

        assertTrue(history.isEmpty());
        Path expected = tempDir.resolve(".paicli").resolve("history").resolve("input.history")
                .toAbsolutePath().normalize();
        assertFalse(Files.exists(expected) && !Files.readString(expected).isBlank());
    }

    @Test
    void treatsConfiguredDirectoryAsHistoryContainer() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("history-dir"));

        assertEquals(dir.resolve("input.history").toAbsolutePath().normalize(),
                Main.normalizeHistoryFile(dir));
    }

    private static LineReader newLineReader() throws Exception {
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build();

        return LineReaderBuilder.builder()
                .terminal(terminal)
                .history(new PaiCliHistory())
                .build();
    }
}
