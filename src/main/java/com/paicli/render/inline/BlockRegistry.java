package com.paicli.render.inline;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 活动 {@link FoldableBlock} 注册表。
 *
 * <p>维护一个双端队列，新注册的块成为"队尾活跃块"，之前的块被 freeze
 * （因为新输出意味着它们已经向上滚走，无法再覆盖重绘）。
 *
 * <p>{@link #toggleLast()} 永远只 toggle 队尾且尚未被后续输出污染的活跃块。
 * 一旦普通输出继续写入，调用方必须 {@link #freezeAll()}，避免用相对光标覆盖
 * 已经滚走的历史内容。
 */
public final class BlockRegistry {

    private final Deque<FoldableBlock> blocks = new ArrayDeque<>();

    /** 注册新块；之前的所有块进入 frozen 状态。 */
    public synchronized void register(FoldableBlock block) {
        for (FoldableBlock existing : blocks) {
            existing.freeze();
        }
        blocks.addLast(block);
    }

    /** Toggle 队尾活跃块（即最近一次 register 的块）。返回是否生效。 */
    public synchronized boolean toggleLast() {
        FoldableBlock last = blocks.peekLast();
        if (last == null) {
            return false;
        }
        return last.toggle();
    }

    /** Toggle 队尾块的内存态，由 transcript 重绘负责真正输出。 */
    public synchronized boolean toggleLastForRedraw() {
        FoldableBlock last = blocks.peekLast();
        if (last == null) {
            return false;
        }
        return last.toggleForRedraw();
    }

    /** 后续普通输出已经出现，所有现存块都不能再做原地覆盖重绘。 */
    public synchronized void freezeAll() {
        for (FoldableBlock block : blocks) {
            block.freeze();
        }
    }

    /** 清空注册表（如 /clear 时）。 */
    public synchronized void clear() {
        blocks.clear();
    }

    /** 当前注册数量（含 frozen）。 */
    public synchronized int size() {
        return blocks.size();
    }

    /** 测试可见：当前队尾块。 */
    synchronized FoldableBlock peekLast() {
        return blocks.peekLast();
    }
}
