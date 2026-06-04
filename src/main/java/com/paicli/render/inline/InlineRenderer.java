package com.paicli.render.inline;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.PlainRenderer;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import com.paicli.util.AnsiStyle;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Inline 流式渲染器：默认形态。
 *
 * <p>不进 alternate screen，主屏直接输出；输入期状态区紧跟当前 prompt 渲染。
 * 工具调用块、行内 diff、HITL 单字符提示、palette 等高级特性在 Day 3 / Day 4 落地，
 * 现阶段对应方法委托给 {@link PlainRenderer} 兜底。
 */
public final class InlineRenderer implements Renderer {

    private final Terminal terminal;
    private final PlainRenderer fallback;
    private final BottomStatusBar statusBar;
    private final BlockRegistry blockRegistry;
    private final PrintStream stream;
    private final PrintStream out;
    private final Object transcriptLock = new Object();
    private final Object thinkingLock = new Object();
    private final ScheduledExecutorService thinkingScheduler;
    private final List<TranscriptEntry> transcript = new ArrayList<>();
    private volatile LineReader lineReader;
    private int renderedRows;
    private boolean redrawing;
    private volatile boolean started;
    private volatile boolean closed;
    private final StringBuilder thinkingBuffer = new StringBuilder();
    private ScheduledFuture<?> thinkingTask;
    private boolean thinkingActive;
    private String thinkingLabel = "Thinking";
    private long thinkingStartedNanos;
    private int thinkingFrame;
    private int thinkingRenderedRows;

    // —— 代码块折叠状态机字段（仅供 createTranscriptStream 内部使用）——
    private final StringBuilder lineBuffer = new StringBuilder();
    private final List<String> codeBodyLines = new ArrayList<>();
    private boolean inCodeBlock;
    private String codeLanguage = "";
    private String codeHeaderLine;
    private int codeStartTranscriptIndex = -1;
    private boolean codeHeaderEmitted;

    public InlineRenderer(Terminal terminal) {
        this(terminal, System.out);
    }

    /** 测试用构造器：注入输出流，避免污染真实 stdout。 */
    InlineRenderer(Terminal terminal, PrintStream out) {
        this.terminal = terminal;
        this.fallback = new PlainRenderer();
        this.out = out;
        this.statusBar = TerminalCapabilities.supportsScrollRegion(terminal)
                ? new BottomStatusBar(terminal, out)
                : null;
        this.blockRegistry = new BlockRegistry();
        this.stream = createTranscriptStream(out);
        this.thinkingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "paicli-thinking-panel");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void beginTurn() {
        synchronized (transcriptLock) {
            transcript.clear();
            renderedRows = 0;
            lineBuffer.setLength(0);
            inCodeBlock = false;
            codeBodyLines.clear();
            codeLanguage = "";
            codeHeaderLine = null;
            codeStartTranscriptIndex = -1;
            codeHeaderEmitted = false;
        }
        blockRegistry.clear();
    }

    @Override
    public void start() {
        if (started || closed) {
            return;
        }
        if (statusBar != null) {
            statusBar.start();
        }
        started = true;
    }

    @Override
    public void beforeInput() {
        if (statusBar != null) {
            statusBar.prepareInputLine();
            statusBar.flushNow();
        }
    }

    @Override
    public void afterInput() {
        if (statusBar != null) {
            statusBar.finishInputLine();
        }
    }

    @Override
    public String inputPrompt() {
        return "* ";
    }

    @Override
    public String inputRightPrompt() {
        return "message / @path / @image";
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        endThinking();
        thinkingScheduler.shutdownNow();
        if (statusBar != null) {
            statusBar.close();
        }
        fallback.close();
    }

    @Override
    public PrintStream stream() {
        return stream;
    }

    @Override
    public boolean supportsThinkingPanel() {
        return statusBar != null && terminal != null;
    }

    @Override
    public void beginThinking(String label) {
        if (!supportsThinkingPanel() || closed) {
            return;
        }
        synchronized (thinkingLock) {
            clearThinkingPanelLocked();
            thinkingBuffer.setLength(0);
            thinkingLabel = (label == null || label.isBlank()) ? "Thinking" : label.trim();
            thinkingStartedNanos = System.nanoTime();
            thinkingFrame = 0;
            thinkingActive = true;
            renderThinkingPanelLocked();
            if (thinkingTask != null) {
                thinkingTask.cancel(false);
            }
            thinkingTask = thinkingScheduler.scheduleAtFixedRate(
                    this::tickThinkingPanel, 250, 250, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void appendThinking(String delta) {
        if (delta == null || delta.isEmpty() || closed) {
            return;
        }
        if (!supportsThinkingPanel()) {
            return;
        }
        synchronized (thinkingLock) {
            if (!thinkingActive) {
                thinkingStartedNanos = System.nanoTime();
                thinkingLabel = "Thinking";
                thinkingFrame = 0;
                thinkingActive = true;
            }
            thinkingBuffer.append(delta);
            trimThinkingBuffer();
            renderThinkingPanelLocked();
        }
    }

    @Override
    public void endThinking() {
        if (!supportsThinkingPanel()) {
            return;
        }
        synchronized (thinkingLock) {
            thinkingActive = false;
            if (thinkingTask != null) {
                thinkingTask.cancel(false);
                thinkingTask = null;
            }
            clearThinkingPanelLocked();
            thinkingBuffer.setLength(0);
        }
    }

    /**
     * 绑定当前交互循环使用的 JLine LineReader。
     *
     * <p>绑定后，用户正在输入时的异步输出会优先通过
     * {@link LineReader#printAbove(String)} 显示在输入行上方；非读取态和
     * 测试/降级路径继续使用构造时注入的 {@link PrintStream}。
     */
    public void bindLineReader(LineReader lineReader) {
        this.lineReader = lineReader;
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        Map<String, List<LlmClient.ToolCall>> grouped = ToolCallRenderer.group(toolCalls);
        FoldableBlock block = new FoldableBlock(out,
                ToolCallRenderer.collapsedHeader(grouped),
                ToolCallRenderer.expandedLines(grouped));
        blockRegistry.register(block);
        TranscriptEntry entry = new BlockEntry(block);
        String rendered = entry.render();
        synchronized (transcriptLock) {
            transcript.add(entry);
            renderedRows += estimateRows(rendered);
            emit(rendered);
        }
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        new InlineDiffRenderer(out).render(filePath, before, after);
    }

    @Override
    public void updateStatus(StatusInfo status) {
        if (statusBar != null) {
            statusBar.update(status);
        }
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        if (terminal == null) {
            return fallback.promptApproval(request);
        }
        return new InlineApprovalPrompter(out, terminal).prompt(request);
    }

    @Override
    public int openPalette(String title, List<String> items) {
        if (terminal == null) {
            return fallback.openPalette(title, items);
        }
        return new SlashPalette(out, terminal).open(title, items);
    }

    /** 测试可见：当前实例是否启动了 status bar。 */
    public boolean hasStatusBar() {
        return statusBar != null;
    }

    /** 测试 / Main.java 可见：拿到 terminal 用于其它 inline 组件。 */
    public Terminal terminal() {
        return terminal;
    }

    /** Main.java 用：把 Ctrl+O 绑定到 toggleLast。 */
    public BlockRegistry blockRegistry() {
        return blockRegistry;
    }

    /** Main.java 用：Ctrl+O 触发内存态切换，然后重绘本轮 transcript。 */
    public boolean toggleLastBlock() {
        boolean changed = blockRegistry.toggleLastForRedraw();
        if (changed) {
            redrawTranscript();
        }
        return changed;
    }

    private PrintStream createTranscriptStream(PrintStream delegate) {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                if (len <= 0) {
                    return;
                }
                String text = new String(b, off, len, StandardCharsets.UTF_8);
                synchronized (transcriptLock) {
                    if (redrawing) {
                        // 重绘期间 BlockEntry.render() 已经决定折叠/展开形态，原样转发不再走折叠状态机
                        delegate.write(b, off, len);
                        return;
                    }
                    feedWithCodeBlockDetection(text);
                }
            }

            @Override
            public void flush() {
                delegate.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    /**
     * 行级状态机：检测 {@code ┌─ code:} / {@code └─ end} 边界，把整段代码块换成
     * {@link FoldableBlock}。代码体在流式期间不写到终端（避免大段文本刷屏），
     * 等 {@code └─ end} 到达后用 {@code [<n>A[J} 回退覆盖原 header 行 + 输出折叠头。
     *
     * <p>已知小瑕疵：代码块流式期间用户按 Ctrl+O 触发 {@link #redrawTranscript()} 会
     * 看到 header 行被重新渲染但 body 行尚未在 transcript 里——属于罕见时序，
     * 下一次 LLM 输出 / `/clear` / 退出可恢复。
     */
    private void feedWithCodeBlockDetection(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            lineBuffer.append(ch);
            if (ch == '\n') {
                String line = lineBuffer.toString();
                lineBuffer.setLength(0);
                processStreamedLine(line);
            }
        }
    }

    private void processStreamedLine(String line) {
        String stripped = stripAnsi(line).trim();

        if (!inCodeBlock && stripped.startsWith("┌─ code")) {
            // 进入代码块：写出 header，记录 transcript 位置，body 之后会被吞掉
            inCodeBlock = true;
            int colon = stripped.indexOf(':');
            codeLanguage = colon >= 0 ? stripped.substring(colon + 1).trim() : "";
            codeHeaderLine = stripTrailingNewline(line);
            codeBodyLines.clear();
            codeStartTranscriptIndex = transcript.size();
            codeHeaderEmitted = activePrintAboveReader() == null;
            if (codeHeaderEmitted) {
                emit(line);
                transcript.add(new TextEntry(line));
                renderedRows += estimateRows(line);
            }
            return;
        }

        if (inCodeBlock) {
            if (stripped.startsWith("└─ end")) {
                int bodyLineCount = codeBodyLines.size();
                inCodeBlock = false;

                if (codeHeaderEmitted) {
                    // 用 ANSI move-up + clear-to-eos 覆盖原 header 行。这里必须直写
                    // 底层输出，因为 printAbove 是追加式输出，不适合承载光标回退。
                    out.print(AnsiSeq.moveUp(1));
                    out.print("\r");
                    out.print(AnsiSeq.CLEAR_TO_EOS);
                }

                String label = codeLanguage.isEmpty() ? "code" : "code: " + codeLanguage;
                String collapsedHeader = AnsiStyle.subtle(
                        "⏵ " + label + " (" + bodyLineCount + " 行, ctrl+o to expand)");

                List<String> expandedLines = new ArrayList<>();
                expandedLines.add(stripTrailingNewline(codeHeaderLine));
                for (String body : codeBodyLines) {
                    expandedLines.add(stripTrailingNewline(body));
                }
                expandedLines.add(stripTrailingNewline(line));

                FoldableBlock block = new FoldableBlock(out, collapsedHeader, expandedLines, "⏷ collapse (ctrl+o)");
                blockRegistry.register(block);

                if (codeStartTranscriptIndex >= 0 && codeStartTranscriptIndex < transcript.size()) {
                    transcript.set(codeStartTranscriptIndex, new BlockEntry(block));
                } else {
                    transcript.add(new BlockEntry(block));
                }

                if (codeHeaderEmitted) {
                    out.print(collapsedHeader);
                    out.print("\n");
                    out.flush();
                } else {
                    emit(collapsedHeader + "\n");
                }

                // renderedRows 调整：移除原 header 占位（约 1 行），加入折叠头占位
                renderedRows = Math.max(0, renderedRows - estimateRows(codeHeaderLine + "\n"));
                renderedRows += estimateRows(collapsedHeader + "\n");

                codeBodyLines.clear();
                codeHeaderLine = null;
                codeStartTranscriptIndex = -1;
                codeHeaderEmitted = false;
                return;
            }
            // body 行：缓冲，不写终端、不入 transcript
            codeBodyLines.add(line);
            return;
        }

        // 非代码块：常规流式
        emit(line);
        transcript.add(new TextEntry(line));
        renderedRows += estimateRows(line);
    }

    private LineReader activePrintAboveReader() {
        LineReader reader = lineReader;
        if (reader == null || redrawing || closed) {
            return null;
        }
        try {
            return reader.isReading() ? reader : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void emit(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        LineReader reader = activePrintAboveReader();
        if (reader != null) {
            reader.printAbove(text);
            return;
        }
        out.print(text);
        out.flush();
    }

    private void tickThinkingPanel() {
        synchronized (thinkingLock) {
            if (!thinkingActive || closed) {
                return;
            }
            thinkingFrame++;
            renderThinkingPanelLocked();
        }
    }

    private void renderThinkingPanelLocked() {
        if (!thinkingActive || closed) {
            return;
        }
        List<String> lines = thinkingPanelLines();
        synchronized (out) {
            clearThinkingPanelLocked();
            for (String line : lines) {
                out.print(line);
                out.print('\n');
            }
            out.flush();
            thinkingRenderedRows = lines.size();
        }
    }

    private void clearThinkingPanelLocked() {
        if (thinkingRenderedRows <= 0) {
            return;
        }
        synchronized (out) {
            out.print(AnsiSeq.moveUp(thinkingRenderedRows));
            out.print('\r');
            out.print(AnsiSeq.CLEAR_TO_EOS);
            out.flush();
        }
        thinkingRenderedRows = 0;
    }

    private List<String> thinkingPanelLines() {
        int elapsedSeconds = (int) Math.max(0, TimeUnit.NANOSECONDS.toSeconds(
                System.nanoTime() - thinkingStartedNanos));
        String dots = switch (thinkingFrame % 4) {
            case 0 -> ".";
            case 1 -> "..";
            case 2 -> "...";
            default -> "....";
        };
        List<String> lines = new ArrayList<>();
        lines.add(AnsiStyle.subtle(": " + thinkingLabel + dots + " (ESC 取消, " + elapsedSeconds + "s)"));
        for (String line : latestThinkingLines(3)) {
            lines.add(AnsiStyle.subtle("  > " + line));
        }
        return lines;
    }

    private List<String> latestThinkingLines(int maxLines) {
        String text = thinkingBuffer.toString()
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (text.isEmpty()) {
            return List.of();
        }
        String[] rawLines = text.split("\\R+");
        int start = Math.max(0, rawLines.length - maxLines);
        int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns());
        int maxChars = Math.max(20, cols - 6);
        List<String> lines = new ArrayList<>();
        for (int i = start; i < rawLines.length; i++) {
            String line = rawLines[i].replaceAll("\\s+", " ").trim();
            if (line.isEmpty()) {
                continue;
            }
            lines.add(truncateVisible(line, maxChars));
        }
        return lines;
    }

    private String truncateVisible(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 1) {
            return "…";
        }
        return text.substring(0, maxChars - 1) + "…";
    }

    private void trimThinkingBuffer() {
        int max = 4096;
        if (thinkingBuffer.length() <= max) {
            return;
        }
        thinkingBuffer.delete(0, thinkingBuffer.length() - max);
    }

    private static String stripAnsi(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                int j = i + 2;
                while (j < s.length()) {
                    char c = s.charAt(j);
                    if (c >= '@' && c <= '~') {
                        break;
                    }
                    j++;
                }
                i = j;
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String stripTrailingNewline(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        int end = s.length();
        if (s.charAt(end - 1) == '\n') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '\r') {
            end--;
        }
        return s.substring(0, end);
    }

    private void redrawTranscript() {
        synchronized (transcriptLock) {
            if (transcript.isEmpty()) {
                return;
            }
            LineReader reader = activePrintAboveReader();
            if (reader != null) {
                StringBuilder snapshot = new StringBuilder();
                int rowsAfter = 0;
                for (TranscriptEntry entry : transcript) {
                    String rendered = entry.render();
                    snapshot.append(rendered);
                    rowsAfter += estimateRows(rendered);
                }
                renderedRows = rowsAfter;
                reader.printAbove(snapshot.toString());
                return;
            }
            redrawing = true;
            try {
                int rows = TerminalCapabilities.safeSize(terminal).getRows();
                int maxMove = Math.max(1, rows - (statusBar == null ? 1 : 2));
                int move = Math.min(renderedRows, maxMove);
                if (move > 0) {
                    out.print(AnsiSeq.moveUp(move));
                }
                out.print("\r");
                out.print(AnsiSeq.CLEAR_TO_EOS);
                int rowsAfter = 0;
                for (TranscriptEntry entry : transcript) {
                    String rendered = entry.render();
                    out.print(rendered);
                    rowsAfter += estimateRows(rendered);
                }
                renderedRows = rowsAfter;
                out.flush();
            } finally {
                redrawing = false;
            }
        }
    }

    private int estimateRows(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns());
        int rows = 0;
        int col = 0;
        boolean sawVisible = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\u001B') {
                i = skipAnsi(text, i);
                continue;
            }
            if (ch == '\r') {
                col = 0;
                continue;
            }
            if (ch == '\n') {
                rows++;
                col = 0;
                sawVisible = false;
                continue;
            }
            int width = displayWidth(ch);
            if (width <= 0) {
                continue;
            }
            sawVisible = true;
            col += width;
            if (col >= cols) {
                rows++;
                col = 0;
                sawVisible = false;
            }
        }
        if (sawVisible) {
            rows++;
        }
        return rows;
    }

    private int skipAnsi(String text, int escIndex) {
        int i = escIndex + 1;
        if (i < text.length() && text.charAt(i) == '[') {
            i++;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c >= '@' && c <= '~') {
                    return i;
                }
                i++;
            }
        }
        return escIndex;
    }

    private int displayWidth(char ch) {
        if (Character.isISOControl(ch)) {
            return 0;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA) {
            return 2;
        }
        return 1;
    }

    private interface TranscriptEntry {
        String render();
    }

    private record TextEntry(String text) implements TranscriptEntry {
        @Override
        public String render() {
            return text;
        }
    }

    private record BlockEntry(FoldableBlock block) implements TranscriptEntry {
        @Override
        public String render() {
            return String.join(System.lineSeparator(), block.currentLines()) + System.lineSeparator();
        }
    }
}
