package com.paicli.render.inline;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.PlainRenderer;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import com.paicli.util.AnsiStyle;
import org.jline.terminal.Terminal;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Inline 流式渲染器：默认形态。
 *
 * <p>不进 alternate screen，主屏直接输出；底部用 DECSTBM 留出常驻状态栏。
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
    private final List<TranscriptEntry> transcript = new ArrayList<>();
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
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
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
            out.print(rendered);
            out.flush();
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
                    feedWithCodeBlockDetection(text, delegate);
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
    private void feedWithCodeBlockDetection(String text, PrintStream delegate) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            lineBuffer.append(ch);
            if (ch == '\n') {
                String line = lineBuffer.toString();
                lineBuffer.setLength(0);
                processStreamedLine(line, delegate);
            }
        }
    }

    private void processStreamedLine(String line, PrintStream delegate) {
        String stripped = stripAnsi(line).trim();

        if (!inCodeBlock && stripped.startsWith("┌─ code")) {
            // 进入代码块：写出 header，记录 transcript 位置，body 之后会被吞掉
            inCodeBlock = true;
            int colon = stripped.indexOf(':');
            codeLanguage = colon >= 0 ? stripped.substring(colon + 1).trim() : "";
            codeHeaderLine = stripTrailingNewline(line);
            codeBodyLines.clear();
            delegate.print(line);
            codeStartTranscriptIndex = transcript.size();
            transcript.add(new TextEntry(line));
            renderedRows += estimateRows(line);
            return;
        }

        if (inCodeBlock) {
            if (stripped.startsWith("└─ end")) {
                int bodyLineCount = codeBodyLines.size();
                inCodeBlock = false;

                // 用 ANSI move-up + clear-to-eos 覆盖原 header 行
                delegate.print(AnsiSeq.moveUp(1));
                delegate.print("\r");
                delegate.print(AnsiSeq.CLEAR_TO_EOS);

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
                }

                delegate.print(collapsedHeader);
                delegate.print("\n");

                // renderedRows 调整：移除原 header 占位（约 1 行），加入折叠头占位
                renderedRows = Math.max(0, renderedRows - estimateRows(codeHeaderLine + "\n"));
                renderedRows += estimateRows(collapsedHeader + "\n");

                codeBodyLines.clear();
                codeHeaderLine = null;
                codeStartTranscriptIndex = -1;
                return;
            }
            // body 行：缓冲，不写终端、不入 transcript
            codeBodyLines.add(line);
            return;
        }

        // 非代码块：常规流式
        delegate.print(line);
        transcript.add(new TextEntry(line));
        renderedRows += estimateRows(line);
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
