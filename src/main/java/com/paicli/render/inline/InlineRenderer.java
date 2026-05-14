package com.paicli.render.inline;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.PlainRenderer;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import org.jline.terminal.Terminal;

import java.io.PrintStream;
import java.util.List;

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
    private final ToolCallRenderer toolCallRenderer;
    private volatile boolean started;
    private volatile boolean closed;

    public InlineRenderer(Terminal terminal) {
        this.terminal = terminal;
        this.fallback = new PlainRenderer();
        this.statusBar = TerminalCapabilities.supportsScrollRegion(terminal)
                ? new BottomStatusBar(terminal)
                : null;
        this.blockRegistry = new BlockRegistry();
        this.toolCallRenderer = new ToolCallRenderer(System.out, blockRegistry);
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
        return fallback.stream();
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        toolCallRenderer.render(toolCalls);
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        new InlineDiffRenderer(System.out).render(filePath, before, after);
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
        return new InlineApprovalPrompter(System.out, terminal).prompt(request);
    }

    @Override
    public int openPalette(String title, List<String> items) {
        if (terminal == null) {
            return fallback.openPalette(title, items);
        }
        return new SlashPalette(System.out, terminal).open(title, items);
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
}
