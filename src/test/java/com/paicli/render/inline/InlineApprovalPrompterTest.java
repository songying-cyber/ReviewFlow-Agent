package com.paicli.render.inline;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineApprovalPrompterTest {

    @Test
    void singleCharYReturnsApprove() throws Exception {
        Terminal terminal = mockTerminalReturning('y');
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineApprovalPrompter p = new InlineApprovalPrompter(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                terminal,
                new BufferedReader(new StringReader("")));
        ApprovalResult result = p.prompt(ApprovalRequest.of("write_file", "{\"path\":\"a\"}", "test"));
        assertEquals(ApprovalResult.Decision.APPROVED, result.decision());
    }

    @Test
    void singleCharSReturnsSkip() throws Exception {
        Terminal terminal = mockTerminalReturning('s');
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineApprovalPrompter p = new InlineApprovalPrompter(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                terminal,
                new BufferedReader(new StringReader("")));
        ApprovalResult result = p.prompt(ApprovalRequest.of("write_file", "{}", "test"));
        assertEquals(ApprovalResult.Decision.SKIPPED, result.decision());
    }

    @Test
    void singleCharNFollowedByReasonReturnsRejected() throws Exception {
        Terminal terminal = mockTerminalReturning('n');
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineApprovalPrompter p = new InlineApprovalPrompter(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                terminal,
                new BufferedReader(new StringReader("too risky\n")));
        ApprovalResult result = p.prompt(ApprovalRequest.of("write_file", "{}", "test"));
        assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
        assertEquals("too risky", result.reason());
    }

    @Test
    void singleCharAOnBuiltinToolApproveAll() throws Exception {
        Terminal terminal = mockTerminalReturning('a');
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineApprovalPrompter p = new InlineApprovalPrompter(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                terminal,
                new BufferedReader(new StringReader("")));
        ApprovalResult result = p.prompt(ApprovalRequest.of("write_file", "{}", "test"));
        assertEquals(ApprovalResult.Decision.APPROVED_ALL, result.decision());
    }

    @Test
    void singleCharAOnMcpToolApproveAllByServer() throws Exception {
        Terminal terminal = mockTerminalReturning('a');
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineApprovalPrompter p = new InlineApprovalPrompter(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                terminal,
                new BufferedReader(new StringReader("server\n")));
        ApprovalResult result = p.prompt(ApprovalRequest.of("mcp__chrome-devtools__click", "{}", "test"));
        assertEquals(ApprovalResult.Decision.APPROVED_ALL_BY_SERVER, result.decision());
    }

    @Test
    void singleCharMWithValidJsonReturnsModified() throws Exception {
        Terminal terminal = mockTerminalReturning('m');
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineApprovalPrompter p = new InlineApprovalPrompter(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                terminal,
                new BufferedReader(new StringReader("{\"path\":\"safe.txt\"}\n")));
        ApprovalResult result = p.prompt(ApprovalRequest.of("write_file", "{\"path\":\"a\"}", "test"));
        assertEquals(ApprovalResult.Decision.MODIFIED, result.decision());
        assertTrue(result.modifiedArguments().contains("safe.txt"));
    }

    @Test
    void rawModeFailureFallsThroughToReject() throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.enterRawMode()).thenThrow(new RuntimeException("no tty"));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineApprovalPrompter p = new InlineApprovalPrompter(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                terminal,
                new BufferedReader(new StringReader("")));
        ApprovalResult result = p.prompt(ApprovalRequest.of("write_file", "{}", "test"));
        assertNotNull(result);
        assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
    }

    private static Terminal mockTerminalReturning(char ch) throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.enterRawMode()).thenReturn(null);
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        Mockito.when(reader.read()).thenReturn((int) ch);
        Mockito.when(terminal.reader()).thenReturn(reader);
        return terminal;
    }
}
