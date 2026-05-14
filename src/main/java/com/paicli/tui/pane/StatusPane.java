package com.paicli.tui.pane;

import com.googlecode.lanterna.gui2.*;
import com.paicli.llm.LlmClient;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 右侧状态栏面板。
 *
 * <p>显示：
 * - 模型名称 / 提供商
 * - Token 使用量（已用 / 预算）
 * - 任务耗时
 * - 当前模式（ReAct / Plan / Team）
 * - 快捷键提示（精简版）
 *
 * <p>由 TUI 会话控制器在任务开始、结束和 token 变化时更新。
 */
public class StatusPane extends Panel {

    private final LlmClient llmClient;
    private final Label modelLabel;
    private final Label tokenLabel;
    private final Label modeLabel;
    private final Label timeLabel;
    private final AtomicLong taskStartTime = new AtomicLong(0);

    /**
     * 创建状态栏面板。
     *
     * @param config    配置（暂未使用）
     * @param llmClient LLM 客户端
     */
    public StatusPane(com.paicli.config.PaiCliConfig config, LlmClient llmClient) {
        super();
        this.llmClient = llmClient;

        setLayoutManager(new LinearLayout(Direction.VERTICAL));

        // 模型信息
        this.modelLabel = new Label("🤖 " + (llmClient != null ? llmClient.getModelName() : "?"));
        this.tokenLabel = new Label("💡 --");
        this.modeLabel = new Label("🔄 ReAct");
        this.timeLabel = new Label("⏱ --");

        addComponent(modelLabel);
        addComponent(tokenLabel);
        addComponent(modeLabel);
        addComponent(timeLabel);
    }

    /**
     * 更新 Token 使用量。
     *
     * @param used    已用 token
     * @param budget  总预算
     * @param cached  缓存的 token
     */
    public void updateTokenUsage(long used, long budget, long cached) {
        tokenLabel.setText(String.format("💡 %d/%d", used, budget));
        if (cached > 0) {
            tokenLabel.setText(tokenLabel.getText() + String.format(" (cached: %d)", cached));
        }
    }

    /**
     * 更新当前模式。
     *
     * @param mode 模式（ReAct / Plan / Team）
     */
    public void updateMode(String mode) {
        modeLabel.setText("🔄 " + (mode != null ? mode : "ReAct"));
    }

    /**
     * 开始计时（任务开始时调用）。
     */
    public void startTimer() {
        taskStartTime.set(System.currentTimeMillis());
    }

    /**
     * 停止计时并更新显示。
     */
    public void stopTimer() {
        if (taskStartTime.get() > 0) {
            long elapsedMs = System.currentTimeMillis() - taskStartTime.get();
            timeLabel.setText(String.format("⏱ %.1fs", elapsedMs / 1000.0));
            taskStartTime.set(0);
        }
    }
}
