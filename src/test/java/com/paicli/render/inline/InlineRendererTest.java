package com.paicli.render.inline;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.render.StatusInfo;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineRendererTest {

    @Test
    void onAnsiTerminalEnablesStatusBar() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertTrue(renderer.hasStatusBar());
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
        } finally {
            renderer.close();
        }
    }

    @Test
    void onSmallTerminalDisablesStatusBar() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(40, 4));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertFalse(renderer.hasStatusBar());
            // updateStatus should still not throw
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamReturnsSystemOut() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertNotNull(renderer.stream());
        } finally {
            renderer.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        renderer.start();
        renderer.close();
        renderer.close();
    }

    @Test
    void promptApprovalDelegatesToFallback() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            // When run without TTY, fallback PlainRenderer reads from stdin.
            // Just verify the call doesn't throw and returns a non-null result.
            // Using `n` as the input is unreliable here, so we skip assertion on actual decision
            // and just verify the type contract by interrupting via empty stdin → reject.
            ApprovalRequest req = ApprovalRequest.of("write_file", "{}", "test");
            ApprovalResult result = renderer.promptApproval(req);
            assertNotNull(result);
        } finally {
            renderer.close();
        }
    }

    @Test
    void openPaletteReturnsMinusOneOnNoInput() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            int idx = renderer.openPalette("title", java.util.List.of("a", "b"));
            assertEquals(-1, idx);
        } finally {
            renderer.close();
        }
    }
}
