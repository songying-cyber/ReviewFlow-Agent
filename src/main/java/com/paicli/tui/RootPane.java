package com.paicli.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.LinearLayout.Alignment;
import com.googlecode.lanterna.gui2.LinearLayout.GrowPolicy;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.paicli.config.PaiCliConfig;
import com.paicli.llm.LlmClient;
import com.paicli.tui.pane.CenterPane;
import com.paicli.tui.pane.FileTreePane;
import com.paicli.tui.pane.InputBar;
import com.paicli.tui.pane.StatusPane;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 根面板容器，实现三栏布局。
 *
 * <p>布局结构：
 * <pre>
 * ┌─────────────────┬─────────────────────────────────────┬──────────┐
 * │  文件树面板      │  对话流面板                          │  状态栏   │
 * │  (FileTreePane) │  (CenterPane)                       │ (StatusPane)
 * │                 │                                     │          │
 * ├─────────────────┴─────────────────────────────────────┴──────────┤
 * │  输入栏 (InputBar)                                              │
 * └─────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class RootPane extends Panel {

    private final FileTreePane fileTreePane;
    private final CenterPane centerPane;
    private final StatusPane statusPane;
    private final InputBar inputBar;
    private final LlmClient llmClient;
    private final PaiCliConfig config;
    private Consumer<String> messageHandler;

    // 宽度比例（百分比）
    private static final double FILE_TREE_RATIO = 0.25;
    private static final double STATUS_RATIO = 0.10;

    // 文件树可见性状态
    private boolean fileTreeVisible = true;

    /**
     * 创建根面板。
     *
     * @param config    配置
     * @param llmClient LLM 客户端
     */
    public RootPane(PaiCliConfig config, LlmClient llmClient) {
        super();
        this.config = Objects.requireNonNull(config);
        this.llmClient = Objects.requireNonNull(llmClient);

        // 创建子面板
        this.fileTreePane = new FileTreePane(config);
        this.centerPane = new CenterPane(config, llmClient);
        this.statusPane = new StatusPane(config, llmClient);
        this.messageHandler = centerPane::onUserMessage;
        this.inputBar = new InputBar(config, llmClient, this::onUserMessage);

        // 设置主布局（垂直）
        setLayoutManager(new LinearLayout(Direction.VERTICAL));

        // 顶部面板容器（水平布局：文件树 + 对话流 + 状态栏）
        Panel topPanel = new Panel();
        topPanel.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));

        // 文件树（固定比例）
        topPanel.addComponent(fileTreePane.withBorder(Borders.singleLine("项目结构")));

        // 对话流（填充剩余）
        topPanel.addComponent(centerPane.withBorder(Borders.singleLine("对话")).setLayoutData(
                LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));

        // 状态栏（固定比例）
        topPanel.addComponent(statusPane.withBorder(Borders.singleLine("状态")).setLayoutData(
                LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));

        // 添加顶部面板（自适应填充）
        addComponent(topPanel.setLayoutData(
                LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));

        // 输入栏（固定高度）
        addComponent(inputBar.withBorder(Borders.singleLine("输入")).setLayoutData(
                LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));
    }

    /**
     * 窗口大小变化时的回调。
     *
     * @param newSize 新的终端尺寸
     */
    public void onResize(TerminalSize newSize) {
        if (newSize == null) {
            return;
        }

        int cols = newSize.getColumns();
        int rows = newSize.getRows();

        // 响应式调整：
        // - cols < 90: 文件树缩到 15 列
        // - cols < 80: 文件树隐藏（按 Ctrl+\ 可恢复）
        int fileTreeWidth;
        if (cols < 80) {
            fileTreeWidth = 0;  // 隐藏
        } else if (cols < 120) {
            fileTreeWidth = 15;
        } else {
            fileTreeWidth = (int) (cols * FILE_TREE_RATIO);
        }

        fileTreePane.setPreferredSize(new TerminalSize(fileTreeWidth, rows - 5));

        // 状态栏固定 20 列
        int statusWidth = Math.min(20, (int) (cols * STATUS_RATIO));
        statusPane.setPreferredSize(new TerminalSize(statusWidth, rows - 5));

        // 对话流填充剩余
        int centerWidth = cols - fileTreeWidth - statusWidth - 2;  // -2 for borders
        centerPane.setPreferredSize(new TerminalSize(Math.max(20, centerWidth), rows - 5));

        // 刷新布局
        invalidate();
    }

    /**
     * 用户消息回调（从 InputBar 转发到对话流）。
     *
     * @param message 用户输入的消息
     */
    public void onUserMessage(String message) {
        if (message != null && !message.trim().isEmpty()) {
            messageHandler.accept(message);
        }
    }

    public void setMessageHandler(Consumer<String> messageHandler) {
        this.messageHandler = messageHandler == null ? centerPane::onUserMessage : messageHandler;
    }

    public FileTreePane getFileTreePane() {
        return fileTreePane;
    }

    public CenterPane getCenterPane() {
        return centerPane;
    }

    public StatusPane getStatusPane() {
        return statusPane;
    }

    public InputBar getInputBar() {
        return inputBar;
    }

    /**
     * 切换文件树可见性。
     */
    public void toggleFileTree() {
        fileTreeVisible = !fileTreeVisible;
        fileTreePane.setVisible(fileTreeVisible);
        invalidate();
        centerPane.appendSystemMessage("文件树已" + (fileTreeVisible ? "显示" : "隐藏"));
    }

    /**
     * 处理键盘快捷键（Day 4 实现）。
     */
    @Override
    public boolean handleInput(KeyStroke keyStroke) {
        // Ctrl+O: 折叠/展开代码块
        if (keyStroke.getKeyType() == KeyType.Character && keyStroke.isCtrlDown() && keyStroke.getCharacter() == 'O') {
            centerPane.appendSystemMessage("代码块折叠快捷键已收到；当前版本保留完整代码输出。");
            return true;
        }

        // Ctrl+P: 查看历史对话
        if (keyStroke.getKeyType() == KeyType.Character && keyStroke.isCtrlDown() && keyStroke.getCharacter() == 'P') {
            centerPane.appendSystemMessage("对话历史已持续保存到 ~/.paicli/history/。");
            return true;
        }

        // Ctrl+\: 显示/隐藏文件树
        if (keyStroke.getKeyType() == KeyType.Character && keyStroke.isCtrlDown() && keyStroke.getCharacter() == '\\') {
            toggleFileTree();
            return true;
        }

        return super.handleInput(keyStroke);
    }
}
