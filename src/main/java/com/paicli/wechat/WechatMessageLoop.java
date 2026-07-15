package com.paicli.wechat;

import com.paicli.render.PlainRenderer;
import com.paicli.render.Renderer;
import com.paicli.util.AnsiStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class WechatMessageLoop {
    private static final Logger log = LoggerFactory.getLogger(WechatMessageLoop.class);
    private static final int SESSION_EXPIRED = -14;
    private static final long TYPING_REFRESH_NANOS = 5_000_000_000L;

    private final IlinkClient client;
    private final WechatAccountStore store;
    private final Renderer localRenderer;
    private WechatAccount account;
    private WechatAgentSession session;
    private final Queue<WechatMessage> queue = new ArrayDeque<>();
    private final Set<String> seenMessageIds = new HashSet<>();
    private boolean paused;
    private boolean stopped;
    private volatile String activeContextToken = "";
    private long lastTypingNanos;

    public WechatMessageLoop(IlinkClient client, WechatAccountStore store, WechatAccount account) {
        this(client, store, account, null);
    }

    public WechatMessageLoop(IlinkClient client, WechatAccountStore store, WechatAccount account, Renderer localRenderer) {
        this.client = client == null ? new IlinkClient() : client;
        this.store = store == null ? WechatAccountStore.createDefault() : store;
        this.account = account;
        this.localRenderer = localRenderer == null ? new PlainRenderer() : localRenderer;
    }

    public void run() {
        if (account == null) {
            throw new IllegalStateException("未找到微信账号，请先执行 paicli wechat setup");
        }
        try {
            client.notifyStart(account);
        } catch (Exception e) {
            log.warn("notifyStart failed: {}", e.getMessage());
        }
        try (WechatAgentSession agentSession = new WechatAgentSession(account,
                text -> send(text),
                localRenderer)) {
            this.session = agentSession;
            long timeoutMs = 35_000;
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                try {
                    completeCurrentIfDone();
                    drainQueue();
                    refreshTypingIfNeeded();
                    long pollTimeoutMs = session != null && session.isRunning()
                            ? Math.min(timeoutMs, 3_000)
                            : timeoutMs;
                    WechatUpdate update = client.getUpdates(account, pollTimeoutMs);
                    if (update.ret() == SESSION_EXPIRED) {
                        log.warn("WeChat iLink session expired; please run setup again");
                        sleep(60_000);
                        continue;
                    }
                    if (update.nextLongPollTimeoutMs() != null && update.nextLongPollTimeoutMs() > 0) {
                        timeoutMs = update.nextLongPollTimeoutMs();
                    }
                    if (update.nextSyncBuf() != null && !update.nextSyncBuf().isBlank()) {
                        account = account.withSyncBuf(update.nextSyncBuf());
                        store.save(account);
                    }
                    for (WechatMessage message : update.messages()) {
                        handle(message);
                    }
                    completeCurrentIfDone();
                    drainQueue();
                } catch (Exception e) {
                    log.warn("WeChat loop error: {}", e.getMessage());
                    sleep(3_000);
                }
            }
        } finally {
            try {
                client.notifyStop(account);
            } catch (Exception e) {
                log.warn("notifyStop failed: {}", e.getMessage());
            }
        }
    }

    public void stop() {
        stopped = true;
        if (session != null) {
            session.cancel();
        }
    }

    private String currentContextToken() {
        return activeContextToken == null ? "" : activeContextToken;
    }

    private void handle(WechatMessage message) throws IOException {
        if (message == null || message.fromUserId() == null || message.fromUserId().isBlank()) {
            return;
        }
        if (message.messageId() != null && !message.messageId().isBlank() && !seenMessageIds.add(message.messageId())) {
            return;
        }
        if (!message.fromUserId().equals(account.boundUserId())) {
            log.warn("drop unbound wechat message from={}", mask(message.fromUserId()));
            return;
        }
        renderIncoming(message);
        WechatCommandParser.Command command = WechatCommandParser.parse(message.text());
        if (command.bypassQueue()) {
            handleBypass(command, message.contextToken());
            return;
        }
        queue.add(message);
    }

    private void handleBypass(WechatCommandParser.Command command, String contextToken) throws IOException {
        switch (command.type()) {
            case HELP -> send(helpText(), contextToken);
            case STATUS -> send("微信通道状态: " + (paused ? "paused" : "running")
                    + "\n队列: " + queue.size()
                    + "\nAgent: " + (session != null && session.isRunning() ? "running" : "idle"), contextToken);
            case PAUSE -> {
                paused = true;
                send("微信通道已暂停，普通消息会继续排队。", contextToken);
            }
            case RESUME -> {
                paused = false;
                send("微信通道已恢复。", contextToken);
                drainQueue();
            }
            case STOP -> {
                if (session != null) {
                    session.cancel();
                }
                stopTyping(contextToken);
                send("已请求取消当前任务。", contextToken);
            }
            case UNKNOWN -> send("未知微信命令: " + command.payload() + "\n发送 /help 查看可用命令。", contextToken);
            default -> queue.add(new WechatMessage("", account.boundUserId(), contextToken, "/" + command.type().name().toLowerCase(), List.of()));
        }
    }

    private void drainQueue() throws IOException {
        if (paused || session == null || session.isRunning()) {
            return;
        }
        WechatMessage message = queue.poll();
        if (message == null) {
            return;
        }
        activeContextToken = safeContextToken(message.contextToken());
        WechatCommandParser.Command command = WechatCommandParser.parse(message.text());
        String prompt = message.text();
        switch (command.type()) {
            case CLEAR -> {
                session.clear();
                send("当前微信会话历史已清空，长期记忆保持不变。");
                drainQueue();
                return;
            }
            case COMPACT -> {
                send(session.compact());
                drainQueue();
                return;
            }
            case CWD -> {
                send("`/cwd` v1 只允许在 setup workspace 内切换，当前 MVP 请重新 setup 指定工作区。");
                drainQueue();
                return;
            }
            case SEND -> {
                send("`/send` 文件推送将在媒体上传链路完成后启用。");
                drainQueue();
                return;
            }
            case MODEL -> {
                send("`/model` 微信侧切换将在模型配置命令抽取后启用；当前使用 PaiCLI 默认模型。");
                drainQueue();
                return;
            }
            case UNKNOWN -> {
                send("未知微信命令: " + command.payload());
                drainQueue();
                return;
            }
            case NONE -> {
            }
            default -> {
                send("该命令不支持排队执行，请发送 /help 查看可用命令。");
                drainQueue();
                return;
            }
        }
        if (!message.mediaItems().isEmpty()) {
            prompt += "\n\n用户发送了媒体文件，当前 MVP 已收到媒体元数据；图片/文件下载链路将在 CDN 解密模块完成后启用。";
        }
        startTyping();
        session.submit(prompt);
    }

    private void completeCurrentIfDone() throws IOException {
        if (session == null || !session.hasCompletedRun()) {
            return;
        }
        String result = session.awaitCurrent();
        stopTyping();
        if (result != null && !result.isBlank()) {
            send(result);
        }
        drainQueue();
    }

    private void send(String text) throws IOException {
        send(text, currentContextToken());
    }

    private void send(String text, String contextToken) throws IOException {
        String token = safeContextToken(contextToken);
        WechatRenderer renderer = new WechatRenderer(chunk -> client.sendText(account, account.boundUserId(), token, chunk));
        renderer.append(text);
        renderer.flushBuffer();
    }

    private void startTyping() {
        setTyping(currentContextToken(), true);
    }

    private void stopTyping() {
        stopTyping(currentContextToken());
    }

    private void stopTyping(String contextToken) {
        setTyping(contextToken, false);
    }

    private void refreshTypingIfNeeded() {
        if (session == null || !session.isRunning()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastTypingNanos >= TYPING_REFRESH_NANOS) {
            setTyping(currentContextToken(), true);
        }
    }

    private void setTyping(String contextToken, boolean typing) {
        try {
            client.sendTyping(account, account.boundUserId(), safeContextToken(contextToken), typing ? 1 : 2);
            lastTypingNanos = typing ? System.nanoTime() : 0L;
        } catch (Exception e) {
            log.debug("sendTyping failed: {}", e.getMessage());
        }
    }

    private static String safeContextToken(String contextToken) {
        return contextToken == null ? "" : contextToken;
    }

    private void renderIncoming(WechatMessage message) {
        if (localRenderer == null || message == null) {
            return;
        }
        String text = message.text() == null ? "" : message.text().trim();
        if (text.isBlank() && !message.mediaItems().isEmpty()) {
            text = "[媒体消息 " + message.mediaItems().size() + " 个]";
        }
        if (text.isBlank()) {
            return;
        }
        localRenderer.beginTurn();
        localRenderer.stream().println(AnsiStyle.userMessageBlock("微信 > " + text, localRenderer.terminalColumns()));
    }

    private static String helpText() {
        return """
                PaiCLI 微信通道命令：
                /help      查看帮助
                /status    查看状态
                /clear     清空当前微信会话
                /compact   压缩当前上下文
                /pause     暂停普通消息消费
                /resume    恢复消息消费
                /stop      取消当前任务

                安全策略：微信通道使用非交互式默认拒绝策略；execute_command 和 MCP 默认拒绝，写文件受 workspace PathGuard 限制。
                """.trim();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String mask(String id) {
        if (id == null || id.length() < 8) {
            return "***";
        }
        return id.substring(0, 4) + "***" + id.substring(id.length() - 4);
    }
}
