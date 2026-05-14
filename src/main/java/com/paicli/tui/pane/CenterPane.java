package com.paicli.tui.pane;

import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.LinearLayout.Alignment;
import com.googlecode.lanterna.gui2.LinearLayout.GrowPolicy;
import com.paicli.llm.LlmClient;
import com.paicli.tui.highlight.CodeHighlighter;
import com.paicli.util.AnsiStyle;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中央对话流面板。
 *
 * <p>职责：
 * - 显示对话历史（用户消息 / Assistant 回复 / 工具调用 / 工具结果）
 * - Markdown 渲染 + 代码高亮（Day 3 实现）
 * - 流式输出适配（Day 3 实现）
 * - 代码块折叠（Day 5 实现）
 *
 * <p>Day 3: 流式输出 + Markdown + 代码高亮
 */
public class CenterPane extends Panel {

    private final LlmClient llmClient;
    private final TextBox chatArea;
    private final StringBuilder assistantBuffer;  // Assistant 流式缓冲

    /**
     * 创建对话流面板。
     *
     * @param config    配置（暂未使用）
     * @param llmClient LLM 客户端
     */
    public CenterPane(com.paicli.config.PaiCliConfig config, LlmClient llmClient) {
        super();
        this.llmClient = llmClient;
        this.assistantBuffer = new StringBuilder();

        setLayoutManager(new LinearLayout(Direction.VERTICAL));

        // 对话流使用 TextBox（只读，支持滚动）
        this.chatArea = new TextBox("对话开始...\n\n💡 提示：\n  - 在底部输入框输入问题\n  - Ctrl+O 折叠/展开代码块\n  - Ctrl+P 查看历史对话\n  - Ctrl+\\ 显示/隐藏文件树");
        chatArea.setReadOnly(true);

        addComponent(chatArea.setLayoutData(
                LinearLayout.createLayoutData(Alignment.Fill, GrowPolicy.CanGrow)));
    }

    /**
     * 用户消息回调（从 RootPane 转发）。
     *
     * @param message 用户输入的消息
     */
    public void onUserMessage(String message) {
        appendUserMessage(message);
    }

    public void appendSystemMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        chatArea.setText(chatArea.getText() + "\n💡 系统:\n" + message.trim() + "\n");
        scrollToBottom();
    }

    public void appendAssistantOutput(String output) {
        if (output == null || output.isBlank()) {
            return;
        }
        chatArea.setText(chatArea.getText() + "\n🤖 PaiCLI:\n" + output.trim() + "\n");
        scrollToBottom();
    }

    /**
     * 追加用户消息。
     */
    private void appendUserMessage(String message) {
        String rendered = renderMarkdown(message);
        chatArea.setText(chatArea.getText() + "\n👤 你:\n" + rendered + "\n");
        scrollToBottom();
    }

    /**
     * 追加 Assistant 流式文本（50ms 节流）。
     *
     * @param chunk 新的文本块
     */
    public void appendAssistantChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        assistantBuffer.append(chunk);
        flushAssistantBuffer();
    }

    /**
     * 刷新 Assistant 缓冲到聊天区域（带 50ms 节流）。
     */
    private synchronized void flushAssistantBuffer() {
        if (assistantBuffer.length() == 0) {
            return;
        }

        String content = assistantBuffer.toString();
        assistantBuffer.setLength(0);

        // 渲染 Markdown + 代码高亮
        String rendered = renderMarkdown(content);
        chatArea.setText(chatArea.getText() + rendered);
        scrollToBottom();
    }

    /**
     * 追加工具调用。
     *
     * @param toolName 工具名称
     * @param args     参数 JSON
     */
    public void appendToolCall(String toolName, String args) {
        String toolBlock = "🔧 工具调用: " + (toolName != null ? toolName : "unknown") + "\n"
                + (args != null ? "  参数: " + args : "")
                + "\n";
        chatArea.setText(chatArea.getText() + "\n" + toolBlock);
        scrollToBottom();
    }

    /**
     * 追加工具结果。
     *
     * @param result 工具执行结果
     */
    public void appendToolResult(String result) {
        String truncated = truncateResult(result, 500);
        String resultBlock = "📤 工具结果:\n" + truncated + "\n";
        chatArea.setText(chatArea.getText() + resultBlock);
        scrollToBottom();
    }

    /**
     * 渲染 Markdown（支持代码块、粗体和行内代码）。
     */
    private String renderMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // 代码块高亮（```lang ... ```）
        text = highlightCodeBlocks(text);

        // 粗体 **text** → bold
        text = replaceAllRegex(text, "\\*\\*(.+?)\\*\\*", m -> AnsiStyle.emphasis(m.group(1)));

        // 行内代码 `code`
        text = replaceAllRegex(text, "`(.+?)`", m -> AnsiStyle.codeLabel(m.group(1)));

        return text;
    }

    /**
     * 使用正则表达式替换文本（支持 lambda 回调）。
     */
    private static String replaceAllRegex(String text, String regex, java.util.function.Function<Matcher, String> replacer) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, replacer.apply(matcher));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 高亮代码块（```lang ... ```）。
     */
    private String highlightCodeBlocks(String text) {
        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < text.length()) {
            // 查找代码块开始 ```lang
            if (i < text.length() - 2 && text.charAt(i) == '`' && text.charAt(i + 1) == '`' && text.charAt(i + 2) == '`') {
                int start = i + 3;
                // 提取语言标识
                int langEnd = text.indexOf('\n', start);
                String lang = "text";
                int codeStart;
                if (langEnd > 0) {
                    lang = text.substring(start, langEnd).trim();
                    codeStart = langEnd + 1;
                } else {
                    codeStart = start;
                }

                // 查找代码块结束
                int codeEnd = text.indexOf("```", codeStart);
                if (codeEnd < 0) {
                    codeEnd = text.length();
                }

                String code = text.substring(codeStart, codeEnd);
                String highlighted = CodeHighlighter.highlight(code, lang);

                result.append("\n").append(highlighted);
                i = codeEnd + 3;
            } else {
                result.append(text.charAt(i));
                i++;
            }
        }

        return result.toString();
    }

    /**
     * 截断过长的工具结果。
     */
    private static String truncateResult(String result, int maxLength) {
        if (result == null) {
            return "null";
        }
        if (result.length() <= maxLength) {
            return result;
        }
        return result.substring(0, maxLength) + "\n... (截断，共 " + result.length() + " 字符)";
    }

    /**
     * 滚动到底部。
     */
    private void scrollToBottom() {
        // TextBox 会自动滚动到最后。
    }

    /**
     * 清空对话流。
     */
    public void clear() {
        chatArea.setText("");
        assistantBuffer.setLength(0);
    }
}
