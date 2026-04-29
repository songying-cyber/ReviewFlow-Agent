package com.paicli.hitl;

/**
 * 审批结果 - 用户对一次工具调用审批的决策
 *
 * 决策类型：
 * - APPROVED:      批准执行，使用原始参数
 * - APPROVED_ALL:  批准本次会话所有后续同类工具操作（批量模式）
 * - APPROVED_ALL_BY_SERVER: 批准本次会话同一 MCP server 的后续操作
 * - REJECTED:      拒绝执行，Agent 收到拒绝通知后可重新规划
 * - MODIFIED:      修改参数后执行（用户可以调整命令或文件内容）
 * - SKIPPED:       跳过本步骤，继续后续操作
 */
public record ApprovalResult(
        Decision decision,
        String modifiedArguments,
        String reason
) {
    public enum Decision {
        APPROVED,
        APPROVED_ALL,
        APPROVED_ALL_BY_SERVER,
        REJECTED,
        MODIFIED,
        SKIPPED
    }

    public static ApprovalResult approve() {
        return new ApprovalResult(Decision.APPROVED, null, null);
    }

    public static ApprovalResult approveAll() {
        return new ApprovalResult(Decision.APPROVED_ALL, null, null);
    }

    public static ApprovalResult approveAllByServer() {
        return new ApprovalResult(Decision.APPROVED_ALL_BY_SERVER, null, null);
    }

    public static ApprovalResult reject(String reason) {
        return new ApprovalResult(Decision.REJECTED, null, reason);
    }

    public static ApprovalResult modify(String modifiedArguments) {
        return new ApprovalResult(Decision.MODIFIED, modifiedArguments, null);
    }

    public static ApprovalResult skip() {
        return new ApprovalResult(Decision.SKIPPED, null, null);
    }

    public boolean isApproved() {
        return decision == Decision.APPROVED || decision == Decision.APPROVED_ALL
                || decision == Decision.APPROVED_ALL_BY_SERVER
                || decision == Decision.MODIFIED;
    }

    public boolean isApprovedAll() {
        return decision == Decision.APPROVED_ALL;
    }

    public boolean isApprovedAllForTool() {
        return decision == Decision.APPROVED_ALL;
    }

    public boolean isApprovedAllForServer() {
        return decision == Decision.APPROVED_ALL_BY_SERVER;
    }

    public boolean isRejected() {
        return decision == Decision.REJECTED;
    }

    public boolean isSkipped() {
        return decision == Decision.SKIPPED;
    }

    /**
     * 获取实际执行时使用的参数
     * 如果是 MODIFIED，返回用户修改后的参数；否则返回 null（调用方使用原始参数）
     */
    public String effectiveArguments(String originalArguments) {
        if (decision == Decision.MODIFIED && modifiedArguments != null && !modifiedArguments.isBlank()) {
            return modifiedArguments;
        }
        return originalArguments;
    }
}
