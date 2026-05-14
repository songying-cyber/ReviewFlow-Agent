package com.paicli.tui.pane;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.LinearLayout.Alignment;
import com.googlecode.lanterna.gui2.LinearLayout.GrowPolicy;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.paicli.llm.LlmClient;

import java.util.function.Consumer;

/**
 * 底部输入栏。
 *
 * <p>职责：
 * - 接收用户输入（单行，Shift+Enter 多行）
 * - Enter 提交，回调给 RootPane → CenterPane
 * - ↑↓ 输入历史
 * - Tab 自动补全（`/` 命令 + `@` resource，复用 AtMentionCompleter）
 * - Esc 清空
 * - Ctrl+K 清空
 *
 * <p>Day 2 实现：Enter 提交，Esc 清空
 */
public class InputBar extends Panel {

    private final LlmClient llmClient;
    private final Consumer<String> onMessage;
    private final TextBox inputBox;

    /**
     * 创建输入栏。
     *
     * @param config    配置（暂未使用）
     * @param llmClient LLM 客户端
     * @param onMessage 用户消息回调
     */
    public InputBar(com.paicli.config.PaiCliConfig config, LlmClient llmClient, Consumer<String> onMessage) {
        super();
        this.llmClient = llmClient;
        this.onMessage = onMessage;

        setLayoutManager(new LinearLayout(Direction.HORIZONTAL));

        // 输入框（自定义 TextBox 处理 Enter/Esc 快捷键）
        this.inputBox = new TextBox() {
            @Override
            public Interactable.Result handleKeyStroke(KeyStroke keyStroke) {
                if (keyStroke.getKeyType() == KeyType.Enter) {
                    submit();
                    return Interactable.Result.HANDLED;
                } else if (keyStroke.getKeyType() == KeyType.Escape) {
                    clear();
                    return Interactable.Result.HANDLED;
                }
                return super.handleKeyStroke(keyStroke);
            }
        };
        inputBox.setPreferredSize(new TerminalSize(80, 3));

        addComponent(inputBox.setLayoutData(
                LinearLayout.createLayoutData(Alignment.Fill, GrowPolicy.CanGrow)));
    }

    /**
     * 提交消息。
     */
    private void submit() {
        String text = inputBox.getText().trim();
        if (!text.isEmpty()) {
            onMessage.accept(text);
            inputBox.setText("");
        }
    }

    /**
     * 获取当前输入内容。
     */
    public String getText() {
        return inputBox.getText();
    }

    /**
     * 清空输入框。
     */
    public void clear() {
        inputBox.setText("");
    }
}
