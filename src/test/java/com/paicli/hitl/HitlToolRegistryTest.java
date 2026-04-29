package com.paicli.hitl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
    void approvedAllByServerDecisionExecutesMcpTool() {
        StubHandler stub = new StubHandler(req -> ApprovalResult.approveAllByServer());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registerMcpTool(registry, "chrome-devtools", "navigate_page", args -> "navigated");

        String result = registry.executeTool("mcp__chrome-devtools__navigate_page",
                "{\"url\":\"https://example.com\"}");

        assertEquals("navigated", result);
        assertEquals(1, stub.requestCount());
    }

    @Test
    void approvedAllByServerCacheSkipsApprovalForSameMcpServer() {
        StubHandler stub = new StubHandler(req -> {
            throw new AssertionError("server 维度已放行后不应再次触发审批");
        });
        stub.approveServer("chrome-devtools");
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registerMcpTool(registry, "chrome-devtools", "click", args -> "clicked");

        String result = registry.executeTool("mcp__chrome-devtools__click", "{\"uid\":\"1\"}");

        assertEquals("clicked", result);
        assertEquals(0, stub.requestCount());
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
