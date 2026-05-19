package com.paicli.render;

import com.paicli.render.inline.InlineRenderer;
import com.paicli.render.inline.TerminalCapabilities;
import org.jline.terminal.Terminal;

/**
 * 启动时根据环境变量选择渲染器形态。
 *
 * <p>选型规则：
 * <ul>
 *   <li>{@code -Dpaicli.renderer} > {@code PAICLI_RENDERER} 环境变量 > 默认 inline</li>
 *   <li>{@code lanterna} → Lanterna 全屏 TUI（由 {@code TuiBootstrap} 在 CLI 循环前接管）</li>
 *   <li>{@code plain} → {@link PlainRenderer}</li>
 *   <li>{@code inline}（默认）→ Inline 流式（Day 2 落地，先返回 plain 占位）</li>
 *   <li>兼容：{@code PAICLI_TUI=true} → 等价 {@code lanterna}，打 deprecation 提示</li>
 * </ul>
 *
 * <p>当 inline 目标渲染器初始化失败（如终端不支持 ANSI），自动 fallback 到
 * {@link PlainRenderer}，并在 stderr 打日志。Lanterna 模式不经由本工厂创建，
 * 而是在 {@code Main} 里进入 {@code TuiBootstrap}。
 */
public final class RendererFactory {

    public enum Mode {
        INLINE, LANTERNA, PLAIN
    }

    private RendererFactory() {
    }

    public static Mode resolveMode() {
        String prop = System.getProperty("paicli.renderer");
        if (prop != null && !prop.isBlank()) {
            return parse(prop);
        }
        String env = System.getenv("PAICLI_RENDERER");
        if (env != null && !env.isBlank()) {
            return parse(env);
        }
        // 兼容旧 PAICLI_TUI=true → lanterna
        String legacyTui = System.getenv("PAICLI_TUI");
        if (legacyTui != null && Boolean.parseBoolean(legacyTui.trim())) {
            System.err.println("⚠️ PAICLI_TUI=true 已废弃，请改用 PAICLI_RENDERER=lanterna");
            return Mode.LANTERNA;
        }
        return Mode.INLINE;
    }

    private static Mode parse(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case "lanterna", "tui" -> Mode.LANTERNA;
            case "plain" -> Mode.PLAIN;
            case "inline" -> Mode.INLINE;
            default -> {
                System.err.println("⚠️ 未识别的 PAICLI_RENDERER='" + raw + "'，回退到 inline");
                yield Mode.INLINE;
            }
        };
    }

    /**
     * 创建 CLI 循环内使用的渲染器。inline 模式如果终端不支持 ANSI（如 dumb 终端），
     * 回退到 plain。lanterna 模式应在进入 CLI 循环前由 TuiBootstrap 接管；如果走到
     * 这里，说明 TUI 因 NO_TUI、终端尺寸或启动失败被降级。
     *
     * @param terminal JLine terminal，用于 inline / lanterna 模式探测能力。可为 null。
     */
    public static Renderer create(Mode mode, Terminal terminal) {
        return switch (mode) {
            case PLAIN -> new PlainRenderer();
            case INLINE -> {
                if (TerminalCapabilities.supportsAnsi(terminal)) {
                    yield new InlineRenderer(terminal);
                }
                System.err.println("⚠️ 终端不支持 ANSI，inline 模式回退到 plain");
                yield new PlainRenderer();
            }
            case LANTERNA -> new PlainRenderer();    // Day 5 后替换为 LanternaRenderer
        };
    }
}
