package com.paicli.hitl;

import java.util.Objects;

/**
 * Delegates HITL interaction to the currently active UI implementation.
 *
 * <p>The registry is created before the UI mode is selected, so this wrapper
 * lets CLI keep using {@link TerminalHitlHandler} while TUI can swap in a
 * Lanterna-backed handler without rebuilding the tool registry.
 */
public final class SwitchableHitlHandler implements HitlHandler {

    private volatile HitlHandler delegate;

    public SwitchableHitlHandler(HitlHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public void setDelegate(HitlHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public HitlHandler getDelegate() {
        return delegate;
    }

    @Override
    public ApprovalResult requestApproval(ApprovalRequest request) {
        return delegate.requestApproval(request);
    }

    @Override
    public boolean isEnabled() {
        return delegate.isEnabled();
    }

    @Override
    public void setEnabled(boolean enabled) {
        delegate.setEnabled(enabled);
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return delegate.isApprovedAllByTool(toolName);
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return delegate.isApprovedAllByServer(serverName);
    }

    @Override
    public void clearApprovedAll() {
        delegate.clearApprovedAll();
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        delegate.clearApprovedAllForServer(serverName);
    }
}
