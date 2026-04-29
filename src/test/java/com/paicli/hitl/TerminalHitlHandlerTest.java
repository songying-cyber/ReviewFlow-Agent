package com.paicli.hitl;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 TerminalHitlHandler 的五种决策路径 + 未知输入 fail-safe 行为。
 * 通过构造器注入假的 stdin / stdout，不依赖真实终端。
 */
class TerminalHitlHandlerTest {

    private static final ApprovalRequest WRITE_FILE_REQUEST =
            ApprovalRequest.of("write_file", "{\"path\":\"/tmp/x.txt\",\"content\":\"hi\"}", null);
    private static final ApprovalRequest MCP_CHROME_REQUEST =
            ApprovalRequest.of("mcp__chrome-devtools__navigate_page", "{\"url\":\"https://example.com\"}", null);
    private static final ApprovalRequest MCP_CHROME_CLICK_REQUEST =
            ApprovalRequest.of("mcp__chrome-devtools__click", "{\"uid\":\"1\"}", null);

    @Test
    void yInputApproves() {
        Harness h = Harness.withInput("y\n");
        assertEquals(ApprovalResult.Decision.APPROVED,
                h.handler.requestApproval(WRITE_FILE_REQUEST).decision());
    }

    @Test
    void enterAloneApproves() {
        Harness h = Harness.withInput("\n");
        assertEquals(ApprovalResult.Decision.APPROVED,
                h.handler.requestApproval(WRITE_FILE_REQUEST).decision());
    }

    @Test
    void aInputApprovesAllAndCachesTool() {
        // 一次性准备两轮输入：第一次 a 触发缓存，第二次根本不读 stdin（缓存命中）
        Harness h = Harness.withInput("a\n\n");
        assertEquals(ApprovalResult.Decision.APPROVED_ALL,
                h.handler.requestApproval(WRITE_FILE_REQUEST).decision());

        ApprovalResult cached = h.handler.requestApproval(WRITE_FILE_REQUEST);
        assertEquals(ApprovalResult.Decision.APPROVED_ALL, cached.decision());
        assertTrue(h.output().contains("已在本次会话中全部放行"),
                "第二次应命中缓存；实际输出：" + h.output());
    }

    @Test
    void aInputForMcpCanApproveEntireServer() {
        Harness h = Harness.withInput("a\nserver\n");
        ApprovalResult result = h.handler.requestApproval(MCP_CHROME_REQUEST);

        assertEquals(ApprovalResult.Decision.APPROVED_ALL_BY_SERVER, result.decision());
        assertTrue(h.handler.isApprovedAllByServer("chrome-devtools"));
        assertFalse(h.handler.isApprovedAllByTool("mcp__chrome-devtools__navigate_page"));
        assertTrue(h.output().contains("MCP server chrome-devtools"));
    }

    @Test
    void approvedServerCacheSkipsLaterMcpToolsFromSameServer() {
        Harness h = Harness.withInput("a\nserver\n");
        h.handler.requestApproval(MCP_CHROME_REQUEST);

        ApprovalResult cached = h.handler.requestApproval(MCP_CHROME_CLICK_REQUEST);

        assertEquals(ApprovalResult.Decision.APPROVED_ALL_BY_SERVER, cached.decision());
        assertTrue(h.output().contains("已在本次会话中全部放行"));
    }

    @Test
    void nInputRejectsWithReason() {
        Harness h = Harness.withInput("n\n太危险\n");
        ApprovalResult result = h.handler.requestApproval(WRITE_FILE_REQUEST);
        assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
        assertEquals("太危险", result.reason());
    }

    @Test
    void nInputWithEmptyReasonIsAllowed() {
        Harness h = Harness.withInput("n\n\n");
        ApprovalResult result = h.handler.requestApproval(WRITE_FILE_REQUEST);
        assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
        assertEquals("", result.reason());
    }

    @Test
    void sInputSkips() {
        Harness h = Harness.withInput("s\n");
        assertEquals(ApprovalResult.Decision.SKIPPED,
                h.handler.requestApproval(WRITE_FILE_REQUEST).decision());
    }

    @Test
    void mInputWithValidJsonModifies() {
        String modified = "{\"path\":\"/tmp/safe.txt\",\"content\":\"hi\"}";
        Harness h = Harness.withInput("m\n" + modified + "\n");
        ApprovalResult result = h.handler.requestApproval(WRITE_FILE_REQUEST);
        assertEquals(ApprovalResult.Decision.MODIFIED, result.decision());
        assertEquals(modified, result.modifiedArguments());
    }

    @Test
    void mInputWithEmptyFallsBackToApprove() {
        Harness h = Harness.withInput("m\n\n");
        assertEquals(ApprovalResult.Decision.APPROVED,
                h.handler.requestApproval(WRITE_FILE_REQUEST).decision());
    }

    @Test
    void mInputWithInvalidJsonReprompts() {
        // 先输入非法 JSON，系统提示错误并回到主菜单；第二次选 y 批准
        Harness h = Harness.withInput("m\nnot-json\ny\n");
        ApprovalResult result = h.handler.requestApproval(WRITE_FILE_REQUEST);
        assertEquals(ApprovalResult.Decision.APPROVED, result.decision());
        assertTrue(h.output().contains("不是合法 JSON"),
                "应提示 JSON 非法；实际输出：" + h.output());
    }

    @Test
    void unknownInputRepromptsInsteadOfDefaultApproving() {
        // foo 不是有效选项 → 必须重新提示，绝不能默认放行（fail-safe）
        Harness h = Harness.withInput("foo\ny\n");
        ApprovalResult result = h.handler.requestApproval(WRITE_FILE_REQUEST);
        assertEquals(ApprovalResult.Decision.APPROVED, result.decision());
        assertTrue(h.output().contains("无法识别的选项"),
                "未知输入必须提示而非直接批准；实际输出：" + h.output());
    }

    @Test
    void repeatedInvalidInputEventuallyRejects() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append("garbage\n");
        }
        Harness h = Harness.withInput(sb.toString());
        assertEquals(ApprovalResult.Decision.REJECTED,
                h.handler.requestApproval(WRITE_FILE_REQUEST).decision());
    }

    @Test
    void clearApprovedAllResetsCache() {
        Harness h = Harness.withInput("a\n\ny\n");
        h.handler.requestApproval(WRITE_FILE_REQUEST);
        h.handler.clearApprovedAll();
        // 清空后需要重新审批，会读取第二行 "y"
        assertEquals(ApprovalResult.Decision.APPROVED,
                h.handler.requestApproval(WRITE_FILE_REQUEST).decision());
    }

    @Test
    void eofStreamRejectsSafely() {
        Harness h = Harness.withInput("");  // 空输入 → readLine 返回 null
        assertEquals(ApprovalResult.Decision.REJECTED,
                h.handler.requestApproval(WRITE_FILE_REQUEST).decision());
    }

    @Test
    void disabledHandlerShortCircuits() {
        // 关闭状态下不应由 HitlToolRegistry 调用 requestApproval；但如果直接调用也得有合理行为
        // 这里验证 disabled 状态仅影响 isEnabled()，不改变 requestApproval 语义本身
        Harness h = Harness.withInput("y\n");
        h.handler.setEnabled(false);
        assertFalse(h.handler.isEnabled());
        // 即便 disabled，requestApproval 被直接调用仍应按输入返回 APPROVED
        assertEquals(ApprovalResult.Decision.APPROVED,
                h.handler.requestApproval(WRITE_FILE_REQUEST).decision());
    }

    /**
     * 测试辅助：封装 handler + stdin + stdout 缓冲。
     * 单个 handler 复用整个测试用例周期，所有轮次的输入在构造时一次性塞入。
     */
    private static final class Harness {
        private final TerminalHitlHandler handler;
        private final ByteArrayOutputStream bout;

        private Harness(TerminalHitlHandler handler, ByteArrayOutputStream bout) {
            this.handler = handler;
            this.bout = bout;
        }

        static Harness withInput(String stdinText) {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            PrintStream pout = new PrintStream(bout, true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(new StringReader(stdinText));
            TerminalHitlHandler handler = new TerminalHitlHandler(true, in, pout);
            return new Harness(handler, bout);
        }

        String output() {
            return bout.toString(StandardCharsets.UTF_8);
        }
    }
}
