package com.paicli.wechat;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.PlainRenderer;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;

import java.io.PrintStream;
import java.util.List;

public class WechatTerminalRenderer implements Renderer {
    private static final int STREAM_FLUSH_CHARS = 180;
    private static final long STREAM_FLUSH_NANOS = 1_200_000_000L;

    private final Renderer delegate;
    private final WechatRenderer wechatRenderer;
    private boolean sentContent;
    private long lastFlushNanos;

    public WechatTerminalRenderer(Renderer delegate, WechatMessageSender sender) {
        this.delegate = delegate == null ? new PlainRenderer() : delegate;
        this.wechatRenderer = new WechatRenderer(sender);
    }

    public synchronized void resetWechatStream() {
        wechatRenderer.flushBuffer();
        sentContent = false;
        lastFlushNanos = 0L;
    }

    public synchronized boolean consumeSentContentFlag() {
        boolean sent = sentContent;
        sentContent = false;
        return sent;
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void beginTurn() {
        delegate.beginTurn();
    }

    @Override
    public void beforeInput() {
        delegate.beforeInput();
    }

    @Override
    public void afterInput() {
        delegate.afterInput();
    }

    @Override
    public boolean supportsThinkingPanel() {
        return delegate.supportsThinkingPanel();
    }

    @Override
    public boolean rendersReasoning() {
        return delegate.rendersReasoning();
    }

    @Override
    public void beginThinking(String label) {
        delegate.beginThinking(label);
    }

    @Override
    public void appendThinking(String delta) {
        delegate.appendThinking(delta);
    }

    @Override
    public void endThinking() {
        delegate.endThinking();
    }

    @Override
    public boolean supportsActivityPanel() {
        return delegate.supportsActivityPanel();
    }

    @Override
    public void beginActivity(String label, String detail) {
        delegate.beginActivity(label, detail);
    }

    @Override
    public void endActivity() {
        delegate.endActivity();
    }

    @Override
    public String inputPrompt() {
        return delegate.inputPrompt();
    }

    @Override
    public String inputRightPrompt() {
        return delegate.inputRightPrompt();
    }

    @Override
    public void close() {
        finishAssistantContent();
    }

    @Override
    public PrintStream stream() {
        return delegate.stream();
    }

    @Override
    public int terminalColumns() {
        return delegate.terminalColumns();
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        delegate.appendToolCalls(toolCalls);
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        delegate.appendDiff(filePath, before, after);
    }

    @Override
    public void updateStatus(StatusInfo status) {
        delegate.updateStatus(status);
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        return delegate.promptApproval(request);
    }

    @Override
    public int openPalette(String title, List<String> items) {
        return delegate.openPalette(title, items);
    }

    @Override
    public synchronized void appendAssistantContentDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        wechatRenderer.append(delta);
        long now = System.nanoTime();
        if (shouldFlush(now)) {
            flushWechat();
        }
    }

    @Override
    public synchronized void finishAssistantContent() {
        flushWechat();
    }

    private boolean shouldFlush(long now) {
        int pending = wechatRenderer.pendingChars();
        if (pending >= STREAM_FLUSH_CHARS) {
            return true;
        }
        return pending >= 60 && lastFlushNanos > 0 && now - lastFlushNanos >= STREAM_FLUSH_NANOS;
    }

    private void flushWechat() {
        if (wechatRenderer.pendingChars() <= 0) {
            return;
        }
        wechatRenderer.flushBuffer();
        sentContent = true;
        lastFlushNanos = System.nanoTime();
    }
}
