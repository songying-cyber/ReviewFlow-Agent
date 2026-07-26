package com.paicli.hitl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.browser.BrowserGuard;
import com.paicli.browser.BrowserSession;
import com.paicli.browser.SensitivePagePolicy;
import com.paicli.mcp.protocol.McpToolDescriptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class HitlToolRegistryTest {

    // ------------------ 旁路行为（原有测试保留） ------------------

    @Test
    void disabledHitlPassesThroughToParent() {
        TerminalHitlHandler handler = new TerminalHitlHandler(false);
        HitlToolRegistry registry = new HitlToolRegistry(handler);

        assertFalse(handler.isEnabled());
        handler.setEnabled(true);
        // list_dir 不是危险工具，HITL 启用也应直接通过
        String result = registry.executeTool("list_dir", "{\"path\": \".\"}");
        assertNotNull(result);
        assertFalse(result.startsWith("[HITL]"));
    }

    @Test
    void hitlHandlerIsReturnedFromGetter() {
        TerminalHitlHandler handler = new TerminalHitlHandler(false);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        assertSame(handler, registry.getHitlHandler());
    }

    @Test
    void enableAndDisableHitl() {
        TerminalHitlHandler handler = new TerminalHitlHandler(false);
        assertFalse(handler.isEnabled());
        handler.setEnabled(true);
        assertTrue(handler.isEnabled());
        handler.setEnabled(false);
        assertFalse(handler.isEnabled());
    }

    @Test
    void clearApprovedAllResetsState() {
        TerminalHitlHandler handler = new TerminalHitlHandler(true);
        handler.clearApprovedAll();
        assertTrue(handler.isEnabled());
    }

    @Test
    void lowWriteToolDoesNotRequestApprovalByDefault() {
        StubHandler stub = new StubHandler(req -> ApprovalResult.reject("should not ask"));
        HitlToolRegistry registry = new HitlToolRegistry(stub);

        String result = registry.executeTool("save_memory", "{\"fact\":\"x\"}");

        assertTrue(result.contains("记忆保存器未初始化"));
        assertEquals(0, stub.requestCount(), "低风险写默认不应触发 HITL");
    }

    // ------------------ 开启 HITL 后的决策分支（新增） ------------------

    @Test
    void rejectedDecisionBlocksExecutionAndReturnsRejectMessage(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("should-not-exist.txt");
        StubHandler stub = new StubHandler(req -> ApprovalResult.reject("too risky"));
        HitlToolRegistry registry = new HitlToolRegistry(stub);

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"x\"}");

        assertTrue(result.startsWith("[HITL]"), "结果应为 HITL 拒绝消息: " + result);
        assertTrue(result.contains("too risky"));
        assertFalse(Files.exists(target), "拒绝后文件不应被创建");
        assertEquals(1, stub.requestCount(), "应只发起一次审批");
    }

    @Test
    void skippedDecisionBlocksExecution(@TempDir Path tempDir) {
        Path target = tempDir.resolve("skipped.txt");
        StubHandler stub = new StubHandler(req -> ApprovalResult.skip());
        HitlToolRegistry registry = new HitlToolRegistry(stub);

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"x\"}");

        assertTrue(result.startsWith("[HITL]"), "结果应为 HITL 跳过消息: " + result);
        assertTrue(result.contains("跳过"));
        assertFalse(Files.exists(target));
    }

    @Test
    void approvedDecisionExecutesToolWithOriginalArgs(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("approved.txt");
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"approved\"}");

        assertFalse(result.startsWith("[HITL]"));
        assertTrue(Files.exists(target));
        assertEquals("approved", Files.readString(target));
    }

    @Test
    void modifiedDecisionExecutesToolWithModifiedArgs(@TempDir Path tempDir) throws Exception {
        Path original = tempDir.resolve("original.txt");
        Path modified = tempDir.resolve("modified.txt");

        String modifiedArgs = "{\"path\":\"" + modified.toString().replace("\\", "\\\\") + "\",\"content\":\"modified!\"}";
        StubHandler stub = new StubHandler(req -> ApprovalResult.modify(modifiedArgs));
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + original.toString().replace("\\", "\\\\") + "\",\"content\":\"oops\"}");

        assertFalse(result.startsWith("[HITL]"), "MODIFIED 应实际执行工具: " + result);
        assertFalse(Files.exists(original), "原始路径不应被写入");
        assertTrue(Files.exists(modified), "修改后的路径应被写入");
        assertEquals("modified!", Files.readString(modified));
    }

    @Test
    void approvalRequestIncludesFingerprintAndWriteFilePreview(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("preview.txt");
        Files.writeString(target, "old\nsame\n");
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        registry.executeTool("write_file", "{\"path\":\"preview.txt\",\"content\":\"new\\nsame\\n\"}");

        ApprovalRequest request = stub.received.get(0);
        assertNotNull(request.fingerprint());
        assertFalse(request.fingerprint().isBlank());
        assertEquals(ApprovalActionType.TOOL_CALL, request.actionType());
        assertEquals("write_file", request.subject());
        assertTrue(request.preview().contains("modify file: preview.txt"));
        assertTrue(request.preview().contains("- 1 | old"));
        assertTrue(request.preview().contains("+ 1 | new"));
    }

    @Test
    void canonicalFingerprintIgnoresJsonFieldOrder(@TempDir Path tempDir) {
        HitlToolRegistry registry = new HitlToolRegistry(new StubHandler(req -> ApprovalResult.approve()));
        registry.setProjectPath(tempDir.toString());

        String first = registry.fingerprintFor("write_file",
                "{\"path\":\"a.txt\",\"content\":\"x\",\"write_mode\":\"overwrite\"}",
                registry.getToolMetadata("write_file"));
        String second = registry.fingerprintFor("write_file",
                "{\"write_mode\":\"overwrite\",\"content\":\"x\",\"path\":\"a.txt\"}",
                registry.getToolMetadata("write_file"));

        assertEquals(first, second);
    }

    @Test
    void modifiedArgumentsAreAuditedWithFinalFingerprint(@TempDir Path tempDir) throws Exception {
        Path original = tempDir.resolve("audit-original.txt");
        Path modified = tempDir.resolve("audit-modified.txt");
        String modifiedArgs = "{\"path\":\"audit-modified.txt\",\"content\":\"modified\"}";
        StubHandler stub = new StubHandler(req -> ApprovalResult.modify(modifiedArgs));
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        registry.executeTool("write_file", "{\"path\":\"audit-original.txt\",\"content\":\"original\"}");

        String expected = registry.fingerprintFor("write_file", modifiedArgs, registry.getToolMetadata("write_file"));
        var entry = registry.getAuditLog().readRecent(1).get(0);
        assertEquals(expected, entry.fingerprint());
        assertTrue(Files.exists(modified));
        assertFalse(Files.exists(original));
    }

    @Test
    void approvedAllDecisionExecutesTool(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("approved-all.txt");
        StubHandler stub = new StubHandler(req -> ApprovalResult.approveAll());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"ok\"}");

        assertFalse(result.startsWith("[HITL]"));
        assertTrue(Files.exists(target));
    }

    @Test
    void approvedAllByServerDecisionIsRejectedForHighRiskMcpTool() {
        StubHandler stub = new StubHandler(req -> ApprovalResult.approveAllByServer());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registerMcpTool(registry, "chrome-devtools", "navigate_page", args -> "navigated");

        String result = registry.executeTool("mcp__chrome-devtools__navigate_page",
                "{\"url\":\"https://example.com\"}");

        assertTrue(result.contains("高风险操作不支持全部放行"));
        assertEquals(1, stub.requestCount());
    }

    @Test
    void approvedAllByServerCacheDoesNotSkipHighRiskMcpTool() {
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        stub.approveServer("chrome-devtools");
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registerMcpTool(registry, "chrome-devtools", "click", args -> "clicked");

        String result = registry.executeTool("mcp__chrome-devtools__click", "{\"uid\":\"1\"}");

        assertEquals("clicked", result);
        assertEquals(1, stub.requestCount());
    }

    @Test
    void sensitiveBrowserToolBypassesApprovedAllByServerCache(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("sensitive_patterns.txt");
        Files.writeString(rules, "*://example.com/admin/*\n");
        BrowserSession session = new BrowserSession();
        session.switchToShared("http://127.0.0.1:9222");
        session.rememberNavigation("https://example.com/admin/users");
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        stub.approveServer("chrome-devtools");
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setBrowserGuard(new BrowserGuard(session, new SensitivePagePolicy(rules)));
        registerMcpTool(registry, "chrome-devtools", "click", args -> "clicked");

        String result = registry.executeTool("mcp__chrome-devtools__click", "{\"uid\":\"1\"}");

        assertEquals("clicked", result);
        assertEquals(1, stub.requestCount());
        assertNotNull(stub.received.get(0).sensitiveNotice());
    }

    @Test
    void nonDangerousToolSkipsApprovalEvenWhenEnabled() {
        StubHandler stub = new StubHandler(req -> {
            throw new AssertionError("non-dangerous 工具不应触发审批");
        });
        HitlToolRegistry registry = new HitlToolRegistry(stub);

        String result = registry.executeTool("list_dir", "{\"path\":\".\"}");
        assertFalse(result.startsWith("[HITL]"));
        assertEquals(0, stub.requestCount());
    }

    /** 可预设决策结果的 HitlHandler stub。 */
    private static final class StubHandler implements HitlHandler {
        private final Function<ApprovalRequest, ApprovalResult> decision;
        private final List<ApprovalRequest> received = new ArrayList<>();
        private final List<String> approvedServers = new ArrayList<>();
        private boolean enabled = true;

        StubHandler(Function<ApprovalRequest, ApprovalResult> decision) {
            this.decision = decision;
        }

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            received.add(request);
            return decision.apply(request);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        int requestCount() {
            return received.size();
        }

        void approveServer(String serverName) {
            approvedServers.add(serverName);
        }

        @Override
        public boolean isApprovedAllByServer(String serverName) {
            return approvedServers.contains(serverName);
        }
    }

    private static void registerMcpTool(HitlToolRegistry registry, String serverName, String toolName,
                                        Function<String, String> invoker) {
        registry.registerMcpTool(new McpToolDescriptor(
                serverName,
                toolName,
                McpToolDescriptor.namespaced(serverName, toolName),
                "test tool",
                JsonNodeFactory.instance.objectNode()
        ), invoker);
    }
}
