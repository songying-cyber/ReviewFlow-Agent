package com.paicli.render.inline;

import java.io.PrintStream;
import java.util.List;

/**
 * 行内可折叠块。
 *
 * <p>典型用途是工具调用的展示：折叠时一行 {@code ⏵ Read 3 files (ctrl+o to expand)}，
 * 展开时多行原始内容 + 末行 {@code ⏷ collapse}。
 *
 * <p>实现方式：保存渲染后占用的行数，toggle 时通过 ANSI 序列
 * （{@code [<n>A} 上移 + {@code [J} 清屏到底）覆盖原内容再重新渲染。
 *
 * <p>约束：toggle 前不能有其它输出滚走本块——否则 {@code renderedLineCount}
 * 不再可信，块会被 {@link BlockRegistry} 标记为 frozen。
 */
public final class FoldableBlock {

    private final PrintStream out;
    private final String collapsedHeader;
    private final List<String> expandedLines;
    private final String collapseFooter;

    private boolean expanded;
    private int renderedLineCount;
    private volatile boolean frozen;

    public FoldableBlock(PrintStream out,
                         String collapsedHeader,
                         List<String> expandedLines) {
        this(out, collapsedHeader, expandedLines, "⏷ collapse (ctrl+o)");
    }

    public FoldableBlock(PrintStream out,
                         String collapsedHeader,
                         List<String> expandedLines,
                         String collapseFooter) {
        this.out = out;
        this.collapsedHeader = collapsedHeader;
        this.expandedLines = List.copyOf(expandedLines);
        this.collapseFooter = collapseFooter;
    }

    /** 首次渲染（折叠态），由调用方在 BlockRegistry.register 之前/之后调用。 */
    public void renderInitial() {
        synchronized (out) {
            out.println(collapsedHeader);
            renderedLineCount = 1;
            out.flush();
        }
    }

    public boolean isExpanded() {
        return expanded;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void freeze() {
        frozen = true;
    }

    /**
     * 展开/收起切换。frozen 后不再生效。
     *
     * @return true 表示成功 toggle；false 表示已 frozen
     */
    public boolean toggle() {
        if (frozen) {
            return false;
        }
        synchronized (out) {
            // 上移 N 行覆盖原渲染
            out.print(AnsiSeq.moveUp(renderedLineCount));
            out.print("\r");
            out.print(AnsiSeq.CLEAR_TO_EOS);
            if (expanded) {
                out.println(collapsedHeader);
                renderedLineCount = 1;
            } else {
                for (String line : expandedLines) {
                    out.println(line);
                }
                if (collapseFooter != null && !collapseFooter.isEmpty()) {
                    out.println(collapseFooter);
                    renderedLineCount = expandedLines.size() + 1;
                } else {
                    renderedLineCount = expandedLines.size();
                }
            }
            expanded = !expanded;
            out.flush();
        }
        return true;
    }

    /** 只切换内存态，不直接写终端；用于 transcript 级重绘。 */
    public boolean toggleForRedraw() {
        expanded = !expanded;
        renderedLineCount = currentLines().size();
        return true;
    }

    /** 当前状态下应渲染的完整行。 */
    public List<String> currentLines() {
        if (!expanded) {
            return List.of(collapsedHeader);
        }
        if (collapseFooter == null || collapseFooter.isEmpty()) {
            return expandedLines;
        }
        java.util.ArrayList<String> lines = new java.util.ArrayList<>(expandedLines);
        lines.add(collapseFooter);
        return lines;
    }

    /** 测试可见：返回当前折叠态文案。 */
    String collapsedHeader() {
        return collapsedHeader;
    }

    /** 测试可见：返回展开内容。 */
    List<String> expandedLines() {
        return expandedLines;
    }

    /** 测试可见：返回当前占用行数。 */
    int renderedLineCount() {
        return renderedLineCount;
    }
}
