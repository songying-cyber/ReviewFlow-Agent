package com.paicli.cli;

import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainInputNormalizationTest {

    @Test
    void keepsMultilinePasteStructure() {
        String normalized = Main.prepareSeedBuffer("请把任务拆成可并行的 DAG:\n1. 读 pom.xml\r\n2. 列出 src/main/java");

        assertEquals("请把任务拆成可并行的 DAG:\n1. 读 pom.xml\n2. 列出 src/main/java", normalized);
    }

    @Test
    void keepsSingleLineInputUntouched() {
        String normalized = Main.prepareSeedBuffer("帮我读取 pom.xml");

        assertEquals("帮我读取 pom.xml", normalized);
    }

    @Test
    void normalizesLegacyCarriageReturnsWithoutChangingTextLayout() {
        String normalized = Main.prepareSeedBuffer("第一行\r第二行\r\n第三行");

        assertEquals("第一行\n第二行\n第三行", normalized);
    }

    @Test
    void startupHintsKeepSlashCommandDetailsOutOfInitialScreen() {
        List<String> hints = Main.startupHints();

        assertTrue(hints.stream().anyMatch(hint -> hint.contains("输入 '/' 查看命令")));
        assertTrue(hints.stream().noneMatch(hint -> hint.contains("/model")));
        assertTrue(hints.stream().noneMatch(hint -> hint.contains("/index [路径]")));
    }

    @Test
    void slashCommandHintsIncludeRagSlashCommands() {
        List<String> commands = Main.slashCommandHints().stream()
                .map(Main.SlashCommandHint::display)
                .toList();

        assertTrue(commands.contains("/index [路径]"));
        assertTrue(commands.contains("/search <查询>"));
        assertTrue(commands.contains("/graph <类名>"));
    }

    @Test
    void classifiesStandaloneEscapeAsCancelIntent() {
        assertEquals(Main.EscapeSequenceType.STANDALONE_ESC, Main.classifyEscapeSequence(""));
    }

    @Test
    void classifiesArrowKeysAsControlSequences() {
        assertEquals(Main.EscapeSequenceType.CONTROL_SEQUENCE, Main.classifyEscapeSequence("[A"));
        assertEquals(Main.EscapeSequenceType.CONTROL_SEQUENCE, Main.classifyEscapeSequence("[B"));
        assertEquals(Main.EscapeSequenceType.CONTROL_SEQUENCE, Main.classifyEscapeSequence("OA"));
    }

    @Test
    void classifiesBracketedPasteSequenceSeparately() {
        assertEquals(Main.EscapeSequenceType.BRACKETED_PASTE, Main.classifyEscapeSequence("[200~hello"));
    }

    @Test
    void upArrowPrefillsLatestHistoryEntry() throws Exception {
        LineReader lineReader = newLineReader();
        History history = lineReader.getHistory();
        history.add("第一条");
        history.add("最近一条");

        assertEquals("最近一条", Main.seedBufferForHistoryNavigation(lineReader, "[A"));
    }

    @Test
    void downArrowKeepsPromptEmpty() throws Exception {
        LineReader lineReader = newLineReader();
        lineReader.getHistory().add("最近一条");

        assertEquals("", Main.seedBufferForHistoryNavigation(lineReader, "[B"));
    }

    @Test
    void decideEscCancelTriggersOnStandaloneEsc() {
        // 单 ESC（escTail 为空）→ 取消
        assertTrue(Main.decideEscCancel(27, ""));
        assertTrue(Main.decideEscCancel(27, null));
    }

    @Test
    void decideEscCancelIgnoresArrowKeyEscapeSequence() {
        // 上方向键 ESC[A → CONTROL_SEQUENCE，不取消
        assertFalse(Main.decideEscCancel(27, "[A"));
        // 下方向键
        assertFalse(Main.decideEscCancel(27, "[B"));
        // 应用模式方向键
        assertFalse(Main.decideEscCancel(27, "OA"));
    }

    @Test
    void decideEscCancelIgnoresBracketedPaste() {
        assertFalse(Main.decideEscCancel(27, "[200~hello"));
    }

    @Test
    void decideEscCancelIgnoresNonEscFirstByte() {
        // 普通字符不应触发
        assertFalse(Main.decideEscCancel((int) 'a', null));
        assertFalse(Main.decideEscCancel((int) '/', "cancel"));
        assertFalse(Main.decideEscCancel(0, null));
        assertFalse(Main.decideEscCancel(-1, null));
    }

    @Test
    void readEscCancelHandlesNullTerminalSafely() {
        assertFalse(Main.readEscCancel(null));
    }

    private static LineReader newLineReader() throws Exception {
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build();

        DefaultHistory history = new DefaultHistory();
        return LineReaderBuilder.builder()
                .terminal(terminal)
                .history(history)
                .build();
    }
}
