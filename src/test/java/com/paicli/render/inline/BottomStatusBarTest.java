package com.paicli.render.inline;

import com.paicli.render.StatusInfo;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BottomStatusBarTest {

    @Test
    void formatStatusLineIncludesAllFields() {
        StatusInfo info = new StatusInfo("glm-5.1", 1234L, 200_000L, true, 1500L);
        String line = BottomStatusBar.formatStatusLine(info, 80);
        assertTrue(line.contains("glm-5.1"), line);
        assertTrue(line.contains("1.2k/200.0k"), line);
        assertTrue(line.contains("HITL ON"), line);
        assertTrue(line.contains("1.5s"), line);
    }

    @Test
    void formatStatusLineDoesNotPadToColumnWidth() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 200_000L, false, 0L);
        String line = BottomStatusBar.formatStatusLine(info, 80);
        assertTrue(line.length() < 80, "status line should stay compact: " + line.length());
    }

    @Test
    void formatStatusLineTruncatesWhenLong() {
        StatusInfo info = new StatusInfo("very-long-model-name-exceeding-cols",
                999_999L, 200_000L, true, 0L);
        String line = BottomStatusBar.formatStatusLine(info, 30);
        assertTrue(line.length() == 30);
    }

    @Test
    void formatStatusLineHidesElapsedWhenZero() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 200_000L, false, 0L);
        String line = BottomStatusBar.formatStatusLine(info, 80);
        assertFalse(line.contains("ms"));
        assertFalse(line.contains("0s"));
    }

    @Test
    void formatStatusLineHandlesMillisecondElapsed() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 0L, false, 250L);
        String line = BottomStatusBar.formatStatusLine(info, 80);
        assertTrue(line.contains("250ms"), line);
    }

    @Test
    void closeBeforeStartIsSafe() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BottomStatusBar bar = new BottomStatusBar(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        bar.close();
        // 不应抛异常；不应输出 ANSI（因为 start 没调过）
        assertTrue(sink.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void startEmitsScrollRegion() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BottomStatusBar bar = new BottomStatusBar(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        bar.start();
        try {
            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("[1;22r"), "scroll region should reserve status and gap rows: " + emitted);
        } finally {
            bar.close();
        }
    }

    @Test
    void closeRestoresScrollRegion() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BottomStatusBar bar = new BottomStatusBar(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        bar.start();
        bar.close();
        String emitted = sink.toString(StandardCharsets.UTF_8);
        assertTrue(emitted.contains("[r"), "scroll region should be reset on close: " + emitted);
    }

    @Test
    void flushNowDrawsImmediately() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BottomStatusBar bar = new BottomStatusBar(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        bar.start();
        try {
            bar.update(StatusInfo.idle("glm-5.1", 200_000L, false));
            bar.flushNow();
            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("glm-5.1"), "should render model name: " + emitted);
            assertFalse(emitted.contains("[7m"), "should not use reverse video: " + emitted);
        } finally {
            bar.close();
        }
    }
}
