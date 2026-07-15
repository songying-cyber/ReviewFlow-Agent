package com.paicli.wechat;

import com.paicli.agent.Agent;
import com.paicli.config.PaiCliConfig;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClientFactory;
import com.paicli.render.Renderer;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class WechatAgentSession implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "paicli-wechat-agent");
        thread.setDaemon(true);
        return thread;
    });
    private final WechatTerminalRenderer renderer;
    private final Agent agent;
    private Future<String> running;
    private CancellationToken runningToken;

    public WechatAgentSession(WechatAccount account, WechatMessageSender sender) {
        this(account, sender, null);
    }

    public WechatAgentSession(WechatAccount account, WechatMessageSender sender, Renderer localRenderer) {
        Objects.requireNonNull(account, "account");
        PaiCliConfig config = PaiCliConfig.load();
        LlmClient client = LlmClientFactory.createFromConfig(config);
        if (client == null) {
            throw new IllegalStateException("未找到可用的 API Key，无法启动微信 Agent 会话");
        }
        Path workspace = Path.of(account.workspace() == null || account.workspace().isBlank() ? "." : account.workspace())
                .toAbsolutePath().normalize();
        WechatPolicyConfig policyConfig = WechatPolicyConfig.forWorkspace(workspace);
        WechatToolRegistry registry = new WechatToolRegistry(new WechatPolicyDecider(policyConfig));
        registry.setProjectPath(workspace.toString());
        this.renderer = new WechatTerminalRenderer(localRenderer, sender);
        this.agent = new Agent(client, registry);
        this.agent.setRenderer(renderer);
        this.agent.setReturnFinalResponseWhenStreamed(true);
    }

    public synchronized boolean isRunning() {
        return running != null && !running.isDone();
    }

    public synchronized boolean hasCompletedRun() {
        return running != null && running.isDone();
    }

    public synchronized Future<String> submit(String prompt) {
        if (isRunning()) {
            throw new IllegalStateException("当前已有微信任务在运行");
        }
        renderer.resetWechatStream();
        runningToken = CancellationContext.startRun();
        Callable<String> task = () -> agent.run(prompt);
        running = executor.submit(task);
        return running;
    }

    public synchronized String awaitCurrent() {
        if (running == null) {
            return "";
        }
        try {
            String result = running.get();
            if (renderer.consumeSentContentFlag()) {
                return "";
            }
            return result == null ? "" : result;
        } catch (CancellationException e) {
            return "已取消当前任务。";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "当前任务被中断。";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return "执行失败: " + (cause == null || cause.getMessage() == null ? "未知错误" : cause.getMessage());
        } finally {
            if (runningToken != null) {
                CancellationContext.clear(runningToken);
                runningToken = null;
            }
            running = null;
        }
    }

    public synchronized void cancel() {
        if (runningToken != null) {
            runningToken.cancel();
        }
        if (running != null) {
            running.cancel(true);
        }
    }

    public void clear() {
        agent.clearHistory();
    }

    public String compact() {
        Agent.CompactionResult result = agent.compactHistoryNow();
        if (result.error() != null && !result.error().isBlank()) {
            return "手动压缩失败: " + result.error();
        }
        if (result.compacted()) {
            return "已手动压缩历史上下文: " + result.beforeTokens() + " -> " + result.afterTokens() + " tokens";
        }
        return "当前没有需要压缩的历史上下文";
    }

    public String status() {
        return agent.currentStatus(isRunning() ? "running" : "idle").toString();
    }

    @Override
    public void close() {
        cancel();
        executor.shutdownNow();
    }
}
