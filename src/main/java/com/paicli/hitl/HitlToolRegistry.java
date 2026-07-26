package com.paicli.hitl;

import com.paicli.browser.BrowserCheckResult;
import com.paicli.policy.AuditLog;
import com.paicli.tool.ToolErrorType;
import com.paicli.tool.ToolMetadata;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;

import java.util.concurrent.TimeUnit;

/**
 * HITL 工具注册表 - 在危险工具调用前插入人工审批
 *
 * 继承自 ToolRegistry，覆写 executeTool 方法，在执行危险操作之前
 * 通过 HitlHandler 向用户请求审批。
 *
 * 如果 HITL 未启用，行为与父类完全相同，无额外开销。
 *
 * HITL 拒绝 / 跳过路径会写一行 audit（approver=hitl），HITL 通过后由父类 ToolRegistry 写
 * allow / policy-deny / error，HITL 审批与策略拦截共用同一份 ~/.paicli/audit/ 文件。
 */
public class HitlToolRegistry extends ToolRegistry {

    private final HitlHandler hitlHandler;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        super();
        this.hitlHandler = hitlHandler;
    }

    @Override
    public String executeTool(String name, String argumentsJson) {
        return executeToolOutput(name, argumentsJson).text();
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        ToolMetadata metadata = getToolMetadata(name);
        // HITL 未启用或该工具不需要审批，直接执行
        if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(metadata)) {
            return super.doExecuteTool(name, argumentsJson);
        }
        if (isSandboxAutoAllowedCommand(name, argumentsJson)) {
            return super.doExecuteTool(name, argumentsJson);
        }
        BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, true);
        if (browserCheck.blocked()) {
            return super.doExecuteTool(name, argumentsJson);
        }
        if (browserCheck.requiresPerCallApproval()) {
            return executeAfterExplicitApproval(name, argumentsJson, metadata, browserCheck.sensitiveNotice());
        }
        String mcpServer = ApprovalPolicy.mcpServerName(name);
        boolean allowApproveAll = metadata.riskLevel().allowsApproveAll();
        if (allowApproveAll && (hitlHandler.isApprovedAllByTool(name) || hitlHandler.isApprovedAllByServer(mcpServer))) {
            return super.doExecuteTool(name, argumentsJson);
        }

        return executeAfterExplicitApproval(name, argumentsJson, metadata, null);
    }

    private ToolOutput executeAfterExplicitApproval(String name, String argumentsJson, ToolMetadata metadata,
                                                    String sensitiveNotice) {
        long start = System.nanoTime();
        ApprovalRequest request = createApprovalRequest(name, argumentsJson, metadata, sensitiveNotice);
        ApprovalResult result = hitlHandler.requestApproval(request);

        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank()
                    ? result.reason()
                    : "用户拒绝了此操作";
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, reason, elapsedMillis(start), request.fingerprint()));
            return ToolOutput.error(ToolErrorType.APPROVAL_DENIED, false,
                    "[HITL] 操作已被拒绝：" + reason,
                    "不要绕过用户拒绝；请向用户说明已取消该操作，或等待用户明确给出新的授权。");
        }

        if (result.isSkipped()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, "用户跳过", elapsedMillis(start), request.fingerprint()));
            return ToolOutput.error(ToolErrorType.APPROVAL_DENIED, true,
                    "[HITL] 操作已被跳过",
                    "本次操作未执行；可以继续处理无需该操作的部分，或等待用户重新确认。");
        }

        if ((result.isApprovedAllForTool() || result.isApprovedAllForServer()) && !request.allowsApproveAll()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, "高风险操作不支持全部放行", elapsedMillis(start), request.fingerprint()));
            return ToolOutput.error(ToolErrorType.APPROVAL_DENIED, true,
                    "[HITL] 高风险操作不支持全部放行，请逐次批准",
                    "请重新发起同一高风险动作的单次审批，不要使用 approve-all。");
        }

        // 批准（含修改参数）- 使用 effectiveArguments 获取最终参数；父类执行路径会负责 allow audit
        String effectiveArgs = result.effectiveArguments(argumentsJson);
        ApprovalRequest effectiveRequest = createApprovalRequest(name, effectiveArgs, metadata, sensitiveNotice);
        return super.doExecuteToolWithApprovalFingerprint(name, effectiveArgs, effectiveRequest.fingerprint());
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
