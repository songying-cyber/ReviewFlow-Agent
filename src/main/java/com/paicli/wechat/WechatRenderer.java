package com.paicli.wechat;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

public class WechatRenderer implements Renderer {
    static final int MAX_CHARS = 3800;
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\u001B\\[[;?0-9]*[ -/]*[@-~]");

    private final WechatMessageSender sender;
    private final PrintStream stream;
    private final StringBuilder buffer = new StringBuilder();

    public WechatRenderer(WechatMessageSender sender) {
        this.sender = sender;
        this.stream = new PrintStream(new WechatOutputStream(), true, StandardCharsets.UTF_8);
    }

    @Override
    public void start() {
    }

    @Override
    public void close() {
        flushBuffer();
    }

    @Override
    public PrintStream stream() {
        return stream;
    }

    @Override
    public boolean rendersReasoning() {
        return false;
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        // 微信侧只发最终结果；工具调用进度留在 PaiCLI 进程日志 / 本地终端。
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        // 远程通道不外发 diff 细节，避免把大段代码刷到微信；详细内容留给审计和本地日志。
    }

    @Override
    public void updateStatus(StatusInfo status) {
        // 微信无底部状态栏。
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        return ApprovalResult.reject("微信通道不支持交互式 HITL 审批");
    }

    @Override
    public int openPalette(String title, List<String> items) {
        return -1;
    }

    public synchronized void appendLine(String line) {
        append(line == null ? "" : line + "\n");
    }

    public synchronized void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        buffer.append(text);
    }

    public synchronized int pendingChars() {
        return buffer.length();
    }

    public synchronized String pendingText() {
        return buffer.toString();
    }

    public synchronized void flushBuffer() {
        String captured = filterMarkdown(buffer.toString()).trim();
        buffer.setLength(0);
        if (captured.isBlank()) {
            return;
        }
        for (String chunk : split(captured)) {
            try {
                sender.send(chunk);
            } catch (IOException e) {
                throw new IllegalStateException("微信消息发送失败: " + e.getMessage(), e);
            }
        }
    }

    static String filterMarkdown(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return WechatTextFormatter.format(text);
    }

    static String stripAnsi(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }

    private static List<String> split(String text) {
        java.util.ArrayList<String> chunks = new java.util.ArrayList<>();
        String remaining = text;
        while (remaining.length() > MAX_CHARS) {
            int split = remaining.lastIndexOf('\n', MAX_CHARS);
            if (split < MAX_CHARS / 3) {
                split = remaining.lastIndexOf(' ', MAX_CHARS);
            }
            if (split < MAX_CHARS / 3) {
                split = MAX_CHARS;
            }
            chunks.add(remaining.substring(0, split).trim());
            remaining = remaining.substring(split).trim();
        }
        if (!remaining.isBlank()) {
            chunks.add(remaining);
        }
        return chunks;
    }

    private final class WechatOutputStream extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void write(int b) {
            bytes.write(b);
            if (b == '\n') {
                drain();
            }
        }

        @Override
        public void flush() {
            drain();
        }

        private void drain() {
            if (bytes.size() == 0) {
                return;
            }
            append(bytes.toString(StandardCharsets.UTF_8));
            bytes.reset();
        }
    }
}
