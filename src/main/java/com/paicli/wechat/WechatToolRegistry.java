package com.paicli.wechat;

import com.paicli.policy.AuditLog;
import com.paicli.tool.ToolErrorType;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;

import java.util.concurrent.TimeUnit;

public class WechatToolRegistry extends ToolRegistry {
    private final WechatPolicyDecider decider;

    public WechatToolRegistry(WechatPolicyDecider decider) {
        this.decider = decider;
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        long start = System.nanoTime();
        WechatPolicyDecision decision = decider == null
                ? WechatPolicyDecision.allow()
                : decider.decide(name, argumentsJson, getToolMetadata(name));
        if (!decision.allowed()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(
                    name,
                    argumentsJson,
                    decision.reason(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
            return ToolOutput.error(ToolErrorType.POLICY_DENIED, false,
                    "微信通道策略拒绝: " + decision.reason(),
                    "微信非交互通道不能人工审批该操作；请改用允许的只读工具或让用户回到 CLI 确认。");
        }
        return super.doExecuteTool(name, argumentsJson);
    }
}
