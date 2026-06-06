package com.paicli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Display;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * JLine-managed transient activity area for model thinking.
 *
 * <p>The timer only advances the spinner clock. All terminal repaint work goes
 * through {@link Display}, so JLine owns cursor movement, diffing, wrapping and
 * terminal flush semantics instead of this class writing raw carriage-return
 * control sequences.
 */
final class InlineActivityDisplay implements AutoCloseable {

    private static final int MAX_REASONING_CHARS = 4096;
    private static final int MAX_REASONING_ROWS = 4;
    private static final AttributedStyle STATUS_STYLE = AttributedStyle.DEFAULT.faint().italic();
    private static final AttributedStyle QUOTE_STYLE = AttributedStyle.DEFAULT.faint().italic();

    private final Terminal terminal;
    private final PrintStream renderLock;
    private final Display display;
    private final ScheduledExecutorService scheduler;
    private final StringBuilder reasoning = new StringBuilder();

    private ScheduledFuture<?> tickTask;
    private boolean active;
    private boolean closed;
    private String label = "Thinking";
    private long startedNanos;
    private int frame;

    InlineActivityDisplay(Terminal terminal, PrintStream renderLock) {
        this.terminal = terminal;
        this.renderLock = renderLock;
        this.display = new Display(terminal, false);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "paicli-activity-display");
            t.setDaemon(true);
            return t;
        });
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
            try {
                display.resize(TerminalCapabilities.safeSize(terminal).getColumns(),
                        TerminalCapabilities.safeSize(terminal).getRows());
            } catch (RuntimeException ignored) {
                // Some mocked or redirected terminals do not expose buffer size.
                // Display can still repaint with the size captured at construction.
            }
            display.update(buildLines(), -1);
        }
    }

    private void clearLocked() {
        synchronized (renderLock) {
            display.update(List.of(), -1);
            display.reset();
        }
    }

    private List<AttributedString> buildLines() {
        int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns());
        List<AttributedString> lines = new ArrayList<>();
        lines.add(fit(": " + label + dots() + " (ESC 取消, " + elapsedSeconds() + "s)",
                cols, STATUS_STYLE));

        List<String> quoteLines = reasoningLines();
        int quoteWidth = Math.max(12, cols - 4);
        int start = Math.max(0, quoteLines.size() - MAX_REASONING_ROWS);
        for (int i = start; i < quoteLines.size(); i++) {
            AttributedString quote = new AttributedString("> " + quoteLines.get(i), QUOTE_STYLE);
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

    private String dots() {
        return switch (frame % 4) {
            case 0 -> ".";
            case 1 -> "..";
            case 2 -> "...";
            default -> "....";
        };
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
