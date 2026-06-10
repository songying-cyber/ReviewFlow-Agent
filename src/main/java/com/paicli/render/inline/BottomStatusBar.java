package com.paicli.render.inline;

import com.paicli.render.StatusInfo;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.io.PrintStream;
import java.util.List;

/**
 * 跟随当前输入区的 inline 状态栏。
 *
 * <p>状态内容紧贴在输入行下面，而不是 dock 到物理终端底部。否则当输入行位于
 * transcript 当前位置时，输入行和底部 status 之间会留下整屏空白行。
 *
 * <p>保留类名是为了让 {@link InlineRenderer} 的边界稳定：外部仍然只看
 * start/update/close，不关心底层布局实现。
 */
public final class BottomStatusBar implements AutoCloseable {

    private static final int STATUS_ROWS = 2;
    private static final int STATUS_GAP_ROWS = 1;
    private static final int ROWS_AFTER_PROMPT = STATUS_GAP_ROWS + STATUS_ROWS;

    private final Terminal terminal;
    private final PrintStream out;
    private volatile StatusInfo current;
    private volatile boolean started;
    private volatile boolean closed;
    private volatile boolean rendered;

    public BottomStatusBar(Terminal terminal) {
        this.terminal = terminal;
        this.out = System.out;
    }

    /** 测试用构造器：注入输出流，避免污染真实 stdout。 */
    BottomStatusBar(Terminal terminal, PrintStream out) {
        this.terminal = terminal;
        this.out = out;
    }

    /** 初始化状态栏。重复调用无副作用。 */
    public synchronized void start() {
        if (started || closed) {
            return;
        }
        started = true;
    }

    public void update(StatusInfo info) {
        this.current = mergeEnvironment(info, current);
    }

    /** 当前 StatusInfo 快照，供 thinking 面板等组件复用同一份格式化结果。 */
    public StatusInfo currentStatus() {
        return current;
    }

    /** 立即触发一次重绘（不等节流间隔）。 */
    public void flushNow() {
        // Inline status is drawn as part of prepareInputLine(); there is no background throttle to flush.
    }

    /** 在即将读取输入时，把状态区画在 prompt 下方并把光标移回 prompt 行。 */
    public void prepareInputLine() {
        if (!started || closed) {
            return;
        }
        drawInlineStatus();
    }

    /** 输入提交后清掉 inline 状态区和它下面的空白，让下一段 transcript 紧跟输入行。 */
    public void finishInputLine() {
        if (!started || closed) {
            return;
        }
        clearInlineStatusAndGap();
    }

    private void drawInlineStatus() {
        StatusInfo info = current;
        if (info == null || closed || !started) {
            return;
        }
        int cols = TerminalCapabilities.safeSize(terminal).getColumns();
        synchronized (out) {
            out.print("\n".repeat(STATUS_GAP_ROWS + 1));
            out.print(AnsiSeq.REVERSE_ON);
            out.print(formatStatusLine(info, cols));
            out.print(AnsiSeq.RESET);
            out.print("\n");
            out.print(AnsiSeq.DIM);
            out.print(formatFooterLine(cols));
            out.print(AnsiSeq.RESET);
            out.print(AnsiSeq.moveUp(ROWS_AFTER_PROMPT));
            out.print("\r");
            out.flush();
            rendered = true;
        }
    }

    private void clearInlineStatusAndGap() {
        if (!rendered) {
            return;
        }
        synchronized (out) {
            out.print("\r");
            out.print(AnsiSeq.CLEAR_TO_EOS);
            out.flush();
            rendered = false;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        clearInlineStatusAndGap();
    }

    static String formatStatusLine(StatusInfo info, int cols) {
        String model = info.model() == null ? "—" : info.model();
        String phase = info.phase() == null || info.phase().isBlank() ? "idle" : info.phase();
        String tokens = formatTokens(info.totalTokens()) + "/" + formatTokens(info.contextWindow());
        String hitl = info.hitlEnabled() ? "HITL ON" : "HITL OFF";
        StringBuilder sb = new StringBuilder(" PaiCLI");
        appendField(sb, phase);
        appendField(sb, info.mcpSummary());
        appendField(sb, info.skillSummary());
        appendField(sb, hitl);
        appendField(sb, model);
        appendField(sb, "ctx " + tokens);
        if (info.inputTokens() > 0 || info.outputTokens() > 0 || info.cachedInputTokens() > 0) {
            appendField(sb, "in " + formatTokens(info.inputTokens()) + " out " + formatTokens(info.outputTokens()));
            if (info.cachedInputTokens() > 0) {
                sb.append(" cache ").append(formatTokens(info.cachedInputTokens()));
            }
            if (info.estimatedCost() != null && !info.estimatedCost().isBlank()) {
                sb.append("  ").append(info.estimatedCost());
            }
        }
        if (info.elapsedMillis() > 0) {
            sb.append("  ").append(formatElapsed(info.elapsedMillis()));
        }
        return fitToColumns(sb.toString(), cols);
    }

    private static void appendField(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("  ").append(value.trim());
    }

    private static StatusInfo mergeEnvironment(StatusInfo next, StatusInfo previous) {
        if (next == null || previous == null) {
            return next;
        }
        String mcp = next.mcpSummary() == null || next.mcpSummary().isBlank()
                ? previous.mcpSummary()
                : next.mcpSummary();
        String skill = next.skillSummary() == null || next.skillSummary().isBlank()
                ? previous.skillSummary()
                : next.skillSummary();
        if (mcp == next.mcpSummary() && skill == next.skillSummary()) {
            return next;
        }
        return next.withEnvironment(mcp, skill);
    }

    static String formatFooterLine(int cols) {
        return fitToColumns(" Auto Model · / commands · @path/@image · Ctrl+O fold · ESC clear", cols);
    }

    static List<AttributedString> formatStatusLines(StatusInfo info, int cols) {
        return List.of(
                new AttributedString(formatStatusLine(info, cols), AttributedStyle.DEFAULT.inverse()),
                new AttributedString(formatFooterLine(cols), AttributedStyle.DEFAULT.faint())
        );
    }

    private static String fitToColumns(String text, int cols) {
        if (cols <= 0) {
            return "";
        }
        String safe = text == null ? "" : text;
        if (safe.length() > cols) {
            return safe.substring(0, cols);
        }
        return safe + " ".repeat(cols - safe.length());
    }

    private static String formatTokens(long t) {
        if (t >= 1_000_000) {
            return String.format("%.1fM", t / 1_000_000.0);
        }
        if (t >= 1_000) {
            return String.format("%.1fk", t / 1_000.0);
        }
        return String.valueOf(t);
    }

    private static String formatElapsed(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        return String.format("%.1fs", ms / 1000.0);
    }
}
