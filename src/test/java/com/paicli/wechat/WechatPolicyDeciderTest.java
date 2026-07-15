package com.paicli.wechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WechatPolicyDeciderTest {
    @TempDir
    Path tempDir;

    @Test
    void deniesExecuteCommandByDefault() {
        WechatPolicyDecider decider = new WechatPolicyDecider(WechatPolicyConfig.forWorkspace(tempDir));
        WechatPolicyDecision decision = decider.decide("execute_command", "{\"command\":\"git status\"}");
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("execute_command"));
    }

    @Test
    void allowsOnlyExactWhitelistedCommand() {
        WechatPolicyDecider decider = new WechatPolicyDecider(
                new WechatPolicyConfig(tempDir, List.of("git status"), List.of(), 10, 1000));
        assertTrue(decider.decide("execute_command", "{\"command\":\"git status\"}").allowed());
        assertFalse(decider.decide("execute_command", "{\"command\":\"git status && rm -rf src\"}").allowed());
    }

    @Test
    void deniesMcpByDefaultAndAllowsConfiguredServer() {
        WechatPolicyDecider denied = new WechatPolicyDecider(WechatPolicyConfig.forWorkspace(tempDir));
        assertFalse(denied.decide("mcp__chrome-devtools__take_snapshot", "{}").allowed());

        WechatPolicyDecider allowed = new WechatPolicyDecider(
                new WechatPolicyConfig(tempDir, List.of(), List.of("chrome-devtools"), 10, 1000));
        assertTrue(allowed.decide("mcp__chrome-devtools__take_snapshot", "{}").allowed());
    }

    @Test
    void deniesToolsNotExplicitlyClassified() {
        WechatPolicyDecider decider = new WechatPolicyDecider(WechatPolicyConfig.forWorkspace(tempDir));
        assertFalse(decider.decide("save_memory", "{\"fact\":\"secret\"}").allowed());
        assertFalse(decider.decide("browser_connect", "{}").allowed());
    }
}
