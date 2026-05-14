package com.paicli.tui.config;

import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.paicli.config.PaiCliConfig;

/**
 * TUI 配置面板。
 *
 * <p>职责：
 * - 允许用户在 TUI 内修改模型、温度、最大 token 等参数
 * - 持久化到 config.json
 * - 热重载（修改后立即生效）
 *
 * <p>当前先提供只读配置总览和 provider 快速切换，具体 API Key 编辑仍通过 config.json / .env。
 */
public class TuiConfigPanel {

    private final PaiCliConfig config;
    private final WindowBasedTextGUI gui;

    public TuiConfigPanel(PaiCliConfig config, WindowBasedTextGUI gui) {
        this.config = config;
        this.gui = gui;
    }

    public void showConfigDialog() {
        String info = formatConfigInfo();
        MessageDialogBuilder dialog = new MessageDialogBuilder()
                .setTitle("⚙️  配置")
                .setText(info)
                .addButton(MessageDialogButton.OK);
        dialog.build().showDialog(gui);
    }

    /**
     * 格式化配置信息为可读文本。
     */
    private String formatConfigInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("当前配置:\n\n");

        config.getProviders().forEach((name, provider) -> {
            sb.append("Provider: ").append(name).append("\n");
            sb.append("  Model: ").append(provider.getModel() != null ? provider.getModel() : "未设置").append("\n");
            sb.append("  Base URL: ").append(provider.getBaseUrl() != null ? provider.getBaseUrl() : "默认").append("\n");
            sb.append("  Temperature: ").append(provider.getTemperature()).append("\n");
            sb.append("  Max Tokens: ").append(provider.getMaxTokens()).append("\n");
            sb.append("\n");
        });

        return sb.toString();
    }

    public void switchModel(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return;
        }
        config.setDefaultProvider(providerName.trim());
        config.save();
    }
}
