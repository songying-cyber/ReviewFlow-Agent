package com.paicli.cli;

import com.paicli.llm.LlmClient;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
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

        assertTrue(hints.stream().anyMatch(hint -> hint.contains("输入 '/' 后按 Tab 补全命令")));
        assertTrue(hints.stream().noneMatch(hint -> hint.contains("/model")));
        assertTrue(hints.stream().noneMatch(hint -> hint.contains("/index [路径]")));
        assertTrue(hints.stream().noneMatch(hint -> hint.contains("/skill list")));
    }

    @Test
    void startupBannerUsesOpenLayoutWithoutRightBorder() {
        List<String> lines = Main.startupBannerLines();

        assertTrue(lines.stream().anyMatch(line -> line.contains("PaiCLI")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("π")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("v16.1.0")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("████████")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Tips for getting started")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("@path")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("for shortcuts")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("────────────────")));
        assertTrue(lines.stream().noneMatch(line -> line.endsWith("║")),
                "banner should not depend on a padded right border");
    }

    @Test
    void slashCommandTailTipsExposeCommandDescriptions() {
        var tips = Main.slashCommandTailTips();

        assertTrue(tips.containsKey("/model"));
        assertTrue(tips.get("/model").getMainDesc().get(0).toString().contains("查看当前模型"));
        assertTrue(tips.containsKey("/plan <任务内容>"));
    }

    @Test
    void promptDoesNotUseBottomSpaciousModeByDefault() {
        assertFalse(Main.defaultSpaciousPrompt(false));
        assertFalse(Main.defaultSpaciousPrompt(true));
    }

    @Test
    void mcpStartupWaitCanBeTunedForTerminalSmoke() {
        String old = System.getProperty("paicli.mcp.startup.wait.seconds");
        try {
            System.setProperty("paicli.mcp.startup.wait.seconds", "2");

            assertEquals(Duration.ofSeconds(2), Main.mcpStartupWait());
        } finally {
            restoreProperty("paicli.mcp.startup.wait.seconds", old);
        }
    }

    @Test
    void submittedPromptIsRenderedBackIntoTranscript() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        Main.printSubmittedPrompt(new PrintStream(sink, true, StandardCharsets.UTF_8), "  沉默王二是谁？  ");

        String emitted = sink.toString(StandardCharsets.UTF_8);
        assertTrue(emitted.contains(">"), emitted);
        assertTrue(emitted.contains("沉默王二是谁？"), emitted);
        assertTrue(emitted.endsWith("\n"), emitted);
        assertFalse(emitted.endsWith("\n\n"), emitted);
    }

    @Test
    void submittedSingleLinePromptDoesNotAddExtraBlankLine() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        Main.printSubmittedPrompt(new PrintStream(sink, true, StandardCharsets.UTF_8), "你好啊");

        String emitted = sink.toString(StandardCharsets.UTF_8);
        assertEquals(1, emitted.chars().filter(ch -> ch == '\n').count(), emitted);
        assertTrue(emitted.contains(">"), emitted);
        assertTrue(emitted.contains("你好啊"), emitted);
    }

    @Test
    void submittedSlashCommandIsRenderedBackIntoTranscript() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        Main.printSubmittedInput(null, new PrintStream(sink, true, StandardCharsets.UTF_8), "/memory list");

        String emitted = sink.toString(StandardCharsets.UTF_8);
        assertTrue(emitted.contains(">"), emitted);
        assertTrue(emitted.contains("/memory list"), emitted);
        assertEquals(1, emitted.chars().filter(ch -> ch == '\n').count(), emitted);
    }

    @Test
    void configuresAwtHeadlessOnMac() {
        String oldOs = System.getProperty("os.name");
        String oldHeadless = System.getProperty("java.awt.headless");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.clearProperty("java.awt.headless");

            Main.configureAwtForCli();

            assertEquals("true", System.getProperty("java.awt.headless"));
        } finally {
            restoreProperty("os.name", oldOs);
            restoreProperty("java.awt.headless", oldHeadless);
        }
    }

    @Test
    void doesNotForceAwtHeadlessOnNonMac() {
        String oldOs = System.getProperty("os.name");
        String oldHeadless = System.getProperty("java.awt.headless");
        try {
            System.setProperty("os.name", "Linux");
            System.clearProperty("java.awt.headless");

            Main.configureAwtForCli();

            assertFalse(System.getProperties().containsKey("java.awt.headless"));
        } finally {
            restoreProperty("os.name", oldOs);
            restoreProperty("java.awt.headless", oldHeadless);
        }
    }

    @Test
    void clearsCurrentInputBufferForEscWidget() throws Exception {
        LineReader lineReader = newLineReader();
        lineReader.getBuffer().write("@image:</tmp/shot.png> 这张图呢");

        Main.clearInputBuffer(lineReader);

        assertEquals("", lineReader.getBuffer().toString());
    }

    @Test
    void slashCommandHintsIncludeRagSlashCommands() {
        List<String> commands = Main.slashCommandHints().stream()
                .map(Main.SlashCommandHint::display)
                .toList();

        assertTrue(commands.contains("/index [路径]"));
        assertTrue(commands.contains("/search <查询>"));
        assertTrue(commands.contains("/graph <类名>"));
        assertTrue(commands.contains("/compact"));
    }

    @Test
    void slashCommandChoicesAreRenderedDirectlyWithoutJLineConfirmationText() {
        String choices = Main.formatSlashCommandChoices(120);

        assertTrue(choices.contains("/model glm-5.1"), choices);
        assertTrue(choices.contains("/model glm-5v-turbo"), choices);
        assertTrue(choices.contains("/model step"), choices);
        assertTrue(choices.contains("/model kimi"), choices);
        assertTrue(choices.contains("/model freellmapi"), choices);
        assertTrue(choices.contains("/model xfyun"), choices);
        assertTrue(choices.contains("/browser status"), choices);
        assertFalse(choices.contains("do you wish"), choices);
        assertTrue(choices.lines().count() < Main.slashCommandHints().size(),
                "choices should be compact multi-column output");
    }

    @Test
    void exportIncludesSystemOnlyHistoryForPromptInspection() {
        List<LlmClient.Message> history = List.of(LlmClient.Message.system("system prompt"));

        assertTrue(Main.hasExportableMessages(history));
        assertEquals(1, Main.countExportedMessages(history));

        String markdown = Main.renderConversationExport(history, LocalDateTime.of(2026, 6, 10, 14, 17));

        assertTrue(markdown.contains("## System"), markdown);
        assertTrue(markdown.contains("system prompt"), markdown);
        assertFalse(markdown.contains("System prompt 已省略"), markdown);
    }

    @Test
    void rendersConversationExportWithSafeCodeFences() {
        List<LlmClient.Message> history = List.of(
                LlmClient.Message.system("system prompt"),
                LlmClient.Message.user("请读取代码"),
                LlmClient.Message.assistant(
                        "模型思考",
                        "准备调用工具",
                        List.of(new LlmClient.ToolCall("call_1",
                                new LlmClient.ToolCall.Function("write_file",
                                        "{\"path\":\"demo.md\",\"content\":\"```java\\nclass A {}\\n```\"}")))),
                LlmClient.Message.tool("call_1", "工具结果里也有围栏:\n```java\nclass B {}\n```")
        );

        String markdown = Main.renderConversationExport(history, LocalDateTime.of(2026, 6, 10, 14, 30));

        assertTrue(markdown.contains("**导出时间**: 2026-06-10 14:30"), markdown);
        assertTrue(markdown.contains("## System\n\nsystem prompt"), markdown);
        assertFalse(markdown.contains("System prompt 已省略"), markdown);
        assertTrue(markdown.contains("请读取代码"), markdown);
        assertTrue(markdown.contains("> **思考过程**"), markdown);
        assertTrue(markdown.contains("````json"), markdown);
        assertTrue(markdown.contains("\"content\" : \"```java\\nclass A {}\\n```\""), markdown);
        assertTrue(markdown.contains("````\n工具结果里也有围栏:"), markdown);
        assertTrue(markdown.contains("```java\nclass B {}\n```"), markdown);
        assertTrue(markdown.contains("\n````\n"), markdown);
        assertEquals(4, Main.countExportedMessages(history));
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

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
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
