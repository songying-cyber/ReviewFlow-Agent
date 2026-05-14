package com.paicli.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;

/**
 * 终端能力探测：决定 inline 渲染器的各项特性是否可启用。
 *
 * <p>探测逻辑保守——能开则开，老终端 / 非 TTY 环境优雅降级。
 */
public final class TerminalCapabilities {

    private TerminalCapabilities() {
    }

    /** 终端是否能渲染 ANSI 转义序列（颜色、光标控制、滚动区域等）。 */
    public static boolean supportsAnsi(Terminal terminal) {
        if (terminal == null) {
            return false;
        }
        String type = terminal.getType();
        if (type != null && type.equalsIgnoreCase("dumb")) {
            return false;
        }
        if (System.getenv("NO_COLOR") != null) {
            // NO_COLOR 只影响样式，不影响光标控制——保留 true，颜色由 AnsiStyle 自己关
            return true;
        }
        String envTerm = System.getenv("TERM");
        return envTerm == null || !envTerm.equalsIgnoreCase("dumb");
    }

    /**
     * 终端是否支持 DECSTBM 滚动区域（底部常驻状态栏依赖）。
     * 同时校验终端尺寸合理（rows ≥ 5）。
     */
    public static boolean supportsScrollRegion(Terminal terminal) {
        if (!supportsAnsi(terminal)) {
            return false;
        }
        if (Boolean.parseBoolean(System.getenv("PAICLI_NO_STATUSBAR"))) {
            return false;
        }
        if (Boolean.parseBoolean(System.getProperty("paicli.no.statusbar"))) {
            return false;
        }
        Size size = safeSize(terminal);
        return size.getRows() >= 5 && size.getColumns() >= 20;
    }

    /** 终端是否支持 24-bit TrueColor（用于丰富的代码高亮等）。 */
    public static boolean supportsTrueColor() {
        String colorterm = System.getenv("COLORTERM");
        return "truecolor".equalsIgnoreCase(colorterm) || "24bit".equalsIgnoreCase(colorterm);
    }

    public static Size safeSize(Terminal terminal) {
        try {
            Size s = terminal.getSize();
            if (s == null || s.getRows() <= 0 || s.getColumns() <= 0) {
                return new Size(80, 24);
            }
            return s;
        } catch (Exception e) {
            return new Size(80, 24);
        }
    }
}
