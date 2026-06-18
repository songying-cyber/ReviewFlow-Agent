package com.paicli.render.inline;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.PlainRenderer;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import com.paicli.util.AnsiStyle;
import org.jline.reader.LineReader;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;

import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final InlineActivityDisplay activityDisplay;
    private final List<TranscriptEntry> transcript = new ArrayList<>();
    private final AtomicBoolean startupScreenPrinted = new AtomicBoolean(true);
    private volatile LineReader lineReader;
    private int renderedRows;
    private boolean redrawing;
    private volatile boolean started;
    private volatile boolean closed;

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
        this.activityDisplay = statusBar == null
                ? null
                : new InlineActivityDisplay(terminal, out, statusBar);
        this.blockRegistry = new BlockRegistry();
        this.stream = createTranscriptStream(out);
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
        if (activityDisplay != null) {
            activityDisplay.close();
        }
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
        return activityDisplay != null;
    }

    @Override
    public void beginThinking(String label) {
        if (activityDisplay != null && !closed) {
            activityDisplay.begin(label);
        }
    }

    @Override
    public void appendThinking(String delta) {
        if (activityDisplay != null && !closed) {
            activityDisplay.appendThinking(delta);
        }
    }

    @Override
    public void endThinking() {
        if (activityDisplay != null) {
            activityDisplay.end();
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

    /**
     * 在 LineReader 第一次进入 readLine 时打印首屏。
     *
     * <p>首屏不能在 readLine 之前用普通 stdout 打印：底部 Status 初始化后，LineReader
     * 第一次重绘会重新接管输入区，提前打印的 banner 容易被滚动或覆盖。挂到
     * {@link LineReader#CALLBACK_INIT} 后，JLine 会把首屏、输入行和底部 dock 放在同一个
     * 显示生命周期里处理。
     */
    public void installStartupScreen(List<String> lines) {
        LineReader reader = lineReader;
        if (reader == null || lines == null || lines.isEmpty()) {
            return;
        }
        startupScreenPrinted.set(false);
        List<String> snapshot = List.copyOf(lines);
        Widget previous = reader.getWidgets().get(LineReader.CALLBACK_INIT);
        reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
            boolean ok = previous == null || previous.apply();
            if (startupScreenPrinted.compareAndSet(false, true)) {
                reader.printAbove(joinLines(snapshot));
            }
            return ok;
        });
    }

    /**
     * 清掉 JLine accept 后留在屏幕上的编辑态输入行。
     *
     * <p>普通任务会随后以 {@code > prompt} 的 transcript 块写回；这里清理的是编辑态
     * {@code * prompt}，避免同一条输入在屏幕上出现两次。
     */
    public void clearAcceptedInput(String input) {
        if (terminal == null || closed) {
            return;
        }
        int rows = acceptedInputRows(input);
        synchronized (out) {
            PrintWriter writer = terminal.writer();
            if (writer != null) {
                writer.print(clearAcceptedInputSequence(rows));
                writer.flush();
            } else {
                out.print(clearAcceptedInputSequence(rows));
                out.flush();
            }
            terminal.flush();
        }
    }

    public void printSubmittedPrompt(String input) {
        String visible = input == null ? "" : input.strip();
        if (visible.isEmpty()) {
            return;
        }
        int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns() - 1);
        emit(AnsiStyle.userMessageBlock(visible, cols) + "\n");
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
        if (activityDisplay != null) {
            activityDisplay.refreshIfActive();
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

    private static String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        String block = String.join("\n", lines);
        return block.endsWith("\n") ? block : block + "\n";
    }

    private int acceptedInputRows(String input) {
        int cols = Math.max(1, TerminalCapabilities.safeSize(terminal).getColumns());
        String text = input == null ? "" : input;
        String[] parts = text.split("\\R", -1);
        int rows = 0;
        for (int i = 0; i < parts.length; i++) {
            int cells = displayWidth(parts[i]) + (i == 0 ? displayWidth(inputPrompt()) : 0);
            rows += Math.max(1, (cells + cols - 1) / cols);
        }
        return Math.max(1, rows);
    }

    static String clearAcceptedInputSequence(int rows) {
        int count = Math.max(1, rows);
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiSeq.moveUp(count)).append('\r');
        for (int i = 0; i < count; i++) {
            sb.append(AnsiSeq.CLEAR_LINE);
            if (i < count - 1) {
                sb.append('\n');
            }
        }
        if (count > 1) {
            sb.append(AnsiSeq.moveUp(count - 1));
        }
        sb.append('\r');
        return sb.toString();
    }

    private static int displayWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            width += isWideCodePoint(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return width;
    }

    private static boolean isWideCodePoint(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL
                || (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0xFF01 && cp <= 0xFF60);
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
