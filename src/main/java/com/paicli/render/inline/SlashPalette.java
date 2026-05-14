package com.paicli.render.inline;

import com.paicli.util.AnsiStyle;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import java.io.PrintStream;
import java.util.List;

/**
 * 临时浮起的命令选择列表。
 *
 * <p>渲染策略：在当前光标位置直接打印列表（占 N+2 行），用 raw mode 读单键
 * 处理 {@code ↑↓Enter}/数字/Esc。结束后用 {@code [<n>A[J} 把列表清掉，
 * 不留痕迹（行内 palette 风格，类似 Claude Code 的 slash command 展开）。
 *
 * <p>不支持光标动画 / 模糊搜索（留作后续增强）。
 */
public final class SlashPalette {

    private final PrintStream out;
    private final Terminal terminal;

    public SlashPalette(PrintStream out, Terminal terminal) {
        this.out = out;
        this.terminal = terminal;
    }

    /**
     * 打开 palette，阻塞等待用户选择。
     *
     * @return 选中项的下标；用户取消（Esc）返回 -1
     */
    public int open(String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return -1;
        }
        int selected = 0;
        int rendered = 0;
        try {
            while (true) {
                rendered = render(title, items, selected);
                int key = readKey();
                // 清掉本次渲染
                erase(rendered);
                rendered = 0;
                int decision = handleKey(key, selected, items.size());
                if (decision == DECISION_CANCEL) {
                    return -1;
                }
                if (decision == DECISION_CONFIRM) {
                    return selected;
                }
                if (decision >= 0 && decision < items.size()) {
                    return decision;  // 数字快捷键直接选定
                }
                if (decision == DECISION_UP) {
                    selected = (selected - 1 + items.size()) % items.size();
                } else if (decision == DECISION_DOWN) {
                    selected = (selected + 1) % items.size();
                }
            }
        } finally {
            erase(rendered);
        }
    }

    private int render(String title, List<String> items, int selected) {
        out.println();
        out.println(AnsiStyle.heading("┌─ " + (title == null ? "选择" : title) + " ─"));
        for (int i = 0; i < items.size(); i++) {
            String prefix = (i == selected) ? "▶ " : "  ";
            String numberHint = i < 9 ? "[" + (i + 1) + "] " : "    ";
            String line = "│ " + prefix + numberHint + items.get(i);
            if (i == selected) {
                out.println(AnsiStyle.emphasis(line));
            } else {
                out.println(line);
            }
        }
        out.println(AnsiStyle.subtle("└─ ↑↓ 切换  Enter 确认  Esc 取消  数字键直选"));
        out.flush();
        return items.size() + 3;
    }

    private void erase(int lines) {
        if (lines <= 0) return;
        synchronized (out) {
            out.print(AnsiSeq.moveUp(lines));
            out.print("\r");
            out.print(AnsiSeq.CLEAR_TO_EOS);
            out.flush();
        }
    }

    private int readKey() {
        Attributes original;
        try {
            original = terminal.enterRawMode();
        } catch (Exception e) {
            return -1;
        }
        try {
            terminal.flush();
            int b = terminal.reader().read();
            if (b == 27) {
                // ESC 或 ESC + 控制序列
                int next = terminal.reader().read(50);
                if (next < 0) return KEY_ESC;
                if (next == '[') {
                    int third = terminal.reader().read(50);
                    return switch (third) {
                        case 'A' -> KEY_UP;
                        case 'B' -> KEY_DOWN;
                        default -> KEY_ESC;
                    };
                }
                return KEY_ESC;
            }
            return b;
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                terminal.setAttributes(original);
            } catch (Exception ignored) {
            }
        }
    }

    private static final int KEY_ESC = -2;
    private static final int KEY_UP = -3;
    private static final int KEY_DOWN = -4;

    private static final int DECISION_CANCEL = -1;
    private static final int DECISION_CONFIRM = -2;
    private static final int DECISION_UP = -3;
    private static final int DECISION_DOWN = -4;
    private static final int DECISION_NONE = -5;

    static int handleKey(int key, int selected, int itemCount) {
        if (key == KEY_UP) {
            return DECISION_UP;
        }
        if (key == KEY_DOWN) {
            return DECISION_DOWN;
        }
        if (key == KEY_ESC || key < 0) {
            return DECISION_CANCEL;
        }
        if (key == '\r' || key == '\n') {
            return DECISION_CONFIRM;
        }
        if (key >= '1' && key <= '9') {
            int idx = key - '1';
            if (idx < itemCount) {
                return idx;
            }
        }
        if (key == 'k' || key == 'K') return DECISION_UP;
        if (key == 'j' || key == 'J') return DECISION_DOWN;
        if (key == 'q' || key == 'Q') return DECISION_CANCEL;
        return DECISION_NONE;
    }
}
