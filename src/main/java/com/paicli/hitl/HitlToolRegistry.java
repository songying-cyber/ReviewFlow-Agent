package com.paicli.hitl;

import com.paicli.tool.ToolRegistry;

/**
 * HITL 工具注册表 - 在危险工具调用前插入人工审批
 *
 * 继承自 ToolRegistry，覆写 executeTool 方法，在执行危险操作之前
 * 通过 HitlHandler 向用户请求审批。
 *
 * 如果 HITL 未启用，行为与父类完全相同，无额外开销。
 */
public class HitlToolRegistry extends ToolRegistry {

    private final HitlHandler hitlHandler;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        super();
        this.hitlHandler = hitlHandler;
    }

    @Override
    public String executeTool(String name, String argumentsJson) {
        // HITL 未启用或该工具不需要审批，直接执行
        if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(name)) {
            return super.executeTool(name, argumentsJson);
        }

        // 构建审批请求并发起审批
        ApprovalRequest request = ApprovalRequest.of(name, argumentsJson, null);
        ApprovalResult result = hitlHandler.requestApproval(request);

        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank()
                    ? result.reason()
                    : "用户拒绝了此操作";
            return "[HITL] 操作已被拒绝：" + reason;
        }

        if (result.isSkipped()) {
            return "[HITL] 操作已被跳过";
        }

        // 批准（含修改参数）- 使用 effectiveArguments 获取最终参数
        String effectiveArgs = result.effectiveArguments(argumentsJson);
        return super.executeTool(name, effectiveArgs);
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
