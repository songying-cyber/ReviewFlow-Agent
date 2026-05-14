package com.paicli.hitl;

import com.paicli.render.Renderer;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 与 {@link Renderer} 协作的 HITL 处理器：
 * 状态（启用开关、全部放行集合）由本类维护，
 * 实际审批 UI 委托给 {@link Renderer#promptApproval(ApprovalRequest)}。
 *
 * <p>这样切换渲染器形态（plain / inline / lanterna）只需要换一个 Renderer 实例，
 * 不影响审批状态语义。
 */
public final class RendererHitlHandler implements HitlHandler {

    private final Renderer renderer;
    private volatile boolean enabled;
    private final Set<String> approvedAllByTool = ConcurrentHashMap.newKeySet();
    private final Set<String> approvedAllByServer = ConcurrentHashMap.newKeySet();

    public RendererHitlHandler(Renderer renderer, boolean enabled) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public synchronized ApprovalResult requestApproval(ApprovalRequest request) {
        String mcpServer = ApprovalPolicy.mcpServerName(request.toolName());
        boolean sensitivePerCall = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();

        if (!sensitivePerCall && isApprovedAllByTool(request.toolName())) {
            renderer.stream().println("  [HITL] " + request.toolName()
                    + " 已在本次会话中全部放行，自动通过");
            return ApprovalResult.approveAll();
        }
        if (!sensitivePerCall && isApprovedAllByServer(mcpServer)) {
            renderer.stream().println("  [HITL] MCP server " + mcpServer
                    + " 已在本次会话中全部放行，自动通过");
            return ApprovalResult.approveAllByServer();
        }

        ApprovalResult result = renderer.promptApproval(request);
        if (result == null) {
            return ApprovalResult.reject("渲染器返回 null");
        }
        if (result.isApprovedAllForTool()) {
            approvedAllByTool.add(request.toolName());
        } else if (result.isApprovedAllForServer() && mcpServer != null) {
            approvedAllByServer.add(mcpServer);
        }
        return result;
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return toolName != null && approvedAllByTool.contains(toolName);
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return serverName != null && approvedAllByServer.contains(serverName);
    }

    @Override
    public void clearApprovedAll() {
        approvedAllByTool.clear();
        approvedAllByServer.clear();
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        if (serverName != null) {
            approvedAllByServer.remove(serverName);
        }
    }
}
