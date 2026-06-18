package com.paicli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fixed-height transient activity area for model thinking.
 *
 * <p>The live area only ever clears and rewrites rows that it printed itself.
 * It intentionally avoids {@code Display.update(...)} because an independent
 * JLine Display does not share layout ownership with the transcript and status
 * renderer; once scrollback moves, Display can clear from the wrong origin.
 */
final class InlineActivityDisplay implements AutoCloseable {

    private static final int MAX_REASONING_CHARS = 4096;
    private static final int MAX_REASONING_ROWS = 4;
    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final AttributedStyle STATUS_STYLE = AttributedStyle.DEFAULT.italic();
    private static final AttributedStyle QUOTE_STYLE = AttributedStyle.DEFAULT.faint().italic();

    private final Terminal terminal;
    private final PrintStream renderLock;
    private final ScheduledExecutorService scheduler;
    private final StringBuilder reasoning = new StringBuilder();
    private ScheduledFuture<?> tickTask;
    private boolean active;
    private boolean closed;
    private String label = "Thinking";
    private long startedNanos;
    private int frame;
    private int renderedRows;

    InlineActivityDisplay(Terminal terminal, PrintStream renderLock) {
        this(terminal, renderLock, null);
    }

    InlineActivityDisplay(Terminal terminal, PrintStream renderLock, BottomStatusBar statusBar) {
        this.terminal = terminal;
        this.renderLock = renderLock;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "paicli-activity-display");
            t.setDaemon(true);
            return t;
        });
    }

    /** thinking 面板是否正在显示——给 InlineRenderer 决定是否在 status 更新时触发重绘。 */
    synchronized boolean isActive() {
        return active && !closed;
    }

    /** 当 renderer 状态变化时，如果 thinking 正在显示，则刷新 spinner 和 reasoning 预览。 */
    synchronized void refreshIfActive() {
        if (active && !closed) {
            renderLocked();
        }
    }

    synchronized void begin(String label) {
        if (closed) {
            return;
        }
        clearLocked();
        reasoning.setLength(0);
        this.label = (label == null || label.isBlank()) ? "Thinking" : label.trim();
        this.startedNanos = System.nanoTime();
        this.frame = 0;
        this.active = true;
        renderLocked();
        restartTickLocked();
    }

    synchronized void appendThinking(String delta) {
        if (closed || delta == null || delta.isEmpty()) {
            return;
        }
        if (!active) {
            this.label = "Thinking";
            this.startedNanos = System.nanoTime();
            this.frame = 0;
            this.active = true;
            restartTickLocked();
        }
        reasoning.append(delta);
        trimReasoning();
        renderLocked();
    }

    synchronized void end() {
        if (closed) {
            return;
        }
        active = false;
        cancelTickLocked();
        reasoning.setLength(0);
        clearLocked();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        active = false;
        cancelTickLocked();
        reasoning.setLength(0);
        clearLocked();
        scheduler.shutdownNow();
    }

    private void restartTickLocked() {
        cancelTickLocked();
        tickTask = scheduler.scheduleAtFixedRate(this::tick, 250, 250, TimeUnit.MILLISECONDS);
    }

    private void cancelTickLocked() {
        if (tickTask != null) {
            tickTask.cancel(false);
            tickTask = null;
        }
    }

    private void tick() {
        synchronized (this) {
            if (!active || closed) {
                return;
            }
            frame++;
            renderLocked();
        }
    }

    private void renderLocked() {
        if (!active || closed) {
            return;
        }
        synchronized (renderLock) {
            PrintWriter writer = terminalWriter();
            clearRenderedArea(writer);
            List<AttributedString> lines = buildLines();
            for (int i = 0; i < lines.size(); i++) {
                writer.print(lines.get(i).toAnsi(terminal));
                writer.print(AnsiSeq.CLEAR_TO_EOL);
                if (i < lines.size() - 1) {
                    writer.print('\n');
                }
            }
            renderedRows = lines.size();
            writer.flush();
            terminal.flush();
        }
    }

    private void clearLocked() {
        synchronized (renderLock) {
            PrintWriter writer = terminalWriter();
            clearRenderedArea(writer);
            writer.flush();
            terminal.flush();
        }
    }

    private PrintWriter terminalWriter() {
        PrintWriter writer = terminal.writer();
        if (writer != null) {
            return writer;
        }
        return new PrintWriter(renderLock, true, StandardCharsets.UTF_8);
    }

    private void clearRenderedArea(PrintWriter writer) {
        if (renderedRows <= 0) {
            return;
        }
        if (renderedRows > 1) {
            writer.print(AnsiSeq.moveUp(renderedRows - 1));
        }
        writer.print('\r');
        for (int i = 0; i < renderedRows; i++) {
            writer.print(AnsiSeq.CLEAR_LINE);
            if (i < renderedRows - 1) {
                writer.print('\n');
            }
        }
        if (renderedRows > 1) {
            writer.print(AnsiSeq.moveUp(renderedRows - 1));
        }
        writer.print('\r');
        renderedRows = 0;
    }

    private List<AttributedString> buildLines() {
        int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns() - 1);
        List<AttributedString> lines = new ArrayList<>();
        lines.add(fit("  " + spinner() + " " + label + "... (esc to cancel, " + elapsedSeconds() + "s)",
                cols, STATUS_STYLE));

        List<String> quoteLines = reasoningLines();
        int quoteWidth = Math.max(12, cols - 4);
        int start = Math.max(0, quoteLines.size() - MAX_REASONING_ROWS);
        for (int i = start; i < quoteLines.size(); i++) {
            AttributedString quote = new AttributedString("│ " + quoteLines.get(i), QUOTE_STYLE);
            for (AttributedString part : quote.columnSplitLength(quoteWidth, true, true, terminal)) {
                lines.add(fit("  " + part.toString(), cols, QUOTE_STYLE));
                if (lines.size() > MAX_REASONING_ROWS + 1) {
                    return lines;
                }
            }
        }
        return lines;
    }

    private List<String> reasoningLines() {
        String content = reasoning.toString()
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (content.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : content.split("\\R+")) {
            String normalized = line.replaceAll("\\s+", " ").trim();
            if (!normalized.isEmpty()) {
                lines.add(normalized);
            }
        }
        return lines;
    }

    private AttributedString fit(String text, int cols, AttributedStyle style) {
        AttributedString attributed = new AttributedString(text == null ? "" : text, style);
        if (attributed.columnLength(terminal) <= cols) {
            return attributed;
        }
        if (cols <= 3) {
            return new AttributedString(".".repeat(Math.max(0, cols)), style);
        }
        return new AttributedString(
                attributed.columnSubSequence(0, cols - 3, terminal).toString() + "...",
                style);
    }

    private String spinner() {
        return SPINNER_FRAMES[Math.floorMod(frame, SPINNER_FRAMES.length)];
    }

    private long elapsedSeconds() {
        return Math.max(0, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos));
    }

    private void trimReasoning() {
        if (reasoning.length() <= MAX_REASONING_CHARS) {
            return;
        }
        reasoning.delete(0, reasoning.length() - MAX_REASONING_CHARS);
    }
}
