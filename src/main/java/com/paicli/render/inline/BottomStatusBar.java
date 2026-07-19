package com.paicli.render.inline;

import com.paicli.render.StatusInfo;
import com.paicli.util.AnsiStyle;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JLine 托管的底部 dock。
 *
 * <p>只通过 {@link Status} 更新底部保留区，不再手写换行、绝对光标行号或
 * {@code CLEAR_TO_EOS}。正文输出、thinking activity 和 LineReader 输入区都交给
 * JLine 共同协调，避免多个组件争抢同一块物理终端区域。
 *
 * <p>保留类名是为了让 {@link InlineRenderer} 的边界稳定：外部仍然只看
 * start/update/close，不关心底层布局实现。
 */
public final class BottomStatusBar implements AutoCloseable {

    private static final int CONTEXT_BAR_WIDTH = 8;
    private static final Pattern SUMMARY_RATIO = Pattern.compile("(?i)^(?:MCP|Skill)\\s+(\\d+)/(\\d+)$");
    private static final AttributedStyle BASE_STYLE = style(AttributedStyle.DEFAULT.faint());
    private static final AttributedStyle MODE_YOLO_STYLE = style(AttributedStyle.DEFAULT
            .foreground(AttributedStyle.YELLOW)
            .bold());
    private static final AttributedStyle MODE_HITL_STYLE = style(AttributedStyle.DEFAULT
            .foreground(AttributedStyle.GREEN)
            .bold());
    private static final AttributedStyle MCP_STYLE = style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
    private static final AttributedStyle SKILL_STYLE = style(AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA));
    private static final AttributedStyle BRAND_STYLE = style(AttributedStyle.DEFAULT
            .foreground(AttributedStyle.MAGENTA)
            .bold());
    private static final AttributedStyle MODEL_STYLE = style(AttributedStyle.DEFAULT
            .foreground(AttributedStyle.CYAN)
            .bold());
    private static final AttributedStyle PHASE_IDLE_STYLE = style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN));
    private static final AttributedStyle PHASE_ACTIVE_STYLE = style(AttributedStyle.DEFAULT
            .foreground(AttributedStyle.YELLOW)
            .bold());
    private static final AttributedStyle CTX_LABEL_STYLE = style(AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BLUE)
            .bold());
    private static final AttributedStyle CTX_FILL_STYLE = style(AttributedStyle.DEFAULT
            .foreground(AttributedStyle.GREEN)
            .bold());
    private static final AttributedStyle CTX_EMPTY_STYLE = style(AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BLUE)
            .faint());
    private static final AttributedStyle TOKEN_LABEL_STYLE = style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
    private static final AttributedStyle CACHE_LABEL_STYLE = style(AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA));
    private static final AttributedStyle ELAPSED_STYLE = style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
    private static final AttributedStyle CWD_STYLE = style(AttributedStyle.DEFAULT.faint());

    private final Terminal terminal;
    private final PrintStream out;
    private volatile StatusInfo current;
    private Status status;
    private volatile boolean started;
    private volatile boolean closed;

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
        status = Status.getStatus(terminal);
        if (status != null) {
            status.setBorder(true);
        }
        started = true;
        renderDock();
    }

    public void update(StatusInfo info) {
        this.current = mergeEnvironment(info, current);
        renderDock();
    }

    /** 当前 StatusInfo 快照，供 thinking 面板等组件复用同一份格式化结果。 */
    public StatusInfo currentStatus() {
        return current;
    }

    /** 立即触发一次重绘（不等节流间隔）。 */
    public void flushNow() {
        renderDock();
    }

    /** 在即将读取输入时刷新 JLine dock；光标和输入行位置由 LineReader 管理。 */
    public void prepareInputLine() {
        renderDock();
        moveCursorToDockInputRow();
    }

    /** 输入提交后保留底部 dock；正文继续在 JLine 保留区上方滚动。 */
    public void finishInputLine() {
        renderDock();
    }

    private void renderDock() {
        StatusInfo info = current;
        Status dock = status;
        if (info == null || dock == null || closed || !started) {
            return;
        }
        int cols = TerminalCapabilities.safeSize(terminal).getColumns();
        synchronized (out) {
            dock.update(formatStatusLines(info, cols));
        }
    }

    private void moveCursorToDockInputRow() {
        StatusInfo info = current;
        if (info == null || closed || !started) {
            return;
        }
        int rows = TerminalCapabilities.safeSize(terminal).getRows();
        int cols = TerminalCapabilities.safeSize(terminal).getColumns();
        int dockRows = formatStatusLines(info, cols).size() + 1; // JLine Status border.
        int inputRow = inputDockRow(rows, dockRows);
        synchronized (out) {
            terminal.puts(InfoCmp.Capability.cursor_address, inputRow, 0);
            terminal.flush();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        Status dock = status;
        status = null;
        if (dock != null) {
            dock.close();
        }
    }

    static String formatStatusLine(StatusInfo info, int cols) {
        String mode = info.hitlEnabled() ? "HITL Ctrl+Y for YOLO" : "YOLO Ctrl+Y to enable HITL";
        String right = environmentSummary(info);
        if (right.isBlank()) {
            return fitToColumns(" " + mode, cols);
        }
        int gap = Math.max(1, cols - visibleLength(mode) - visibleLength(right) - 2);
        return fitToColumns(" " + mode + " ".repeat(gap) + right + " ", cols);
    }

    static String formatFooterLine(StatusInfo info, int cols) {
        String model = info.model() == null || info.model().isBlank() ? "Auto Model" : info.model().trim();
        String phase = info.phase() == null || info.phase().isBlank() ? "idle" : info.phase().trim();
        StringBuilder sb = new StringBuilder(" Auto Model · ");
        sb.append(model);
        appendField(sb, phase);
        appendField(sb, contextSegment(info));
        if (info.inputTokens() > 0 || info.outputTokens() > 0 || info.cachedInputTokens() > 0) {
            appendField(sb, "in " + formatTokens(info.inputTokens()) + " out " + formatTokens(info.outputTokens()));
            if (info.cachedInputTokens() > 0) {
                sb.append(" cache ").append(formatTokens(info.cachedInputTokens()));
            }
            if (info.estimatedCost() != null && !info.estimatedCost().isBlank()) {
                sb.append(" · ").append(info.estimatedCost().trim());
            }
        }
        if (info.elapsedMillis() > 0) {
            appendField(sb, formatElapsed(info.elapsedMillis()));
        }
        appendField(sb, compactCwd());
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

    static List<AttributedString> formatStatusLines(StatusInfo info, int cols) {
        return List.of(
                formatStatusLineAttributed(info, cols),
                formatFooterLineAttributed(info, cols)
        );
    }

    static AttributedString formatStatusLineAttributed(StatusInfo info, int cols) {
        String mode = info.hitlEnabled() ? "HITL Ctrl+Y for YOLO" : "YOLO Ctrl+Y to enable HITL";
        String right = environmentSummary(info);
        AttributedStringBuilder builder = new AttributedStringBuilder(Math.max(0, cols));
        builder.append(" ", BASE_STYLE);
        builder.append(mode, info.hitlEnabled() ? MODE_HITL_STYLE : MODE_YOLO_STYLE);
        if (!right.isBlank()) {
            int gap = Math.max(1, cols - visibleLength(mode) - visibleLength(right) - 2);
            builder.append(" ".repeat(gap), BASE_STYLE);
            appendEnvironmentSummaryStyled(builder, info);
            builder.append(" ", BASE_STYLE);
        }
        return fitToColumns(builder.toAttributedString(), cols);
    }

    static AttributedString formatFooterLineAttributed(StatusInfo info, int cols) {
        String model = info.model() == null || info.model().isBlank() ? "Auto Model" : info.model().trim();
        String phase = info.phase() == null || info.phase().isBlank() ? "idle" : info.phase().trim();
        AttributedStringBuilder builder = new AttributedStringBuilder(Math.max(0, cols));
        builder.append(" ", BASE_STYLE);
        builder.append("Auto Model", BRAND_STYLE);
        builder.append(" · ", BASE_STYLE);
        builder.append(model, MODEL_STYLE);
        appendStyledField(builder, phase, "idle".equalsIgnoreCase(phase) ? PHASE_IDLE_STYLE : PHASE_ACTIVE_STYLE);
        appendContextField(builder, info);
        if (info.inputTokens() > 0 || info.outputTokens() > 0 || info.cachedInputTokens() > 0) {
            appendUsageField(builder, info);
            if (info.estimatedCost() != null && !info.estimatedCost().isBlank()) {
                builder.append(" · ", BASE_STYLE);
                builder.append(info.estimatedCost().trim(), TOKEN_LABEL_STYLE);
            }
        }
        if (info.elapsedMillis() > 0) {
            appendStyledField(builder, formatElapsed(info.elapsedMillis()), ELAPSED_STYLE);
        }
        appendStyledField(builder, compactCwd(), CWD_STYLE);
        return fitToColumns(builder.toAttributedString(), cols);
    }

    static int inputDockRow(int terminalRows, int dockRows) {
        return Math.max(0, terminalRows - Math.max(0, dockRows) - 1);
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

    private static String environmentSummary(StatusInfo info) {
        String mcp = formatEnvironment(info.mcpSummary(), "MCP server", "MCP servers");
        String skill = formatEnvironment(info.skillSummary(), "skill", "skills");
        if (mcp.isBlank()) {
            return skill;
        }
        if (skill.isBlank()) {
            return mcp;
        }
        return mcp + " · " + skill;
    }

    private static String formatEnvironment(String raw, String singular, String plural) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim();
        Matcher matcher = SUMMARY_RATIO.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        int active = Integer.parseInt(matcher.group(1));
        int total = Integer.parseInt(matcher.group(2));
        if (active == total) {
            return total + " " + (total == 1 ? singular : plural);
        }
        return active + "/" + total + " " + plural;
    }

    private static void appendEnvironmentSummaryStyled(AttributedStringBuilder builder, StatusInfo info) {
        String mcp = formatEnvironment(info.mcpSummary(), "MCP server", "MCP servers");
        String skill = formatEnvironment(info.skillSummary(), "skill", "skills");
        if (!mcp.isBlank()) {
            builder.append(mcp, MCP_STYLE);
        }
        if (!mcp.isBlank() && !skill.isBlank()) {
            builder.append(" · ", BASE_STYLE);
        }
        if (!skill.isBlank()) {
            builder.append(skill, SKILL_STYLE);
        }
    }

    private static void appendStyledField(AttributedStringBuilder builder, String value, AttributedStyle style) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append("  ", BASE_STYLE);
        builder.append(value.trim(), style);
    }

    private static void appendContextField(AttributedStringBuilder builder, StatusInfo info) {
        ContextGauge gauge = contextGauge(info);
        builder.append("  ", BASE_STYLE);
        builder.append("ctx", CTX_LABEL_STYLE);
        builder.append(" ", BASE_STYLE);
        if (gauge.filled() > 0) {
            builder.append("█".repeat(gauge.filled()), CTX_FILL_STYLE);
        }
        if (gauge.empty() > 0) {
            builder.append("░".repeat(gauge.empty()), CTX_EMPTY_STYLE);
        }
        builder.append(" ", BASE_STYLE);
        builder.append(gauge.percent() + "%", contextPercentStyle(gauge.percent()));
        builder.append(" (" + formatTokens(gauge.total()) + "/" + formatTokens(gauge.window()) + ")", BASE_STYLE);
    }

    private static void appendUsageField(AttributedStringBuilder builder, StatusInfo info) {
        builder.append("  ", BASE_STYLE);
        builder.append("in", TOKEN_LABEL_STYLE);
        builder.append(" " + formatTokens(info.inputTokens()) + " ", BASE_STYLE);
        builder.append("out", TOKEN_LABEL_STYLE);
        builder.append(" " + formatTokens(info.outputTokens()), BASE_STYLE);
        if (info.cachedInputTokens() > 0) {
            builder.append(" ", BASE_STYLE);
            builder.append("cache", CACHE_LABEL_STYLE);
            builder.append(" " + formatTokens(info.cachedInputTokens()), BASE_STYLE);
        }
    }

    private static AttributedStyle contextPercentStyle(int percent) {
        if (percent >= 90) {
            return style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold());
        }
        if (percent >= 70) {
            return style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold());
        }
        return style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold());
    }

    private static AttributedStyle style(AttributedStyle style) {
        return AnsiStyle.isEnabled() ? style : AttributedStyle.DEFAULT;
    }

    private static AttributedString fitToColumns(AttributedString text, int cols) {
        if (cols <= 0) {
            return new AttributedString("");
        }
        AttributedString safe = text == null ? new AttributedString("") : text;
        int length = safe.columnLength();
        if (length > cols) {
            return safe.columnSubSequence(0, cols);
        }
        if (length == cols) {
            return safe;
        }
        AttributedStringBuilder builder = new AttributedStringBuilder(cols);
        builder.append(safe);
        builder.append(" ".repeat(cols - length), BASE_STYLE);
        return builder.toAttributedString();
    }

    private static String contextSegment(StatusInfo info) {
        ContextGauge gauge = contextGauge(info);
        String bar = "█".repeat(Math.max(0, gauge.filled()))
                + "░".repeat(Math.max(0, gauge.empty()));
        return "ctx " + bar + " " + gauge.percent() + "% ("
                + formatTokens(gauge.total()) + "/" + formatTokens(gauge.window()) + ")";
    }

    private static ContextGauge contextGauge(StatusInfo info) {
        long total = Math.max(0L, info.totalTokens());
        long window = Math.max(0L, info.contextWindow());
        int percent = window <= 0L ? 0 : (int) Math.min(100L, Math.round(total * 100.0 / window));
        int filled = window <= 0L ? 0 : (int) Math.min(CONTEXT_BAR_WIDTH,
                Math.round(total * CONTEXT_BAR_WIDTH * 1.0 / window));
        int empty = Math.max(0, CONTEXT_BAR_WIDTH - filled);
        return new ContextGauge(total, window, percent, filled, empty);
    }

    private record ContextGauge(long total, long window, int percent, int filled, int empty) {
    }

    private static String compactCwd() {
        String cwd = System.getProperty("user.dir");
        if (cwd == null || cwd.isBlank()) {
            return "";
        }
        String normalized = Path.of(cwd).toAbsolutePath().normalize().toString();
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && normalized.startsWith(home)) {
            normalized = "~" + normalized.substring(home.length());
        }
        return normalized;
    }

    private static int visibleLength(String text) {
        return text == null ? 0 : text.length();
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
