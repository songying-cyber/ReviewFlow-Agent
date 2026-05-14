package com.paicli.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.paicli.config.PaiCliConfig;
import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.util.Objects;

/**
 * Lanterna 窗口管理器。
 *
 * <p>职责：
 * - 创建并管理 Lanterna Screen 和 GUI
 * - 创建 RootPane（主窗口）
 * - 处理窗口大小变化
 * - 提供线程安全的 GUI 调度
 */
public final class LanternaWindow {

    private final Screen screen;
    private final WindowBasedTextGUI gui;
    private final LlmClient llmClient;
    private final PaiCliConfig config;
    private final BasicWindow mainWindow;
    private final RootPane rootPane;
    private Runnable closeHook = () -> {
    };

    /**
     * 是否处于 TUI 模式（静态标志）。
     */
    private static volatile boolean tuiMode = false;

    /**
     * 创建 Lanterna 窗口。
     *
     * @param config    配置
     * @param llmClient LLM 客户端
     * @throws IOException 如果终端初始化失败
     */
    public LanternaWindow(PaiCliConfig config, LlmClient llmClient) throws IOException {
        this.config = Objects.requireNonNull(config);
        this.llmClient = Objects.requireNonNull(llmClient);

        // 创建 Terminal + Screen
        DefaultTerminalFactory terminalFactory = new DefaultTerminalFactory();
        this.screen = new TerminalScreen(terminalFactory.createTerminal());
        this.screen.startScreen();

        // 创建 GUI 层（使用默认主题）
        this.gui = new MultiWindowTextGUI(screen);

        // 创建根面板（三栏布局）
        this.rootPane = new RootPane(config, llmClient);
        this.mainWindow = new BasicWindow("PaiCLI v16.0.0");
        mainWindow.setComponent(rootPane);
        gui.addWindow(mainWindow);

        tuiMode = true;
    }

    /**
     * 启动 TUI 主循环（阻塞直到窗口关闭）。
     */
    public void start() {
        try {
            gui.waitForWindowToClose(mainWindow);
        } catch (Exception e) {
            System.err.println("❌ TUI 主循环异常: " + e.getMessage());
        } finally {
            closeHook.run();
            tuiMode = false;
            closeScreen();
        }
    }

    /**
     * 关闭窗口。
     */
    public void close() {
        mainWindow.close();
        closeScreen();
        tuiMode = false;
    }

    private void closeScreen() {
        try {
            if (screen != null) {
                screen.stopScreen();
            }
        } catch (IOException e) {
            System.err.println("⚠️ 关闭屏幕失败: " + e.getMessage());
        }
    }

    public void setCloseHook(Runnable closeHook) {
        this.closeHook = closeHook == null ? () -> {
        } : closeHook;
    }

    /**
     * 当前是否处于 TUI 模式。
     */
    public static boolean isTuiMode() {
        return tuiMode;
    }

    /**
     * 在 GUI 事件线程执行任务（线程安全封装）。
     *
     * @param task 要执行的任务
     */
    public void runOnGuiThread(Runnable task) {
        Objects.requireNonNull(task);
        if (gui != null) {
            gui.getGUIThread().invokeLater(task);
        }
    }

    public Screen getScreen() {
        return screen;
    }

    public WindowBasedTextGUI getGui() {
        return gui;
    }

    public RootPane getRootPane() {
        return rootPane;
    }

    public LlmClient getLlmClient() {
        return llmClient;
    }

    public PaiCliConfig getConfig() {
        return config;
    }
}
