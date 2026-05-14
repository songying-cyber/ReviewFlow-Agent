package com.paicli.render.inline;

import com.paicli.util.AnsiStyle;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 行内 diff 渲染：红减、绿加、青色 hunk header。
 *
 * <p>不依赖第三方 diff 库——用最朴素的 LCS 实现 O(N×M)，对单文件够用。
 * 整体新增 / 整体删除场景用快速路径。
 *
 * <p>颜色用 ANSI 256 色码：
 * <ul>
 *   <li>新增行：绿色前缀 {@code +} + 行内容</li>
 *   <li>删除行：红色前缀 {@code -} + 行内容</li>
 *   <li>不变行：默认色（仅在 hunk 上下文里展示）</li>
 *   <li>hunk header：青色 {@code @@ -a,b +c,d @@}</li>
 * </ul>
 */
public final class InlineDiffRenderer {

    private static final String GREEN = "[32m";
    private static final String RED = "[31m";
    private static final String CYAN = "[36m";
    private static final String RESET = "[0m";
    private static final int CONTEXT_LINES = 2;

    private final PrintStream out;

    public InlineDiffRenderer(PrintStream out) {
        this.out = out;
    }

    public void render(String filePath, String before, String after) {
        out.println();
        out.println(AnsiStyle.heading("📝 " + (filePath == null ? "(unnamed)" : filePath)));
        if (before == null && after == null) {
            out.println(AnsiStyle.subtle("  (空 diff)"));
            return;
        }
        if (before == null) {
            renderNewFile(after);
            return;
        }
        if (after == null) {
            renderDeleteFile(before);
            return;
        }
        if (Objects.equals(before, after)) {
            out.println(AnsiStyle.subtle("  (内容未变)"));
            return;
        }
        renderUnifiedDiff(before, after);
    }

    private void renderNewFile(String after) {
        String[] lines = after.split("\n", -1);
        out.println(CYAN + "@@ -0,0 +1," + lines.length + " @@" + RESET);
        for (String line : lines) {
            if (line.isEmpty()) continue;
            out.println(GREEN + "+" + line + RESET);
        }
    }

    private void renderDeleteFile(String before) {
        String[] lines = before.split("\n", -1);
        out.println(CYAN + "@@ -1," + lines.length + " +0,0 @@" + RESET);
        for (String line : lines) {
            if (line.isEmpty()) continue;
            out.println(RED + "-" + line + RESET);
        }
    }

    private void renderUnifiedDiff(String before, String after) {
        String[] beforeLines = before.split("\n", -1);
        String[] afterLines = after.split("\n", -1);
        List<DiffOp> ops = computeDiff(beforeLines, afterLines);
        List<Hunk> hunks = groupIntoHunks(ops, beforeLines, afterLines);
        for (Hunk hunk : hunks) {
            out.println(CYAN + hunk.header() + RESET);
            for (DiffOp op : hunk.ops) {
                switch (op.type) {
                    case EQUAL -> out.println(" " + op.text);
                    case ADD -> out.println(GREEN + "+" + op.text + RESET);
                    case DELETE -> out.println(RED + "-" + op.text + RESET);
                }
            }
        }
    }

    enum OpType { EQUAL, ADD, DELETE }

    record DiffOp(OpType type, String text, int beforeIndex, int afterIndex) {
    }

    /** 朴素 LCS-based diff。 */
    static List<DiffOp> computeDiff(String[] before, String[] after) {
        int n = before.length;
        int m = after.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (before[i].equals(after[j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        List<DiffOp> ops = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (before[i].equals(after[j])) {
                ops.add(new DiffOp(OpType.EQUAL, before[i], i, j));
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ops.add(new DiffOp(OpType.DELETE, before[i], i, -1));
                i++;
            } else {
                ops.add(new DiffOp(OpType.ADD, after[j], -1, j));
                j++;
            }
        }
        while (i < n) {
            ops.add(new DiffOp(OpType.DELETE, before[i], i, -1));
            i++;
        }
        while (j < m) {
            ops.add(new DiffOp(OpType.ADD, after[j], -1, j));
            j++;
        }
        return ops;
    }

    record Hunk(int beforeStart, int beforeCount, int afterStart, int afterCount, List<DiffOp> ops) {
        String header() {
            return "@@ -" + (beforeStart + 1) + "," + beforeCount
                    + " +" + (afterStart + 1) + "," + afterCount + " @@";
        }
    }

    /** 把连续的 EQUAL 段切成 hunks，每个 hunk 包含 CONTEXT_LINES 上下行。 */
    static List<Hunk> groupIntoHunks(List<DiffOp> ops, String[] before, String[] after) {
        List<Hunk> hunks = new ArrayList<>();
        int idx = 0;
        while (idx < ops.size()) {
            // 找到下一个非 EQUAL 操作
            while (idx < ops.size() && ops.get(idx).type == OpType.EQUAL) {
                idx++;
            }
            if (idx >= ops.size()) break;
            int hunkStart = Math.max(0, idx - CONTEXT_LINES);
            // 找到 hunk 结束：连续 EQUAL 数 ≥ 2*CONTEXT_LINES 时分块
            int hunkEnd = idx;
            int equalRun = 0;
            while (hunkEnd < ops.size()) {
                DiffOp op = ops.get(hunkEnd);
                if (op.type == OpType.EQUAL) {
                    equalRun++;
                    if (equalRun >= 2 * CONTEXT_LINES) {
                        break;
                    }
                } else {
                    equalRun = 0;
                }
                hunkEnd++;
            }
            int hunkClose = Math.min(ops.size(), hunkEnd + CONTEXT_LINES - equalRun);
            // 收集本 hunk 的 ops
            List<DiffOp> hunkOps = new ArrayList<>(ops.subList(hunkStart, hunkClose));
            int beforeStart = firstBeforeIndex(hunkOps);
            int afterStart = firstAfterIndex(hunkOps);
            int beforeCount = (int) hunkOps.stream().filter(o -> o.type != OpType.ADD).count();
            int afterCount = (int) hunkOps.stream().filter(o -> o.type != OpType.DELETE).count();
            hunks.add(new Hunk(beforeStart, beforeCount, afterStart, afterCount, hunkOps));
            idx = hunkClose;
        }
        return hunks;
    }

    private static int firstBeforeIndex(List<DiffOp> ops) {
        for (DiffOp op : ops) {
            if (op.beforeIndex >= 0) return op.beforeIndex;
        }
        return 0;
    }

    private static int firstAfterIndex(List<DiffOp> ops) {
        for (DiffOp op : ops) {
            if (op.afterIndex >= 0) return op.afterIndex;
        }
        return 0;
    }
}
