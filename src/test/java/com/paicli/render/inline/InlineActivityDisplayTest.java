package com.paicli.render.inline;

import com.paicli.render.StatusInfo;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineActivityDisplayTest {

    @Test
    void thinkingPanelKeepsStatusOutOfLiveArea() throws Exception {
        ByteArrayOutputStream terminalSink = new ByteArrayOutputStream();
        Terminal terminal = mockAnsiTerminal(terminalSink);
        BottomStatusBar statusBar = new BottomStatusBar(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        statusBar.start();
        statusBar.update(StatusInfo.tokens("glm-5.1", 200_000L, 1234L, 567L, 0L,
                null, false, 3200L, "thinking"));

        try (InlineActivityDisplay display = new InlineActivityDisplay(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                statusBar)) {
            display.begin("Thinking");
            display.appendThinking("trying to read file");
            terminal.writer().flush();
        }

        String output = terminalSink.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("PaiCLI"), "thinking panel should not duplicate status bar: " + output);
        assertFalse(output.contains("glm-5.1"), "thinking panel should not duplicate model status: " + output);
        assertFalse(output.contains("Auto Model"), "thinking panel should not duplicate footer cue: " + output);
        assertTrue(output.contains("Thinking"), "thinking panel should keep the spinner label: " + output);
        assertTrue(output.contains("Thinking... (esc to cancel,"),
                "thinking panel should keep a stable label and move the animation to the spinner: " + output);
        assertTrue(output.contains("| trying to read file") || output.contains("│ trying to read file"),
                "thinking panel should show quoted reasoning: " + output);
    }

    @Test
    void thinkingPanelOmitsStatusHeaderWhenStatusBarMissing() throws Exception {
        ByteArrayOutputStream terminalSink = new ByteArrayOutputStream();
        Terminal terminal = mockAnsiTerminal(terminalSink);

        try (InlineActivityDisplay display = new InlineActivityDisplay(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))) {
            display.begin("Thinking");
            terminal.writer().flush();
        }

        String output = terminalSink.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("PaiCLI"),
                "without a status bar the activity panel should not invent a status row: " + output);
        assertTrue(output.contains("Thinking"), output);
    }

    @Test
    void refreshIfActiveIsNoOpWhenIdle() throws Exception {
        ByteArrayOutputStream terminalSink = new ByteArrayOutputStream();
        Terminal terminal = mockAnsiTerminal(terminalSink);
        BottomStatusBar statusBar = new BottomStatusBar(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        statusBar.start();
        statusBar.update(StatusInfo.idle("glm-5.1", 200_000L, false));

        try (InlineActivityDisplay display = new InlineActivityDisplay(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                statusBar)) {
            // no begin() called, panel is idle
            display.refreshIfActive();
            terminal.writer().flush();
        }
        String output = terminalSink.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("PaiCLI"),
                "idle activity display must not paint when status updates: " + output);
    }

    private static Terminal mockAnsiTerminal(ByteArrayOutputStream sink) {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        Mockito.when(terminal.getWidth()).thenReturn(120);
        Mockito.when(terminal.getHeight()).thenReturn(40);
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.when(terminal.encoding()).thenReturn(StandardCharsets.UTF_8);
        return terminal;
    }
}
