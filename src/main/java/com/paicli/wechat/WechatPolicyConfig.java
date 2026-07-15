package com.paicli.wechat;

import java.nio.file.Path;
import java.util.List;

public record WechatPolicyConfig(
        Path workspaceRoot,
        List<String> commandAllowlist,
        List<String> mcpAllowlist,
        int dailyTurnLimit,
        int singleTurnTokenBudget
) {
    public WechatPolicyConfig {
        commandAllowlist = commandAllowlist == null ? List.of() : List.copyOf(commandAllowlist);
        mcpAllowlist = mcpAllowlist == null ? List.of() : List.copyOf(mcpAllowlist);
        dailyTurnLimit = dailyTurnLimit <= 0 ? 50 : dailyTurnLimit;
        singleTurnTokenBudget = singleTurnTokenBudget <= 0 ? 64_000 : singleTurnTokenBudget;
    }

    public static WechatPolicyConfig forWorkspace(Path workspaceRoot) {
        return new WechatPolicyConfig(workspaceRoot, List.of(), List.of(), 50, 64_000);
    }
}
